# 集团欠费与专线到期明细管理系统

这是一个面向政企客户经理的本地 Web 管理系统。管理员把每月整理好的两份真实 Excel 汇总表上传到系统，系统保存原始工作簿、生成可追溯的导入批次，并按权限展示欠费快照和专线到期快照。

当前版本围绕两套**互相独立**的数据建设：

- **欠费明细**：集团账户欠费明细月表，一行表示一个账户在某个统计月份的欠费快照。
- **专线到期明细**：专线到期需续签明细表，一行表示一个专线产品实例在某个统计日期的快照。

两套数据分开导入、分开存储、分开查询和分开授权。`集团编码`只是两张表中的展示和查询字段，不作为两套数据的业务关联键。

## 当前功能

- 管理员和客户经理登录，服务端 Session + CSRF 保护。
- 管理员维护客户经理账号；登录名默认使用姓名拼音，`real_name` 用于展示和与 Excel 姓名精确匹配。
- 管理员上传 Excel，先预检和预览，再确认或取消本次导入。
- 同一来源和统计期按业务唯一键合并多个批次：新增键加入、变化行更新、未变化和未出现在文件中的行保留；确认后每条记录都能追溯到最近一次更新它的批次。
- 只有相对当前数据没有新增或字段变化的上传才判为重复。文件 SHA-256 用于原件识别；曾经确认的文件若能改变当前数据，仍可进入差异预览。
- 预览展示新增、更新、未变化和保留数量，并列出字段前后值；上传表中存在但单元格为空的可选字段会清空旧值，整列缺失则保留旧值。
- 每次完整上传的原始工作簿都保存，管理员可以从批次详情下载；失败、重复和取消的上传尝试也保留追溯记录。
- Excel 客户经理姓名未找到账号或命中多个同名账号时，记录进入“归属匹配异常”，不会阻止其他有效数据导入。
- 管理员可以逐条或批量选择异常行分配客户经理；原始 Excel 姓名不变，只更新该行的当前有效归属。
- “欠费明细”页面默认筛选系统业务时区内的当前自然月；该月无数据时显示空结果，不回退到历史月份。用户可按月份范围查询历史。
- “专线到期明细”页面默认筛选系统业务时区内当前自然月的首日至月末；该范围无数据时显示空结果，不回退到历史日期。用户可按日期范围查询历史；剩余天数按 `计费截止日期 - 系统当前日期` 动态计算。
- 专线每个“统计日期 + 成员服务号码”对应一张续签工单，状态为待处理、跟进中、已续签或不续签；授权客户经理和管理员均可改状态，任意切换都会记录历史。
- 同一统计日期上传修正版时复用原续签工单并保留状态、处理人、处理时间和状态历史；修正版新增的专线默认为待处理。
- 专线页面支持按工单状态筛选；管理员可另外导出固定字段的续签工单报表，原有专线明细导出仍保留全部非空 Excel 字段。
- 详情抽屉按原 Excel 列顺序显示所有非空表头字段，并补充批次、工作表、原始行号、原始归属和当前有效归属。
- 导入批次页面显示各批次仍贡献到当前数据的行数和全部/部分/无贡献状态；欠费和专线详情标明本行最近一次新增或更新的来源批次、工作表与原始行号。
- 普通合并不删除文件未包含的记录；管理员可通过独立的永久删除流程清理所选记录，未选择删除原件时批次及工作簿继续保留供追溯。

当前版本不包含风险阈值、风险等级、自动停机判断、风险任务和通知等自动化流程；已移除旧客户台账、风险、旧导入及手工任务模块。“是否达到停机标准”、成员状态和合同到期日仅作为详情字段展示。欠费明细仍不生成续签工单。

## 业务规则速览

### 必填字段

| 数据来源 | 必填字段 |
|---|---|
| 欠费明细月表 | 日期、集团编码、集团名称、集团客户经理姓名、账户 ID、期末欠费金额 |
| 专线到期需续签明细表 | 统计日期、集团编码、集团名称、成员服务号码、客户经理姓名、计费截止日期 |

其他有表头字段可以为空，但有值时会原样保存。空表头列忽略；重复的非空表头按列位置分别保存，不互相覆盖。

### 快照唯一标识

- 欠费：`账户 ID + 日期`。账户 ID 跨月份保持不变，同一统计月份内不能重复。
- 专线：`成员服务号码 + 统计日期`。

必填字段缺失、日期或金额格式错误、批次内业务键重复会阻断确认，并定位到工作表和原始行号。归属匹配异常属于警告，可以在管理员确认后导入。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 18、TypeScript、Vite、Ant Design、TanStack Query |
| 后端 | Java 17、Spring Boot 2.5.15、Spring JDBC、Spring Security、Maven |
| 数据库 | MySQL 8.x |
| Excel 解析 | Apache POI 5.2.5 |
| 本地运行 | Windows PowerShell 启动脚本；默认只监听 `127.0.0.1` |

## 环境要求

- JDK 17，并能运行 `java -version`。
- Maven 3.8 或更高版本，并能运行 `mvn -version`。
- Node.js 18 或更高版本，并能运行 `node -v` 和 `npm -v`。
- MySQL 8.x，数据库名默认为 `arrears_management`。

