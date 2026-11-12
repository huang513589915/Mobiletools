# AI 项目导读与运行指南

> 这份文件是给阅读本项目压缩包的 AI 助手看的。它帮助 AI 快速了解项目、找到当前有效的需求和代码，并安全地指导用户在 Windows 上运行。它不代表用户已经授权 AI 导入真实数据、改动业务数据库或把文件上传到外部服务。

## 给 AI 助手的第一步

开始回答项目问题前，请先按顺序阅读：

1. `AI_GUIDE.md`：了解项目定位和协作边界。
2. `README.md`：了解项目介绍、依赖、配置和基本命令。
3. `REQUIREMENTS.md`：了解当前业务范围、字段、角色权限和验收基准。
4. `docs/independent-excel-ledgers/project-design.md`：了解真实 Excel 字段、导入和版本规则、数据模型及迁移边界。
5. `openspec/specs/` 下五份当前规格：`independent-data-ingestion`、`arrears-ledger`、`line-expiry-ledger`、`assignment-exception-management`、`manager-account-management`。
6. 运行相关文件：`backend/pom.xml`、`backend/src/main/resources/application.yml`、`frontend/package.json`、`frontend/vite.config.ts`、`scripts/start-project.ps1`、`scripts/stop-project.ps1`。

如果需要改代码，再阅读对应的前后端实现和测试。不要只凭旧归档文档或旧页面推断当前需求；先看当前需求和规格，再核对当前源码。历史技术资料位于 `docs/project-technical-archive/`，旧的客户台账、风险和任务方案只用于了解历史背景。

## 项目是什么

这是一个本地运行的政企客户数据管理 Web 系统。管理员上传两种汇总 Excel，系统保存原始工作簿和批次记录，并展示两套独立快照：

- 欠费明细月表：每行代表一个账户在某统计月份的欠费快照，业务键为 `账户 ID + 日期`。
- 专线到期明细表：每行代表一个专线实例在某统计日期的快照，业务键为 `成员服务号码 + 统计日期`。

两类记录独立导入、存储、查询和授权。`集团编码`是两张表各自的字段，不用来连接欠费与专线记录。管理员可以查看全部记录、导入批次和归属异常；客户经理只能查看当前有效归属为自己的记录。客户经理姓名来自 Excel，并与账号真实姓名精确匹配；无法唯一匹配时由管理员手动分配。

当前主要功能是上传预检、导入预览、确认/取消、重复文件识别、修正版差异、历史批次追溯、原始工作簿保存、两个明细页、归属匹配异常处理、账号管理和权限范围导出。

当前首版不包含风险等级、风险阈值、自动停机、自动督办或通知。旧客户台账、风险和手工任务 API、页面、测试及数据库表已从当前实现移除；`docs/project-technical-archive/` 和 OpenSpec 历史归档中的描述仅作历史记录。欠费工单和专线续签工单属于当前功能。

## 技术结构

```text
React 18 + TypeScript + Vite + Ant Design
                │ HTTP /api（Vite 本地代理）
                ▼
Java 17 + Spring Boot 2.5.15 + Spring JDBC + Spring Security
                │
                ▼
MySQL 8.x + 原始 Excel 工作簿文件存储
```

- 前端目录：`frontend/`，默认开发地址 `http://127.0.0.1:5173`。
- 后端目录：`backend/`，默认地址 `http://127.0.0.1:8080`。
- Vite 把 `/api` 请求代理到后端 `127.0.0.1:8080`。
- 默认数据库：`arrears_management`，默认 MySQL 地址为 `127.0.0.1:3306`。
- 默认原始工作簿保存目录：项目根目录 `.runtime/ledger-original-workbooks/`，可由 `LEDGER_WORKBOOK_DIRECTORY` 修改。
- 服务默认绑定 `127.0.0.1`，面向本机开发使用。

## 解压后的安全检查

压缩包可能是手工制作的，因此 AI 在运行前应先检查目录内容和 Git 忽略状态。`.gitignore` 只能防止 Git 跟踪，不能保证手动压缩时自动排除文件。

指导用户运行前：

