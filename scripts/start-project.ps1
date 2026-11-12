param(
    [int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $repoRoot '.runtime\project'
$logDir = Join-Path $runtimeDir 'logs'
$manifestPath = Join-Path $runtimeDir 'processes.json'
$backendDir = Join-Path $repoRoot 'backend'
$frontendDir = Join-Path $repoRoot 'frontend'
$backendPort = 8080
$frontendPort = 5173
$startedProcesses = New-Object System.Collections.Generic.List[object]
$oldEnvironment = @{}
$mysqlStartedByScript = $false

function Save-EnvironmentValue([string]$Name, [string]$Value) {
    if (-not $oldEnvironment.ContainsKey($Name)) {
        $oldEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Read-SecureValue([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Test-TcpPort([string]$HostName, [int]$Port, [int]$TimeoutMs = 1200) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

function Stop-StartedProcess([int]$ProcessId) {
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        & taskkill.exe /PID $ProcessId /T /F | Out-Null
    }
}

function Test-HttpOk([string]$HostName, [int]$Port, [string]$Path, [int]$TimeoutMs = 3000) {
    # Raw TCP + hand-written HTTP/1.1 GET. Immune to system proxy settings,
    # Invoke-WebRequest quirks and PowerShell version differences. Returns $true
    # only when the server answers with a 2xx status line.
    if (-not $Path) { $Path = '/' }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        $stream = $client.GetStream()
        $stream.ReadTimeout = $TimeoutMs
        $stream.WriteTimeout = $TimeoutMs
        $request = "GET $Path HTTP/1.1`r`nHost: ${HostName}:${Port}`r`nConnection: close`r`n`r`n"
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($request)
        $stream.Write($bytes, 0, $bytes.Length)
        $buffer = New-Object byte[] 16
        $count = $stream.Read($buffer, 0, $buffer.Length)
        if ($count -le 0) { return $false }
        $statusLine = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $count)
        return ($statusLine -match '^HTTP/\d\.\d\s+2\d\d')
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

function Wait-HttpReady([string]$Url, [int]$TimeoutSeconds, [int]$ProcessId, [string]$Label) {
    # Wait until the server both owns the port AND answers HTTP 200. TCP-only was
    # not enough: a process can hold the port while no longer serving.
    # Windows PowerShell 5.1 does not expose the 3-arg [Uri]::TryParse overload,
    # so we parse via a safe cast instead (works on both 5.1 and 7).
    $hostName = '127.0.0.1'
    $port = 8080
    $path = '/'
    try {
        $uri = [System.Uri]$Url
        if ($uri.Host -eq 'localhost') { $hostName = '127.0.0.1' } else { $hostName = $uri.Host }
        $port = $uri.Port
        if ($uri.AbsolutePath) { $path = $uri.AbsolutePath }
        if ($uri.Query) { $path = $path + $uri.Query }
    }
    catch {
        # keep the defaults above
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "$Label process exited. Check its log under $logDir."
        }
        if (Test-HttpOk $hostName $port $path) { return }
        Start-Sleep -Seconds 2
    }
    throw "$Label did not become ready within $TimeoutSeconds seconds. Check its log under $logDir."
}

function Get-QuotedPath([string]$Path) {
    return "'" + $Path.Replace("'", "''") + "'"
}

try {
    foreach ($commandName in @('java', 'mvn', 'node', 'npm')) {
        if (-not (Get-Command $commandName -ErrorAction SilentlyContinue)) {
            throw "Required command '$commandName' was not found. Install the documented project dependencies and reopen PowerShell."
        }
    }

    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'

    if (Test-Path -LiteralPath $manifestPath) {
        $existing = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
        $active = @($existing.processes | Where-Object { Get-Process -Id $_.pid -ErrorAction SilentlyContinue })
        if ($active.Count -gt 0) {
            throw "The project appears to be running already. Use scripts/stop-project.ps1 before starting another copy."
        }
        Remove-Item -LiteralPath $manifestPath -Force
    }

    $mysqlService = $null
    # Prefer a self-contained local MySQL instance from .runtime\mysql so the
    # project never depends on a (fragile) Windows service.
    $mysqlDir = Join-Path $repoRoot '.runtime\mysql'
    $mysqlIni = Join-Path $mysqlDir 'my.ini'
    $mysqlBin = $null
    if (Test-Path -LiteralPath $mysqlIni) {
        $iniLines = Get-Content -LiteralPath $mysqlIni
        $basedirLine = @($iniLines | Where-Object { $_ -match '^\s*basedir\s*=' })[0]
        if ($basedirLine) {
            $basedir = $basedirLine -replace '^\s*basedir\s*=\s*["]?([^"\r\n]+)["]?\s*$', '$1'
            $candidateBin = Join-Path $basedir 'bin\mysqld.exe'
            if (Test-Path -LiteralPath $candidateBin) { $mysqlBin = $candidateBin }
        }
    }

    $dbUrl = if ($env:DB_URL) { $env:DB_URL } else { 'jdbc:mysql://127.0.0.1:3306/arrears_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai' }
    $dbMatch = [regex]::Match($dbUrl, '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)')
    if (-not $dbMatch.Success) { throw 'DB_URL must use a jdbc:mysql://host:port/database URL.' }
    $dbHost = $dbMatch.Groups[1].Value
    $dbPort = if ($dbMatch.Groups[2].Success) { [int]$dbMatch.Groups[2].Value } else { 3306 }
    if (-not (Test-TcpPort $dbHost $dbPort)) {
        if ($mysqlBin -and (Test-Path -LiteralPath $mysqlIni)) {
            Write-Host "Starting local MySQL from $mysqlIni ..."
            $mysqlLogDir = Join-Path $mysqlDir 'logs'
            $mysqlTmpDir = Join-Path $mysqlDir 'tmp'
            New-Item -ItemType Directory -Path $mysqlLogDir -Force | Out-Null
            New-Item -ItemType Directory -Path $mysqlTmpDir -Force | Out-Null
            $mysqlOutLog = Join-Path $mysqlLogDir "mysql-$timestamp.out.log"
            $mysqlErrorLog = Join-Path $mysqlLogDir "mysql-$timestamp.err.log"
            $mysqlProcess = Start-Process -FilePath $mysqlBin -WorkingDirectory $mysqlDir `
                -ArgumentList @("--defaults-file=$mysqlIni", '--console') `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput $mysqlOutLog -RedirectStandardError $mysqlErrorLog
            $mysqlProcess.Refresh()
            $startedProcesses.Add([pscustomobject]@{ name = 'mysql'; pid = $mysqlProcess.Id; startedUtcTicks = $mysqlProcess.StartTime.ToUniversalTime().Ticks; command = 'mysqld --defaults-file'; log = $mysqlOutLog; errorLog = $mysqlErrorLog })
            $mysqlStartedByScript = $true
            $mysqlDeadline = (Get-Date).AddSeconds(30)
            while ((Get-Date) -lt $mysqlDeadline) {
                if (-not (Get-Process -Id $mysqlProcess.Id -ErrorAction SilentlyContinue)) {
                    throw "MySQL process exited during startup. Check its log: $mysqlErrorLog"
                }
                if (Test-TcpPort $dbHost $dbPort) { break }
                Start-Sleep -Seconds 1
            }
            if (-not (Test-TcpPort $dbHost $dbPort)) {
                throw "MySQL did not become ready on ${dbHost}:$dbPort within 30s. Check its log: $mysqlErrorLog"
            }
        }
        else {
            throw "MySQL is not reachable at ${dbHost}:$dbPort and no local MySQL config/bin was found under '$mysqlDir'. Start MySQL manually or install MySQL 8.4."
        }
    }

    if (-not $env:DB_USERNAME) { Save-EnvironmentValue 'DB_USERNAME' 'root' }
    if (-not $env:DB_PASSWORD) {
        $dbPassword = Read-SecureValue 'MySQL password (press Enter if the account has no password)'
        Save-EnvironmentValue 'DB_PASSWORD' $dbPassword
    }
    if (-not $env:APP_ADMIN_USERNAME) { Save-EnvironmentValue 'APP_ADMIN_USERNAME' 'admin' }
    if (-not $env:APP_ADMIN_PASSWORD) {
        $adminPassword = Read-SecureValue 'Initial admin password (used only if the account does not exist)'
        if ([string]::IsNullOrWhiteSpace($adminPassword)) { throw 'An initial admin password is required.' }
        Save-EnvironmentValue 'APP_ADMIN_PASSWORD' $adminPassword
    }
    if (-not $env:DB_URL) { Save-EnvironmentValue 'DB_URL' $dbUrl }

    if (Test-TcpPort '127.0.0.1' $backendPort) {
        throw "Port $backendPort is already in use. The script will not stop an unrelated process."
    }
    if (Test-TcpPort '127.0.0.1' $frontendPort) {
        throw "Port $frontendPort is already in use. The script will not stop an unrelated process."
    }

    $nodeModules = Join-Path $frontendDir 'node_modules'
    if (-not (Test-Path -LiteralPath $nodeModules)) {
        Write-Host 'Installing frontend dependencies with npm ci...'
        Push-Location $frontendDir
        try { & npm ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' } }
        finally { Pop-Location }
    }

    $backendLog = Join-Path $logDir "backend-$timestamp.out.log"
    $backendErrorLog = Join-Path $logDir "backend-$timestamp.err.log"
    $frontendLog = Join-Path $logDir "frontend-$timestamp.out.log"
    $frontendErrorLog = Join-Path $logDir "frontend-$timestamp.err.log"
    $powershellExe = Join-Path $PSHOME 'powershell.exe'
    $quotedBackend = Get-QuotedPath $backendDir
    $quotedFrontend = Get-QuotedPath $frontendDir
    $backendCommand = "Set-Location -LiteralPath $quotedBackend; mvn spring-boot:run"
    # Prefer launching Vite directly through node instead of `npm run dev`.
    # The npm wrapper (npm.cmd -> node -> cmd -> vite.cmd -> node) forwards exit
    # signals to its whole child tree, which is the prime suspect for the dev
    # server dying shortly after startup while the mvn/java backend survived.
    $nodeExe = (Get-Command node -ErrorAction Stop).Source
    $viteEntry = Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
    $useDirectVite = Test-Path -LiteralPath $viteEntry
    $frontendCommandText = if ($useDirectVite) { 'node node_modules/vite/bin/vite.js --host 127.0.0.1 --strictPort' } else { 'npm run dev -- --host 127.0.0.1 --strictPort' }
    $frontendCommand = "Set-Location -LiteralPath $quotedFrontend; npm run dev -- --host 127.0.0.1 --strictPort"

    Write-Host 'Starting backend...'
    $backendProcess = Start-Process -FilePath $powershellExe -WorkingDirectory $backendDir `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $backendCommand) `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $backendLog -RedirectStandardError $backendErrorLog
    $backendProcess.Refresh()
    $startedProcesses.Add([pscustomobject]@{ name = 'backend'; pid = $backendProcess.Id; startedUtcTicks = $backendProcess.StartTime.ToUniversalTime().Ticks; command = 'mvn spring-boot:run'; log = $backendLog; errorLog = $backendErrorLog })
    Wait-HttpReady 'http://127.0.0.1:8080/api/health' $StartupTimeoutSeconds $backendProcess.Id 'Backend'

    Write-Host 'Starting frontend...'
    if ($useDirectVite) {
        $frontendProcess = Start-Process -FilePath $nodeExe -WorkingDirectory $frontendDir `
            -ArgumentList @($viteEntry, '--host', '127.0.0.1', '--strictPort') `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendErrorLog
    }
    else {
        $frontendProcess = Start-Process -FilePath $powershellExe -WorkingDirectory $frontendDir `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $frontendCommand) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendErrorLog
    }
    $frontendProcess.Refresh()
    $startedProcesses.Add([pscustomobject]@{ name = 'frontend'; pid = $frontendProcess.Id; startedUtcTicks = $frontendProcess.StartTime.ToUniversalTime().Ticks; command = $frontendCommandText; log = $frontendLog; errorLog = $frontendErrorLog })
    Wait-HttpReady 'http://127.0.0.1:5173/' $StartupTimeoutSeconds $frontendProcess.Id 'Frontend'

    # Settle check: catch a dev server that dies right after becoming ready. This
    # is exactly the failure that produced the rendered login page followed by
    # "Failed to fetch" (the page was served while Vite was alive, then vanished).
    Start-Sleep -Seconds 5
    foreach ($info in @($startedProcesses.ToArray() | Where-Object { $_.name -ne 'mysql' })) {
        if (-not (Get-Process -Id ([int]$info.pid) -ErrorAction SilentlyContinue)) {
            throw "$($info.name) exited right after startup. Check its log: $($info.errorLog)"
        }
    }
    if (-not (Test-HttpOk '127.0.0.1' $backendPort '/api/health')) {
        throw "Backend stopped answering http://127.0.0.1:$backendPort/api/health right after startup. Check $backendErrorLog."
    }
    if (-not (Test-HttpOk '127.0.0.1' $frontendPort '/')) {
        throw "Frontend stopped answering http://127.0.0.1:$frontendPort/ right after startup. Check $frontendErrorLog."
    }

    $manifest = [pscustomobject]@{
        repoRoot = $repoRoot
        startedAt = (Get-Date).ToString('o')
        processes = $startedProcesses.ToArray()
        mysqlService = if ($mysqlService) { $mysqlService.Name } else { $null }
        mysqlStartedByScript = $mysqlStartedByScript
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

    try { Start-Process 'http://127.0.0.1:5173/' | Out-Null } catch { Write-Warning "Could not open browser automatically: $_" }
    Write-Host ''
    Write-Host 'Project is ready:'
    Write-Host '  Frontend: http://127.0.0.1:5173/'
    Write-Host '  Backend:  http://127.0.0.1:8080/api/health'
    Write-Host "  Logs:     $logDir"
    Write-Host 'Stop with:  .\scripts\stop-project.ps1'
}
catch {
    foreach ($processInfo in $startedProcesses) { Stop-StartedProcess ([int]$processInfo.pid) }
    if ($mysqlStartedByScript -and $mysqlService) {
        try { Stop-Service -Name $mysqlService.Name -ErrorAction Stop } catch { Write-Warning "Could not stop MySQL service '$($mysqlService.Name)' during startup cleanup." }
    }
    # Do NOT use Write-Error here: under $ErrorActionPreference = 'Stop' it becomes
    # a terminating error and would hide the real exception + stack trace below.
    Write-Host ''
    Write-Host 'Project startup failed:' -ForegroundColor Red
    Write-Host ("  Message : " + $_.Exception.Message) -ForegroundColor Red
    Write-Host ("  Type    : " + $_.Exception.GetType().FullName)
    Write-Host ("  ErrorId : " + $_.FullyQualifiedErrorId)
    Write-Host '  Stack   :'
    Write-Host ($_.ScriptStackTrace)
    Write-Host ("  Logs    : " + $logDir)
    exit 1
}
finally {
    foreach ($name in $oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
    }
}