项目是 Java + React 项目，后端依赖写在 [`backend/pom.xml`](backend/pom.xml)，前端依赖和锁定版本写在 [`frontend/package.json`](frontend/package.json) 与 [`frontend/package-lock.json`](frontend/package-lock.json)。当前不需要 Python `requirements.txt`；业务需求见 [`REQUIREMENTS.md`](REQUIREMENTS.md)。

## 数据库初始化

迁移脚本当前由人工评审和执行，项目没有启用 Flyway。执行前请先备份目标数据库，并确认连接的不是生产库。首次初始化可以在 MySQL 客户端或 PowerShell 中执行：

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

V1 建立共享账号与审计表，V2 至 V6 建立当前两套 Excel 台账、工单和合并版本结构。V7 删除已停止支持的旧业务表，保留当前台账、账号及审计历史；V8 增加永久清除统计和原始工作簿清理重试队列。升级前根据实际表结构确认版本：V1–V5 执行 V6、V7、V8；V1–V6 执行 V7、V8；V1–V7 执行 V8。执行破坏性迁移前必须备份并核对目标库。

管理员可以对所选欠费账户或专线实例执行不可撤销的永久删除，范围是同一来源、统计期和业务键下所有批次、版本、归属历史及工单记录；操作前会显示影响并要求输入确认词。删除原始工作簿为独立可选项，选择后会销毁被引用的整份文件并清除所有批次引用；共享文件中的其他已解析数据仍保留。MySQL 与文件系统不能组成同一事务，原件清理失败会显示待重试状态。该操作不会清理数据库外部备份；恢复较早备份可能重新带回已删除数据，必须把备份与文件副本作为整体检查和恢复。

## 配置

推荐通过环境变量配置，不要把密码写入源代码或 Git：

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/arrears_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "<MySQL 密码>"
$env:APP_ADMIN_USERNAME = "admin"
$env:APP_ADMIN_PASSWORD = "123456"
```

本地开发默认管理员账号为 `admin`，初始密码可按上面的变量设置为 `123456`。应用只在账号不存在时创建初始账号，重启不会覆盖已有密码；共享或部署到网络环境前请修改初始密码。原始工作簿默认保存到 `.runtime/ledger-original-workbooks`，可用 `LEDGER_WORKBOOK_DIRECTORY` 指定其他持久化目录。

## 启动

Windows 可直接双击根目录的 `start-project.bat`，或在 PowerShell 中运行：

```powershell
.\scripts\start-project.ps1
```

脚本会检查 Java、Maven、Node、npm 和 MySQL，启动后端 `http://127.0.0.1:8080` 与前端 `http://127.0.0.1:5173`，日志和进程清单写入被 Git 忽略的 `.runtime/project/`。停止项目：

```powershell
.\scripts\stop-project.ps1
```

Windows 用户也可以双击项目根目录下的 `stop-project.bat` 停止项目服务。

也可以手动启动：

```powershell
# 终端 1
cd backend
mvn spring-boot:run

# 终端 2
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

后端健康检查：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/api/health
```

## 验证

```powershell
.\db\tests\run-backend-isolated.ps1

cd frontend
npm ci
npm test
npm run build
```

后端隔离脚本会在系统临时目录创建一次性 MySQL 数据目录，依次执行当前 schema 和 V1–V8 迁移，并将数据库连接和原始工作簿目录限定在该临时实例。脚本结束后关闭实例并删除临时数据。不要直接对本地业务库运行 Spring 集成测试。浏览器验收前，先启动 MySQL、后端和前端，再使用专门的测试数据。

## GitHub 上传前检查

- 真实 Excel 工作簿放在本地 `excel/`，该目录已加入 `.gitignore`，不要上传客户数据。
- `.runtime/`、日志、构建产物、对话记录和本机工作记忆均不应提交。
- 不要提交数据库备份、密码、令牌、`.env`、证书或原始工作簿存储目录。
- 上传前检查：

  ```powershell
  git status --short
  git check-ignore -v excel\*.xlsx .runtime .env
  git diff --check
  ```

## 项目结构

```text
backend/                         Spring Boot 后端
frontend/                        React + Vite 前端
db/migrations/                   数据库初始化和增量迁移 SQL
db/tests/                        独立数据库约束检查
docs/independent-excel-ledgers/  当前业务设计和界面设计
docs/project-technical-archive/  历史技术归档和部署参考
openspec/specs/                  当前 OpenSpec 规格
scripts/                         PowerShell 启停脚本和本地工具
```

## 文档入口

- [给 AI 助手的项目导读与运行指南](AI_GUIDE.md)
- [当前需求说明](REQUIREMENTS.md)
- [项目设计](docs/independent-excel-ledgers/project-design.md)
- [界面整改与优化设计方案](docs/independent-excel-ledgers/界面整改与优化设计方案.md)
- [OpenSpec 当前规格](openspec/specs/)
- [本地部署与运维手册](docs/project-technical-archive/deployment-guide.md)
- [技术归档说明](docs/project-technical-archive/README.md)

## 许可证与数据说明

仓库当前未声明开源许可证。上传 GitHub 前请根据实际授权补充 `LICENSE`。本项目面向本地业务数据管理，仓库不应包含真实客户、账户、联系方式或欠费数据。
