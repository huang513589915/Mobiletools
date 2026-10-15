# 政企客户欠费与到期管理工具

本项目是政企客户欠费、余额、专线到期、风险预警和任务督办管理工具。

## 项目结构

```text
Mobiletools/
├─ backend/                 # Spring Boot 模块化单体后端
├─ frontend/                # React + TypeScript + Vite 前端
├─ docs/                    # 需求、技术设计和项目文档
└─ README.md
```

## 技术栈

- 前端：React、TypeScript、Vite、Ant Design、TanStack Query、ECharts
- 后端：Java 17、Spring Boot 2.5.15、Maven
- 数据库：MySQL，默认数据库名 `arrears_management`
- 认证：Spring Security、本地数据库账号、服务端 Session

## 本地启动

### 前端

```powershell
cd frontend
npm install
npm run dev -- --host 127.0.0.1
```

前端默认地址：`http://127.0.0.1:5173`

### 后端

```powershell
cd backend
$env:APP_ADMIN_USERNAME = "admin"
$env:APP_ADMIN_PASSWORD = "<本地初始密码>"
mvn spring-boot:run
```

后端默认地址：`http://127.0.0.1:8080`

健康检查：`GET http://127.0.0.1:8080/api/health`

首次启动时，系统会在数据库不存在该账号时创建本地账号。当前本机已创建账号 `admin`；初始密码通过 PowerShell 环境变量提供，已经存在的账号不会在每次启动时自动重置密码。密码不要写入源代码、README 或 Git。

默认数据库连接为：

```text
地址：127.0.0.1:3306
数据库：arrears_management
用户名：root
密码：空（如本机 MySQL 已设置密码，请设置 DB_PASSWORD）
```

当前版本已实现本地登录、客户与业务台账、Excel 预检和正式导入、异常追溯、风险计算、自动督办任务、跟进记录、规则维护和管理员权限。短信、邮件、企业微信等外部通知，以及外部业务系统同步暂未接入。

## 数据库初始化、备份和恢复

数据库结构脚本位于 `db/migrations/V1__enterprise_arrears_schema.sql`。当前项目没有自动执行 Flyway，首次初始化或结构升级前应由管理员显式执行脚本，并先备份当前数据库。

```powershell
$mysql = 'C:/Program Files/MySQL/MySQL Server 8.4/bin'
$backup = "D:/code/Mobiletools/.runtime/mysql/backup/arrears_management-before-$(Get-Date -Format yyyyMMdd-HHmmss).sql"
& "$mysql/mysqldump.exe" --host=127.0.0.1 --port=3306 --user=root --single-transaction --routines --triggers --hex-blob arrears_management | Out-File -FilePath $backup -Encoding utf8
Get-Content -LiteralPath $backup -Tail 3
```

确认备份文件存在且末尾包含 `Dump completed on` 后，再执行兼容迁移：

```powershell
Get-Content db/migrations/V1__enterprise_arrears_schema.sql | & "$mysql/mysql.exe" --host=127.0.0.1 --port=3306 --user=root arrears_management
```

恢复必须在确认目标数据库和备份文件后执行。该操作会覆盖目标数据库中的同名表，禁止对当前正式库直接执行，先恢复到隔离数据库：

```powershell
& "$mysql/mysql.exe" --host=127.0.0.1 --port=3306 --user=root -e "CREATE DATABASE arrears_management_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Get-Content -Raw -LiteralPath $backup | & "$mysql/mysql.exe" --host=127.0.0.1 --port=3306 --user=root arrears_management_restore
```

恢复后检查表数量、关键表记录数、外键和登录接口。确认无误后删除临时恢复库：

```powershell
& "$mysql/mysql.exe" --host=127.0.0.1 --port=3306 --user=root -e "DROP DATABASE arrears_management_restore;"
```

当前已完成备份文件可读性检查、V1 迁移幂等验证和独立临时库恢复演练。正式数据库未被恢复演练覆盖。

## 故障排查

1. 检查 MySQL 服务和端口：`Get-NetTCPConnection -LocalPort 3306 -State Listen`。
2. 检查后端健康接口：`Invoke-WebRequest http://127.0.0.1:8080/api/health`。
3. 若出现“无法连接后端服务”，确认后端已在 `127.0.0.1:8080` 启动，前端代理配置为该地址。
4. 若登录失败，确认数据库可连接、账号启用且 `APP_ADMIN_PASSWORD` 仅用于首次创建账号，不会覆盖已有密码。
5. 若导入失败，先查看批次详情和异常行，不要重复导入相同文件；修正 Excel 后重新预检。
6. 发生数据库写入异常时，保留失败批次和日志，先备份现状，再决定是否恢复，不直接清空业务表。
