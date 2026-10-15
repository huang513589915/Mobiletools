param(
    [string]$MySqlServiceName,
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

function Wait-HttpReady([string]$Url, [int]$TimeoutSeconds, [int]$ProcessId, [string]$Label) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "$Label process exited. Check its log under $logDir."
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        }
        catch { }
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

    if (Test-Path -LiteralPath $manifestPath) {
        $existing = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
        $active = @($existing.processes | Where-Object { Get-Process -Id $_.pid -ErrorAction SilentlyContinue })
        if ($active.Count -gt 0) {
            throw "The project appears to be running already. Use scripts/stop-project.ps1 before starting another copy."
        }
        Remove-Item -LiteralPath $manifestPath -Force
    }

    $mysqlService = $null
    if ($MySqlServiceName) {
        $mysqlService = Get-Service -Name $MySqlServiceName -ErrorAction Stop
    }
    else {
        $mysqlCandidates = @(Get-Service -Name 'MySQL*' -ErrorAction SilentlyContinue)
        if ($mysqlCandidates.Count -eq 1) {
            $mysqlService = $mysqlCandidates[0]
        }
        elseif ($mysqlCandidates.Count -gt 1) {
            $runningMySql = @($mysqlCandidates | Where-Object Status -eq 'Running')
            if ($runningMySql.Count -eq 1) { $mysqlService = $runningMySql[0] }
        }
    }
    if ($mysqlService) {
        if ($mysqlService.Status -ne 'Running') {
            Write-Host "Starting MySQL service '$($mysqlService.Name)'..."
            Start-Service -Name $mysqlService.Name
            $mysqlService.WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
            $mysqlStartedByScript = $true
        }
    }

    $dbUrl = if ($env:DB_URL) { $env:DB_URL } else { 'jdbc:mysql://127.0.0.1:3306/arrears_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai' }
    $dbMatch = [regex]::Match($dbUrl, '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)')
    if (-not $dbMatch.Success) { throw 'DB_URL must use a jdbc:mysql://host:port/database URL.' }
    $dbHost = $dbMatch.Groups[1].Value
    $dbPort = if ($dbMatch.Groups[2].Success) { [int]$dbMatch.Groups[2].Value } else { 3306 }
    if (-not (Test-TcpPort $dbHost $dbPort)) {
        throw "MySQL is not reachable at ${dbHost}:$dbPort. Start MySQL, or pass -MySqlServiceName with its Windows service name."
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

    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backendLog = Join-Path $logDir "backend-$timestamp.out.log"
    $backendErrorLog = Join-Path $logDir "backend-$timestamp.err.log"
    $frontendLog = Join-Path $logDir "frontend-$timestamp.out.log"
    $frontendErrorLog = Join-Path $logDir "frontend-$timestamp.err.log"
    $powershellExe = Join-Path $PSHOME 'powershell.exe'
    $quotedBackend = Get-QuotedPath $backendDir
    $quotedFrontend = Get-QuotedPath $frontendDir
    $backendCommand = "Set-Location -LiteralPath $quotedBackend; mvn spring-boot:run"
    $frontendCommand = "Set-Location -LiteralPath $quotedFrontend; npm run dev -- --host 127.0.0.1 --strictPort"

    Write-Host 'Starting backend...'
    $backendProcess = Start-Process -FilePath $powershellExe -WorkingDirectory $backendDir `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $backendCommand) `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $backendLog -RedirectStandardError $backendErrorLog
    $backendProcess.Refresh()
    $startedProcesses.Add([pscustomobject]@{ name = 'backend'; pid = $backendProcess.Id; startedUtcTicks = $backendProcess.StartTime.ToUniversalTime().Ticks; command = 'mvn spring-boot:run'; log = $backendLog; errorLog = $backendErrorLog })
    Wait-HttpReady 'http://127.0.0.1:8080/api/health' $StartupTimeoutSeconds $backendProcess.Id 'Backend'

    Write-Host 'Starting frontend...'
    $frontendProcess = Start-Process -FilePath $powershellExe -WorkingDirectory $frontendDir `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $frontendCommand) `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendErrorLog
    $frontendProcess.Refresh()
    $startedProcesses.Add([pscustomobject]@{ name = 'frontend'; pid = $frontendProcess.Id; startedUtcTicks = $frontendProcess.StartTime.ToUniversalTime().Ticks; command = 'npm run dev'; log = $frontendLog; errorLog = $frontendErrorLog })
    Wait-HttpReady 'http://127.0.0.1:5173/' $StartupTimeoutSeconds $frontendProcess.Id 'Frontend'

    $manifest = [pscustomobject]@{
        repoRoot = $repoRoot
        startedAt = (Get-Date).ToString('o')
        processes = @($startedProcesses)
        mysqlService = if ($mysqlService) { $mysqlService.Name } else { $null }
        mysqlStartedByScript = $mysqlStartedByScript
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

    Start-Process 'http://127.0.0.1:5173/' | Out-Null
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
    Write-Error $_
    exit 1
}
finally {
    foreach ($name in $oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
    }
}
