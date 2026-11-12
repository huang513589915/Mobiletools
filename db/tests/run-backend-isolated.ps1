$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$base = 'C:\Program Files\MySQL\MySQL Server 8.4'
$mysqld = Join-Path $base 'bin\mysqld.exe'
$mysql = Join-Path $base 'bin\mysql.exe'
$mysqladmin = Join-Path $base 'bin\mysqladmin.exe'
$root = Join-Path $env:TEMP ('mobiletools-backend-test-' + [guid]::NewGuid().ToString('N'))
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$out = $root + '.out.log'
$err = $root + '.err.log'
$proc = $null
$previousDbUrl = $env:DB_URL
$previousDbUsername = $env:DB_USERNAME
$previousDbPassword = $env:DB_PASSWORD
$previousWorkbookDirectory = $env:LEDGER_WORKBOOK_DIRECTORY
$previousErasureIntegration = $env:LEDGER_ERASURE_MYSQL_TESTS
$testLog = Join-Path $root 'maven-test.log'

try {
  if (-not (Test-Path -LiteralPath $mysqld)) { throw "MySQL 8.4 is required for isolated integration tests: $mysqld" }
  New-Item -ItemType Directory -Path $root | Out-Null
  & $mysqld --no-defaults --initialize-insecure ('--basedir=' + $base) ('--datadir=' + $root) --console
  if ($LASTEXITCODE -ne 0) { throw 'isolated MySQL initialization failed' }
  $proc = Start-Process -FilePath $mysqld -ArgumentList @(
    '--no-defaults', ('"--basedir=' + $base + '"'), ('"--datadir=' + $root + '"'),
    ('--port=' + $port), ('"--plugin-dir=' + $base + '\lib\plugin"'),
    '--bind-address=127.0.0.1', '--mysqlx=OFF'
  ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    & $mysqladmin --no-defaults --host=127.0.0.1 "--port=$port" --user=root ping 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    if ($i -eq 39) { throw 'isolated MySQL did not become ready' }
  }

  $schemaPath = (Join-Path $repo 'backend/src/main/resources/schema.sql').Replace('\', '/')
  & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root "--execute=SOURCE $schemaPath"
  if ($LASTEXITCODE -ne 0) { throw 'base schema initialization failed' }
  foreach ($migration in @(
    'V1__enterprise_arrears_schema.sql',
    'V2__independent_excel_ledgers.sql',
    'V3__cancel_prechecked_ledger_import.sql',
    'V4__line_renewal_work_orders.sql',
    'V5__ledger_work_orders_and_follow_ups.sql',
    'V6__composed_ledger_datasets.sql',
    'V7__remove_unsupported_legacy_modules.sql',
    'V8__permanent_ledger_record_erasure.sql'
  )) {
    if ($migration -eq 'V7__remove_unsupported_legacy_modules.sql') {
      $fixturePath = (Join-Path $repo 'db/tests/V7_legacy_cleanup_fixture.sql').Replace('\', '/')
      & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root arrears_management "--execute=SOURCE $fixturePath"
      if ($LASTEXITCODE -ne 0) { throw 'V7 legacy cleanup fixture failed' }
    }
    $migrationPath = (Join-Path $repo (Join-Path 'db/migrations' $migration)).Replace('\', '/')
    & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root arrears_management "--execute=SOURCE $migrationPath"
    if ($LASTEXITCODE -ne 0) { throw "Migration failed: $migration" }
    if ($migration -eq 'V7__remove_unsupported_legacy_modules.sql') {
      $assertionPath = (Join-Path $repo 'db/tests/V7_legacy_cleanup_assertions.sql').Replace('\', '/')
      & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root arrears_management "--execute=SOURCE $assertionPath"
      if ($LASTEXITCODE -ne 0) { throw 'V7 legacy cleanup assertions failed' }
    }
    if ($migration -eq 'V8__permanent_ledger_record_erasure.sql') {
      $assertionPath = (Join-Path $repo 'db/tests/V8_erasure_schema.sql').Replace('\', '/')
      & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root arrears_management "--execute=SOURCE $assertionPath"
      if ($LASTEXITCODE -ne 0) { throw 'V8 erasure schema assertions failed' }
    }
  }

  $env:DB_URL = "jdbc:mysql://127.0.0.1:$port/arrears_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
  $env:DB_USERNAME = 'root'
  $env:DB_PASSWORD = ''
  $env:LEDGER_WORKBOOK_DIRECTORY = Join-Path $root 'original-workbooks'
  $env:LEDGER_ERASURE_MYSQL_TESTS = 'true'
  Push-Location (Join-Path $repo 'backend')
  try {
    & mvn test *> $testLog
    $testExitCode = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  Get-Content -LiteralPath $testLog -Tail 50 -Encoding UTF8
  if ($testExitCode -ne 0) { throw "Backend integration tests failed with exit code $testExitCode" }
  Write-Output 'PASS: backend unit and integration tests ran against a disposable MySQL database'
} catch {
  Write-Output ('FAIL: ' + $_.Exception.Message)
  if (Test-Path -LiteralPath $testLog) { Get-Content -LiteralPath $testLog -Tail 80 -Encoding UTF8 }
  if (Test-Path -LiteralPath $out) { Get-Content -LiteralPath $out -Tail 40 -Encoding UTF8 }
  if (Test-Path -LiteralPath $err) { Get-Content -LiteralPath $err -Tail 40 -Encoding UTF8 }
  throw
} finally {
  $env:DB_URL = $previousDbUrl
  $env:DB_USERNAME = $previousDbUsername
  $env:DB_PASSWORD = $previousDbPassword
  $env:LEDGER_WORKBOOK_DIRECTORY = $previousWorkbookDirectory
  $env:LEDGER_ERASURE_MYSQL_TESTS = $previousErasureIntegration
  if ($proc -and -not $proc.HasExited) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $mysqladmin --no-defaults --host=127.0.0.1 "--port=$port" --user=root shutdown 2>$null | Out-Null
    $ErrorActionPreference = $previousErrorAction
    $proc.WaitForExit(10000) | Out-Null
  }
  $tempRoot = [System.IO.Path]::GetFullPath($env:TEMP)
  $resolvedRoot = [System.IO.Path]::GetFullPath($root)
  if ($resolvedRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
      (Split-Path -Parent $resolvedRoot) -eq $tempRoot -and
      (Split-Path -Leaf $resolvedRoot).StartsWith('mobiletools-backend-test-')) {
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
  Remove-Item -LiteralPath $out,$err -Force -ErrorAction SilentlyContinue
}
