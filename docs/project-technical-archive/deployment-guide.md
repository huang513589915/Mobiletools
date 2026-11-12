# 本地部署与运维手册

## 环境要求

需要 JDK 17、Maven、Node.js/npm 和 MySQL 8.x。项目不包含 MySQL 数据目录和真实业务数据，使用者需要自行初始化数据库。

## 数据库初始化

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -p -e "CREATE DATABASE IF NOT EXISTS arrears_management CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Get-Content -Raw backend/src/main/resources/schema.sql | mysql -h 127.0.0.1 -P 3306 -u root -p
Get-Content -Raw db/migrations/V1__enterprise_arrears_schema.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V2__independent_excel_ledgers.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V3__cancel_prechecked_ledger_import.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V4__line_renewal_work_orders.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V5__ledger_work_orders_and_follow_ups.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V6__composed_ledger_datasets.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V7__remove_unsupported_legacy_modules.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
Get-Content -Raw db/migrations/V8__permanent_ledger_record_erasure.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
```

全新数据库按 V1–V8 顺序初始化。已有数据库先确认当前迁移版本；V1–V5 后依次执行 V6–V8，V1–V6 后执行 V7–V8，V1–V7 后执行 V8。当前项目未配置 Flyway，增量 SQL 需要人工评审、备份后执行。V7 会删除旧模块表，执行前须核对旧数据无需保留；不要把测试数据库备份上传到公共仓库。

## 环境变量

| 变量 | 用途 |
|---|---|
| `DB_URL` | JDBC 连接地址 |
| `DB_USERNAME` | 数据库用户名 |
| `DB_PASSWORD` | 数据库密码 |
| `APP_ADMIN_USERNAME` | 首次初始化的管理员用户名 |
| `APP_ADMIN_PASSWORD` | 首次初始化的管理员密码 |

密码和令牌只通过本机环境变量或受保护的配置注入，不写入 Git。

## 启动

后端：

```powershell
cd backend
mvn spring-boot:run
```

前端：

```powershell
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

默认地址分别为 `http://127.0.0.1:8080` 和 `http://127.0.0.1:5173`。

## 快速启动与停止

项目提供 PowerShell 脚本，脚本不会自动执行迁移、清库或导入数据：

Windows 用户可以直接双击项目根目录下的 `start-project.bat` 启动，双击 `stop-project.bat` 停止。批处理窗口会保留，便于查看错误信息。

```powershell
.\scripts\start-project.ps1
```

启动脚本优先使用仓库 `.runtime\mysql\my.ini` 指向的本地 MySQL 实例；如果本机没有该实例，请先手动启动 MySQL 或安装 MySQL 8.x。脚本不会管理无关的 Windows 服务。

停止由脚本启动的前后端：

```powershell
.\scripts\stop-project.ps1
```

只有启动脚本代为启动 MySQL 时，才可以使用 `-StopMySql` 让停止脚本一并停止该服务。日志和 PID 清单写入已忽略的 `.runtime/project/` 目录。

## 测试

```powershell
cd backend
mvn test
cd ..\frontend
npm run build
```

## 备份与恢复

```powershell
mysqldump -h 127.0.0.1 -P 3306 -u root -p --single-transaction --routines --triggers arrears_management > arrears_management-backup.sql
```

管理员永久删除台账记录后，数据库和可选的原始工作簿物理文件会按所选范围清理；外部数据库备份、文件副本和离线存储不会自动清理。恢复删除前备份可能重新带回已删除记录。恢复前先在临时数据库和临时文件目录联合验证备份，再确认目标库，避免覆盖当前数据。项目默认不自动执行恢复和数据库迁移。

## 排障顺序

1. 检查 MySQL 服务和 3306 端口。
2. 检查 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。
3. 检查后端 `http://127.0.0.1:8080/api/health`。
4. 检查浏览器是否访问 `http://127.0.0.1:5173`，以及 Vite `/api` 代理。
5. 导入异常优先查询 `import_batch`、`import_row` 和 `exception_record`。

当前未提供 Docker、云部署、Windows 安装包、外部通知和进程守护配置。
