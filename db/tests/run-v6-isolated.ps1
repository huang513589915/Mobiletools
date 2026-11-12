$ErrorActionPreference = 'Stop'
$base = 'C:\Program Files\MySQL\MySQL Server 8.4'
$mysqld = Join-Path $base 'bin\mysqld.exe'
$mysql = Join-Path $base 'bin\mysql.exe'
$mysqladmin = Join-Path $base 'bin\mysqladmin.exe'
$root = Join-Path $env:TEMP ('mobiletools-v6-' + [guid]::NewGuid().ToString('N'))
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$out = $root + '.out.log'
$err = $root + '.err.log'
$proc = $null
$bootstrap = Join-Path $root 'bootstrap.sql'
try {
  if (-not (Test-Path -LiteralPath $mysqld)) { throw "Isolated MySQL not found: $mysqld" }
  New-Item -ItemType Directory -Path $root | Out-Null
  & $mysqld --no-defaults --initialize-insecure ('--basedir=' + $base) ('--datadir=' + $root) --console
  if ($LASTEXITCODE -ne 0) { throw 'initialize failed' }
  $proc = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults', ('"--basedir=' + $base + '"'), ('"--datadir=' + $root + '"'), ('--port=' + $port), ('"--plugin-dir=' + $base + '\lib\plugin"'), '--bind-address=127.0.0.1', '--mysqlx=OFF') -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    & $mysqladmin --no-defaults --host=127.0.0.1 "--port=$port" --user=root ping 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    if ($i -eq 39) { throw 'server not ready' }
  }
  $bootstrapSql = @'