- 检查压缩包是否包含真实 Excel、`.runtime/`、`.env`、数据库备份、日志、对话记录或凭据文件。
- 如包含真实业务工作簿或数据库备份，不要把它们发送给 AI、上传到 GitHub 或复制到测试目录；只使用确认过的脱敏样例。
- 不要在回复、命令输出或日志摘要中复述密码、访问令牌、客户账户和欠费明细。
- 不要自动删除压缩包里的文件；需要清理时先列出具体路径并说明用途。
- 如果用户的项目目录已有改动，保持原样；不要用 `git reset`、清库、强制覆盖或重新导入来“修复”启动问题。

## 环境要求

在 PowerShell 中检查依赖：

```powershell
java -version
mvn -version
node -v
npm -v
mysql --version
```

需要 JDK 17、Maven 3.8+、Node.js 18+、npm，以及本机 MySQL 8.x。`mysql --version` 仅用于确认命令行客户端是否可用；MySQL 服务还必须实际运行并监听 `127.0.0.1:3306`，或让 `DB_URL` 指向正确的 MySQL 实例。

如果用户缺少依赖，先指出缺少哪一个以及如何安装/启动。不要替用户安装服务、修改系统级环境或更改现有数据库地址，除非用户明确要求。

## 首次运行：先确认数据库

启动应用前先确认：

1. 用户知道 `DB_URL` 实际指向哪个 MySQL 主机、端口和数据库。
2. 数据库是新的本地开发库，或已经完成备份并确认可以迁移。
3. 不会把迁移脚本运行到包含真实业务数据的库上。

全新空库的初始化顺序为：创建数据库，然后依次执行 `backend/src/main/resources/schema.sql` 和 `db/migrations/V1__enterprise_arrears_schema.sql` 至 `V7__remove_unsupported_legacy_modules.sql`。示例：

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
```

这些 SQL 不是自动迁移工具。旧版本数据库升级前先确认连接目标并备份；此前已应用 V1 至 V5 的数据库依次执行 V6、V7，已应用 V1 至 V6 的数据库只执行 V7，不要重跑已完成的早期迁移。V7 会删除旧客户台账、旧导入、风险和任务表及其中数据，但保留当前 `excel_*` 台账、工单、账号和通用审计记录。

如果要运行自动化测试，必须先准备专用的测试数据库，例如 `arrears_management_test`，并确认有效 `DB_URL` 指向它。不要让测试连接用户的日常库或真实数据。

## 启动方式 A：Windows 快速启动

项目根目录提供：

- `start-project.bat`：启动前后端；脚本检查 Java/Maven/Node/npm 和 MySQL，并在需要时提示数据库密码与初始管理员密码。
- `stop-project.bat`：停止本项目启动的进程。

也可以在项目根目录 PowerShell 执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start-project.ps1
```

脚本优先尝试使用 `.runtime/mysql/my.ini` 中配置的本地 MySQL。如果压缩包不包含该本地运行环境，就需要提前启动已安装的 MySQL。脚本日志和进程信息位于 `.runtime/project/`。

如果 3306 已经由 MySQL 占用，脚本会尝试使用它，不会自行判断库内容是否正确；启动前仍要核对 `DB_URL` 和目标库。若端口被其他程序占用或健康检查失败，先停止并诊断，不要杀掉未知进程。

## 启动方式 B：手动启动前后端

先设置本次 PowerShell 会话中的配置。将占位符替换为用户自己的值，不要把真实密码写入文档或源码：

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/arrears_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "<本机 MySQL 密码>"
$env:APP_ADMIN_USERNAME = "admin"
$env:APP_ADMIN_PASSWORD = "<本地初始管理员密码>"
```

在第一个终端启动后端：

```powershell
Set-Location .\backend
mvn spring-boot:run
```

在第二个终端启动前端：

```powershell
Set-Location .\frontend
npm ci
npm run dev -- --host 127.0.0.1
```

首次执行 `npm ci` 会联网从 npm registry 下载依赖；失败时检查网络/代理，再重试，不要删除 `package-lock.json`。`mvn spring-boot:run` 也需要 Maven 仓库可访问，以下载尚未缓存的依赖。

访问：

- 前端页面：<http://127.0.0.1:5173>
- 后端健康检查：<http://127.0.0.1:8080/api/health>

初始管理员用户名默认是 `admin`。初始密码来自 `APP_ADMIN_PASSWORD`；如果没有显式设置，应用配置当前提供本地开发默认值 `123456`。此密码只用于新建账号，已有账号重启时不会重置。仅限本地开发，任何多人共享或网络部署前都应改成强密码。

停止手动启动的进程时，在各终端按 `Ctrl+C`。使用批处理脚本启动的进程可用根目录 `stop-project.bat` 或 `scripts/stop-project.ps1` 停止。

## 启动后检查

1. 确认后端控制台没有数据库连接或 SQL 错误。
2. 打开 `http://127.0.0.1:8080/api/health`，确认返回成功状态。
3. 打开 `http://127.0.0.1:5173`，确认登录页面出现。
4. 用初始管理员账号登录；如果登录失败，检查账号是否已存在、密码是否是首次创建时使用的值、数据库是否已经初始化。
5. 登录后确认“欠费明细”和“专线到期明细”页面能够正常载入。没有业务记录时，空列表是正常状态。
6. 只有在确认使用脱敏样例、测试数据库和本地文件目录后，才进行上传验收。

