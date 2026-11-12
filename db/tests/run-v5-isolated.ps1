$ErrorActionPreference = 'Stop'
$base = 'C:\Program Files\MySQL\MySQL Server 8.4'
$mysqld = Join-Path $base 'bin\mysqld.exe'
$mysql = Join-Path $base 'bin\mysql.exe'
$mysqladmin = Join-Path $base 'bin\mysqladmin.exe'
$root = Join-Path $env:TEMP ('mobiletools-v5-' + [guid]::NewGuid().ToString('N'))
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$out = $root + '.out.log'
$err = $root + '.err.log'
$proc = $null
$bootstrap = Join-Path $root 'bootstrap.sql'
try {
  New-Item -ItemType Directory -Path $root | Out-Null
  & $mysqld --no-defaults --initialize-insecure ('--basedir=' + $base) ('--datadir=' + $root) --console
  if ($LASTEXITCODE -ne 0) { throw 'initialize failed' }
  $proc = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults', ('"--basedir=' + $base + '"'), ('"--datadir=' + $root + '"'), ('--port=' + $port), ('"--plugin-dir=' + $base + '\lib\plugin"'), '--bind-address=127.0.0.1', '--mysqlx=OFF') -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    & $mysqladmin --no-defaults "--host=127.0.0.1" "--port=$port" --user=root ping 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    if ($i -eq 39) { throw 'server not ready' }
  }
  $bootstrapSql = @'
CREATE DATABASE arrears_management_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE arrears_management_test;
CREATE TABLE app_user (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(100) NOT NULL, real_name VARCHAR(100) NOT NULL, password_hash VARCHAR(255) NOT NULL DEFAULT '', role VARCHAR(30) NOT NULL DEFAULT 'ADMIN', enabled BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(id), UNIQUE KEY uk_test_user(username)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE excel_import_batch (id BIGINT NOT NULL AUTO_INCREMENT, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_test_batch(id, source_type, statistics_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE excel_current_version (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, batch_id BIGINT NOT NULL, changed_by_user_id BIGINT NOT NULL, PRIMARY KEY(source_type, statistics_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE excel_snapshot_record (id BIGINT NOT NULL AUTO_INCREMENT, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, effective_manager_user_id BIGINT NULL, PRIMARY KEY(id), UNIQUE KEY uk_test_snapshot(id,batch_id,source_type)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT NOT NULL, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, account_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, PRIMARY KEY(snapshot_record_id), CONSTRAINT fk_test_arrears_snapshot FOREIGN KEY(snapshot_record_id,batch_id,source_type) REFERENCES excel_snapshot_record(id,batch_id,source_type)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT NOT NULL, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, member_service_number VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, PRIMARY KEY(snapshot_record_id), CONSTRAINT fk_test_line_snapshot FOREIGN KEY(snapshot_record_id,batch_id,source_type) REFERENCES excel_snapshot_record(id,batch_id,source_type)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE line_renewal_work_order (id BIGINT NOT NULL AUTO_INCREMENT, statistics_key VARCHAR(8) NOT NULL, member_service_number VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, current_snapshot_record_id BIGINT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', last_handled_by_user_id BIGINT NULL, last_handled_at DATETIME NULL, PRIMARY KEY(id), UNIQUE KEY uk_test_line_identity(statistics_key,member_service_number), UNIQUE KEY uk_test_line_snapshot(current_snapshot_record_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE line_renewal_status_history (id BIGINT NOT NULL AUTO_INCREMENT, work_order_id BIGINT NOT NULL, previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO app_user(username,real_name) VALUES ('test-admin','isolated-admin');
INSERT INTO excel_import_batch(source_type,statistics_key) VALUES ('ARREARS','202609');
INSERT INTO excel_current_version(source_type,statistics_key,batch_id,changed_by_user_id) VALUES ('ARREARS','202609',1,1);
INSERT INTO excel_snapshot_record(id,batch_id,source_type,effective_manager_user_id) VALUES (1,1,'ARREARS',1);
INSERT INTO arrears_snapshot(snapshot_record_id,batch_id,source_type,account_id) VALUES (1,1,'ARREARS','A-1');
'@
  [System.IO.File]::WriteAllText($bootstrap, $bootstrapSql, [System.Text.UTF8Encoding]::new($false))
  & $mysql --no-defaults --default-character-set=utf8mb4 "--host=127.0.0.1" "--port=$port" --user=root --execute="SOURCE $bootstrap"
  if ($LASTEXITCODE -ne 0) { throw 'bootstrap failed' }
  & $mysql --no-defaults --default-character-set=utf8mb4 "--host=127.0.0.1" "--port=$port" --user=root arrears_management_test --execute="SOURCE D:/code/Mobiletools/db/migrations/V5__ledger_work_orders_and_follow_ups.sql"
  if ($LASTEXITCODE -ne 0) { throw 'migration failed' }
  & $mysql --no-defaults --default-character-set=utf8mb4 "--host=127.0.0.1" "--port=$port" --user=root arrears_management_test --execute="SOURCE D:/code/Mobiletools/db/tests/V5_ledger_work_orders_constraints.sql"
  if ($LASTEXITCODE -ne 0) { throw 'verification failed' }
  Write-Output 'PASS: V5 isolated MySQL migration and constraints'
} catch {
  Write-Output ('FAIL: ' + $_.Exception.Message)
  if (Test-Path -LiteralPath $bootstrap) { Get-Content -LiteralPath $bootstrap -Encoding UTF8 }
  if (Test-Path -LiteralPath $out) { Get-Content -LiteralPath $out -Tail 40 }
  if (Test-Path -LiteralPath $err) { Get-Content -LiteralPath $err -Tail 40 }
  throw
}
finally {
  if ($proc -and -not $proc.HasExited) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $mysqladmin --no-defaults "--host=127.0.0.1" "--port=$port" --user=root shutdown 2>$null | Out-Null
    $ErrorActionPreference = $previousErrorAction
    $proc.WaitForExit(10000) | Out-Null
  }
  $tempRoot = [System.IO.Path]::GetFullPath($env:TEMP)
  $resolvedRoot = [System.IO.Path]::GetFullPath($root)
  if ($resolvedRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
      (Split-Path -Parent $resolvedRoot) -eq $tempRoot -and
      (Split-Path -Leaf $resolvedRoot).StartsWith('mobiletools-v5-')) {
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
  Remove-Item -LiteralPath $out,$err -Force -ErrorAction SilentlyContinue
}