CREATE DATABASE arrears_management_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE arrears_management_test;
CREATE TABLE app_user (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(100) NOT NULL, real_name VARCHAR(100) NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB;
CREATE TABLE excel_import_batch (
 id BIGINT NOT NULL AUTO_INCREMENT, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NULL,
 uploaded_by_user_id BIGINT NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_excel_batch_composite_ref(id,source_type,statistics_key)
) ENGINE=InnoDB;
CREATE TABLE excel_current_version (
 source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, batch_id BIGINT NOT NULL,
 changed_by_user_id BIGINT NOT NULL, changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(source_type,statistics_key)
) ENGINE=InnoDB;
CREATE TABLE excel_revision_diff (
 id BIGINT NOT NULL AUTO_INCREMENT, batch_id BIGINT NOT NULL, identity_key VARCHAR(255) NOT NULL,
 change_type VARCHAR(20) NOT NULL, previous_import_row_id BIGINT NULL, candidate_import_row_id BIGINT NULL,
 PRIMARY KEY(id), CONSTRAINT chk_excel_revision_change_type CHECK(change_type IN ('ADDED','REMOVED','CHANGED','UNCHANGED'))
) ENGINE=InnoDB;
CREATE TABLE excel_snapshot_record (
 id BIGINT NOT NULL AUTO_INCREMENT, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL,
 import_row_id BIGINT NOT NULL, effective_manager_user_id BIGINT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_snapshot_id_batch_source(id,batch_id,source_type)
) ENGINE=InnoDB;
CREATE TABLE excel_import_row (
 id BIGINT NOT NULL AUTO_INCREMENT, batch_id BIGINT NOT NULL, sheet_name VARCHAR(100) NOT NULL,
 source_row_number INT NOT NULL, identity_key VARCHAR(255) NULL, validation_status VARCHAR(20) NOT NULL,
 raw_fields JSON NOT NULL, normalized_fields JSON NULL, PRIMARY KEY(id)
) ENGINE=InnoDB;
INSERT INTO app_user(username,real_name) VALUES ('admin','admin');
INSERT INTO app_user(username,real_name) VALUES ('manager','test-manager');
INSERT INTO excel_import_batch(id,source_type,statistics_key,uploaded_by_user_id) VALUES
 (1,'ARREARS','202610',1),(2,'ARREARS','202610',1),(3,'LINE_EXPIRY','20261101',1);
INSERT INTO excel_current_version(source_type,statistics_key,batch_id,changed_by_user_id) VALUES
 ('ARREARS','202610',2,1),('LINE_EXPIRY','20261101',3,1);
INSERT INTO excel_import_row(id,batch_id,sheet_name,source_row_number,identity_key,validation_status,raw_fields,normalized_fields) VALUES
 (9,1,'arrears',2,'202610:A-OLD','VALID',JSON_ARRAY(JSON_OBJECT('header','account','value','A-OLD')),JSON_OBJECT('account','A-OLD')),
 (10,2,'arrears',2,'202610:A-1','VALID',JSON_ARRAY(JSON_OBJECT('header','account','value','A-1')),JSON_OBJECT('account','A-1')),
 (11,3,'line',2,'20261101:S-1','VALID',JSON_ARRAY(JSON_OBJECT('header','service','value','S-1')),JSON_OBJECT('service','S-1'));
INSERT INTO excel_snapshot_record(id,batch_id,source_type,import_row_id) VALUES
 (9,1,'ARREARS',9),(10,2,'ARREARS',10),(11,3,'LINE_EXPIRY',11);
UPDATE excel_snapshot_record SET effective_manager_user_id=2 WHERE id IN (10,11);
CREATE TABLE line_renewal_work_order (
 id BIGINT NOT NULL AUTO_INCREMENT, statistics_key VARCHAR(8) NOT NULL,
 member_service_number VARCHAR(100) NOT NULL, current_snapshot_record_id BIGINT NULL,
 status VARCHAR(20) NOT NULL, last_handled_by_user_id BIGINT NULL, last_handled_at DATETIME NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_test_work_order_period_identity(statistics_key,member_service_number),
 UNIQUE KEY uk_test_work_order_snapshot(current_snapshot_record_id)
) ENGINE=InnoDB;
CREATE TABLE line_renewal_status_history (
 id BIGINT NOT NULL AUTO_INCREMENT, work_order_id BIGINT NOT NULL,
 previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL,
 changed_by_user_id BIGINT NOT NULL, changed_at DATETIME NOT NULL,
 PRIMARY KEY(id)
) ENGINE=InnoDB;
INSERT INTO line_renewal_work_order(id,statistics_key,member_service_number,current_snapshot_record_id,status,
    last_handled_by_user_id,last_handled_at)
VALUES (1,'20261101','S-1',11,CONVERT(0xE8B79FE8BF9BE4B8AD USING utf8mb4),2,'2026-10-31 16:45:00');
INSERT INTO line_renewal_status_history(id,work_order_id,previous_status,new_status,changed_by_user_id,changed_at)
VALUES (1,1,CONVERT(0xE5BE85E5A484E79086 USING utf8mb4),CONVERT(0xE8B79FE8BF9BE4B8AD USING utf8mb4),2,'2026-10-31 16:45:00');
'@
  [System.IO.File]::WriteAllText($bootstrap, $bootstrapSql, [System.Text.UTF8Encoding]::new($false))
  & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root --execute="SOURCE $bootstrap"
  if ($LASTEXITCODE -ne 0) { throw 'bootstrap failed' }
  & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root arrears_management_test --execute="SOURCE D:/code/Mobiletools/db/migrations/V6__composed_ledger_datasets.sql"
  if ($LASTEXITCODE -ne 0) { throw 'V6 migration failed' }
  & $mysql --no-defaults --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$port" --user=root arrears_management_test --execute="SOURCE D:/code/Mobiletools/db/tests/V6_composed_dataset_constraints.sql"
  if ($LASTEXITCODE -ne 0) { throw 'V6 constraints failed' }
  Write-Output 'PASS: V6 isolated MySQL migration, backfill, membership, and constraints'
} catch {
  Write-Output ('FAIL: ' + $_.Exception.Message)
  if (Test-Path -LiteralPath $bootstrap) { Get-Content -LiteralPath $bootstrap -Encoding UTF8 }
  if (Test-Path -LiteralPath $out) { Get-Content -LiteralPath $out -Tail 40 }
  if (Test-Path -LiteralPath $err) { Get-Content -LiteralPath $err -Tail 40 }
  throw
} finally {
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
      (Split-Path -Leaf $resolvedRoot).StartsWith('mobiletools-v6-')) {
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
  Remove-Item -LiteralPath $out,$err -Force -ErrorAction SilentlyContinue
}
