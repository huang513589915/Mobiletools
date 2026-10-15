# 政企客户欠费与到期管理工具

这是一个运行在个人电脑上的本地管理系统，用于维护政企客户、业务专线、欠费账单、到期事项、风险结果和跟进任务。

## 功能范围

- 本地账号登录、退出和 Session 鉴权
- 管理员、客户经理角色及客户数据范围控制
- 客户台账、业务专线、欠费账单和到期记录查询
- Excel 只读预检、导入预览、确认导入、批次追踪和异常核查
- 四条风险规则：余额低于 500 元、累计欠费高于 1000 元、距离到期不超过 30 天、任务超期超过 1 天
- 风险结果、自动督办任务、跟进记录和任务状态流转
- 管理员账号、客户范围、风险规则和操作审计维护

外部业务系统同步、短信/邮件/企业微信提醒、正式统计报表、Docker 和云部署当前未实现。

## 技术栈

- 后端：Java 17、Spring Boot 2.5.15、Spring JDBC、Spring Security、Maven
- 前端：React 18、TypeScript、Vite、Ant Design、TanStack Query、ECharts
- 数据库：MySQL
- Excel：Apache POI 5.2.5

## 环境要求

安装以下软件：

1. JDK 17，并确保 `java -version` 可用。
2. Maven 3.8 或更高版本，并确保 `mvn -version` 可用。
3. Node.js 18 或更高版本，并确保 `node -v` 和 `npm -v` 可用。
4. MySQL 8.x。项目使用本机 MySQL 数据库，不上传数据库文件或真实业务数据。

## 数据库初始化

先创建数据库并执行基础结构脚本。PowerShell 示例：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -p -e "CREATE DATABASE IF NOT EXISTS arrears_management CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Get-Content -Raw backend/src/main/resources/schema.sql | mysql -h 127.0.0.1 -P 3306 -u root -p
Get-Content -Raw db/migrations/V1__enterprise_arrears_schema.sql | mysql -h 127.0.0.1 -P 3306 -u root -p arrears_management
```

`schema.sql` 用于基础表，`V1__enterprise_arrears_schema.sql` 用于当前业务结构的增量补齐。迁移脚本不是 Flyway 自动迁移，执行前应备份现有数据库并检查目标库。不要在正式数据库上直接执行恢复或清空操作。

## 配置后端

后端配置文件为 `backend/src/main/resources/application.yml`。推荐通过环境变量提供数据库和初始化账号信息，不要写入源代码：

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/arrears_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "<你的MySQL密码>"
$env:APP_ADMIN_USERNAME = "admin"
$env:APP_ADMIN_PASSWORD = "<首次启动时设置的管理员密码>"
```

首次启动时，应用会在不存在时创建管理员账号以及预置客户经理账号。已有账号不会因重启而重置密码。不要在 README、日志或 Git 中记录真实密码。

## 启动后端

打开第一个终端：

```powershell
cd backend
mvn spring-boot:run
```

后端默认监听 `http://127.0.0.1:8080`。健康检查：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/api/health
```

## 一键启动和停止

也可以使用项目脚本快速管理本地进程。启动脚本不会自动执行数据库迁移、清库或 Excel 导入；MySQL 必须先运行，并且数据库结构需要按上面的步骤初始化。

```powershell
.\scripts\start-project.ps1
```

脚本会检查 Java、Maven、Node.js、npm 和 MySQL 端口，首次运行时安装前端依赖，并在 `.runtime/project/logs/` 保存启动日志。默认会打开 `http://127.0.0.1:5173/`。

如果本机 MySQL 是 Windows 服务，也可以让脚本尝试启动它：

```powershell
.\scripts\start-project.ps1 -MySqlServiceName MySQL84
```

结束项目：

```powershell
.\scripts\stop-project.ps1
```

停止脚本只结束启动脚本记录的后端和前端进程，不会停止原本已经运行的 MySQL。若启动脚本同时启动了 MySQL，可使用 `-StopMySql` 一并停止：

```powershell
.\scripts\stop-project.ps1 -StopMySql
```

## 启动前端

打开第二个终端：

```powershell
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

浏览器访问 `http://127.0.0.1:5173`。Vite 会将 `/api` 请求代理到 `http://127.0.0.1:8080`。

## 测试与构建

后端测试：

```powershell
cd backend
mvn test
```

前端构建：

```powershell
cd frontend
npm run build
```

完整浏览器导入验收需要先启动 MySQL、后端和前端，并使用独立测试数据。不要直接对正式业务数据运行会写入数据的验收脚本。

## Excel 导入

系统支持预检、预览和确认导入。确认导入前应检查工作表、表头、业务唯一标识、客户关联、金额和日期错误。客户关联失败的行只会进入批次异常/剔除记录，不会写入正式业务台账。重复导入、空值覆盖和批次追溯规则以 `docs` 中的导入契约和当前接口实现为准。

## 备份与恢复

执行迁移或批量导入前建议备份：

```powershell
mysqldump -h 127.0.0.1 -P 3306 -u root -p --single-transaction --routines --triggers arrears_management > arrears_management-backup.sql
```

恢复前必须确认备份文件和目标数据库，建议先恢复到临时数据库验证表结构、记录数和登录功能。不要把备份文件提交到 GitHub；项目已通过 `.gitignore` 排除 `.runtime/` 和本地备份目录。

## 项目结构

```text
backend/                         Spring Boot 后端
frontend/                        React + TypeScript 前端
db/migrations/                   数据库增量脚本
docs/                            历史需求、验收记录和技术归档
docs/project-technical-archive/  当前技术归档文档
openspec/                        OpenSpec 变更和功能规格
scripts/                         本地辅助脚本
```

## 文档入口

- `docs/project-technical-archive/project-technical-design.md`：技术设计与实现说明
- `docs/project-technical-archive/deployment-guide.md`：部署、启动、备份与排障
- `docs/project-technical-archive/database-design.md`：当前数据库结构说明
- `docs/project-technical-archive/api-reference.md`：后端接口清单
- `project.md`：项目开发约定
- `scripts/start-project.ps1`：快速启动本地项目
- `scripts/stop-project.ps1`：停止由启动脚本管理的进程

## 安全提醒

本项目面向本地运行，后端默认只监听回环地址。不要将服务端口直接暴露到互联网，不要提交数据库备份、Excel 原始数据、密码、令牌、Session 文件或 `.runtime` 目录。
