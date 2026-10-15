param(
    [switch]$StopMySql
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $repoRoot '.runtime\project'
$manifestPath = Join-Path $runtimeDir 'processes.json'

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
    if (Get-Process -Id ([int]$ProcessInfo.pid) -ErrorAction SilentlyContinue) {
        Write-Warning "$($ProcessInfo.name): process tree could not be fully stopped."
        return $false
    }
    Write-Host "$($ProcessInfo.name): stopped"
    return $true
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    Write-Host 'No managed project processes were found.'
    exit 0
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$allStopped = $true
foreach ($processInfo in @($manifest.processes)) {
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
