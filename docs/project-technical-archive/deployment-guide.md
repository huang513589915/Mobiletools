# 本地部署与运维手册

## 环境要求

需要 JDK 17、Maven、Node.js/npm 和 MySQL 8.x。项目不包含 MySQL 数据目录和真实业务数据，使用者需要自行初始化数据库。

## 数据库初始化

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -p -e "CREATE DATABASE IF NOT EXISTS arrears_management CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Get-Content -Raw backend/src/main/resources/schema.sql | mysql -h 127.0.0.1 -P 3306 -u root -p
Get-Content -Raw db/migrations/V1__enterprise_arrears_schema.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
```

当前项目未配置 Flyway，增量 SQL 需要人工评审、备份后执行。不要把测试数据库备份上传到公共仓库。

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

恢复前先确认目标库并在临时数据库验证备份，避免覆盖正式数据。项目默认不自动执行恢复和数据库迁移。

## 排障顺序

1. 检查 MySQL 服务和 3306 端口。
2. 检查 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。
3. 检查后端 `http://127.0.0.1:8080/api/health`。
4. 检查浏览器是否访问 `http://127.0.0.1:5173`，以及 Vite `/api` 代理。
5. 导入异常优先查询 `import_batch`、`import_row` 和 `exception_record`。

当前未提供 Docker、云部署、Windows 安装包、外部通知和进程守护配置。