不要为了测试方便直接上传真实客户工作簿。系统会长期保存每次上传的原始字节，包括重复、失败和取消的上传尝试。

## 编译和测试

只想确认代码能编译时：

```powershell
Set-Location .\backend
mvn -DskipTests package

Set-Location ..\frontend
npm run build
```

运行完整后端测试前，先准备隔离测试数据库、设置其 `DB_URL` 并按项目说明初始化结构：

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/arrears_management_test?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "<测试数据库用户>"
$env:DB_PASSWORD = "<测试数据库密码>"
Set-Location .\backend
mvn test
```

某些 Spring 集成测试需要 MySQL；MySQL 不可用时，完整测试会因数据库连接失败，即使不涉及数据库的前端构建仍可能通过。查看 `backend/target/surefire-reports/` 的具体失败摘要时，不要把日志中可能包含的敏感配置公开。

## 常见问题排查

### 登录页显示“接口请求失败”或“后端不可用”

先访问后端健康检查地址，再查看后端终端日志。确认后端已启动、MySQL 可连接、数据库已初始化，且 Vite 使用 `frontend/vite.config.ts` 中指向的 `127.0.0.1:8080`。

### 后端无法连接数据库

核对 MySQL 服务是否运行、端口是否正确、数据库名和凭据是否正确，以及 `DB_URL` 是否在启动后端的同一个 PowerShell 会话中设置。不要在诊断输出中打印数据库密码。

### 后端报表或表不存在

检查是否在正确的开发库依序执行了建库脚本和 V1–V7。若升级现有库，先确认其迁移状态并备份；不要重跑 V1–V6，也不要运行隔离测试指向日常数据库。

### 登录密码与环境变量不一致

初始密码只在创建账号时使用。修改 `APP_ADMIN_PASSWORD` 不会重置已存在账号；不要建议通过删库重置。应使用当前账号管理流程或设计经过确认的密码重置方法。

### 端口被占用

检查 8080、5173 或 3306 的监听进程和用途。不要终止不属于本项目的进程；可以在用户确认后调整本地端口和 Vite 代理配置。

## AI 协作约定

- 先理解需求和现状，再按用户明确请求操作；“指导运行”不等同于授权清库、迁移正式库、导入真实工作簿、部署或推送 GitHub。
- 优先用项目已有脚本和锁文件；运行命令前确认当前工作目录是解压后的仓库根目录。
- 数据库操作前说明目标库并确认安全边界。遇到已有数据库、未知迁移状态或真实数据时先停下来，不做猜测性写入。
- 保持用户现有改动和本地文件，不用破坏性 Git 命令回滚。
- 报告真实执行结果，区分“编译通过”“前端构建通过”“集成测试通过”；不能把无法连接 MySQL说成代码测试通过。
- 不把本地地址或 `127.0.0.1` 自动改成公网监听地址。
- 不承诺云部署、Docker、外部系统同步、通知或风险任务，这些不属于当前首版。

## 可直接交给 AI 的提示词

```text
请先完整阅读项目根目录的 AI_GUIDE.md、README.md 和 REQUIREMENTS.md，再阅读 docs/independent-excel-ledgers/project-design.md 及 openspec/specs/ 下的当前规格。先告诉我你对项目用途、当前功能和运行依赖的理解，再检查我的电脑是否具备 JDK 17、Maven、Node.js/npm 和 MySQL。请按 AI_GUIDE.md 的步骤指导我在本机启动项目。任何数据库迁移、清空、真实 Excel 上传、密码重置、删除文件或外部发布操作都先停下来，说明目标和影响，等我明确授权后再执行。
```

