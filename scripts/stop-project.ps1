param(
    [switch]$StopMySql
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $repoRoot '.runtime\project'
$manifestPath = Join-Path $runtimeDir 'processes.json'

function Wait-ProcessExited([int]$ProcessId, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue))
}

function Stop-ManagedProcess($ProcessInfo) {
    $process = Get-Process -Id ([int]$ProcessInfo.pid) -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "$($ProcessInfo.name): already stopped"
        return $true
    }

    $expectedStart = [DateTime]::new([long]$ProcessInfo.startedUtcTicks, [DateTimeKind]::Utc).ToLocalTime()
    $actualStart = $process.StartTime
    if ([Math]::Abs(($actualStart - $expectedStart).TotalSeconds) -gt 3) {
        Write-Warning "$($ProcessInfo.name): PID $($ProcessInfo.pid) was reused by another process; it was not stopped."
        return $false
    }

    & taskkill.exe /PID ([int]$ProcessInfo.pid) /T /F | Out-Null
    # taskkill returns before the kernel finishes tearing the process down, so an
    # immediate Get-Process check races with termination and reports a false
    # failure (exactly what produced the "could not be fully stopped" warnings).
    # Poll until the process is really gone, up to 15 seconds.
    if (-not (Wait-ProcessExited ([int]$ProcessInfo.pid) 15)) {
        Write-Warning "$($ProcessInfo.name): process tree could not be fully stopped."
        return $false
    }
    Write-Host "$($ProcessInfo.name): stopped"
    return $true
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    Write-Host 'No managed project processes were found.'
    # Fallback cleanup: kill any process still bound to the project ports
    # (leftovers from a previous run that failed before writing the manifest).
    foreach ($port in @(3306, 8080, 5173)) {
        try {
            $conns = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
            foreach ($conn in $conns) {
                $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
                if ($proc) {
                    Write-Host "Cleaning up orphan on port $port : $($proc.Name) (PID $($proc.Id))"
                    & taskkill.exe /PID $proc.Id /T /F | Out-Null
                }
            }
        }
        catch { Write-Warning "Could not inspect port $port : $_" }
    }
    exit 0
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$allStopped = $true
# Stop application processes first, then MySQL last, for a cleaner shutdown.
$nonMySql = @($manifest.processes | Where-Object { $_.name -ne 'mysql' })
$mySql = @($manifest.processes | Where-Object { $_.name -eq 'mysql' })
foreach ($processInfo in ($nonMySql + $mySql)) {
    if (-not (Stop-ManagedProcess $processInfo)) { $allStopped = $false }
}

if ($StopMySql -and $manifest.mysqlStartedByScript -and $manifest.mysqlService) {
    $service = Get-Service -Name $manifest.mysqlService -ErrorAction SilentlyContinue
    if ($service -and $service.Status -eq 'Running') {
        Stop-Service -Name $manifest.mysqlService
        Write-Host "MySQL service '$($manifest.mysqlService)': stopped"
    }
}

if ($allStopped) {
    Remove-Item -LiteralPath $manifestPath -Force
}
else {
    Write-Warning 'Some processes were left untouched. Review the PID manifest before starting again.'
    exit 1
}
Write-Host 'Project processes have been stopped.'
