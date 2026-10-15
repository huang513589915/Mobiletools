# 项目事实基线

## 1. 文档属性

本文件记录截至 2026-10-15 对 `D:\code\Mobiletools` 的核实结果。内容以当前源码、构建配置、迁移脚本、测试代码和本地数据库只读查询为依据；历史需求文件仅用于识别差异。

状态含义：已核实、部分实现、未发现实现、尚未验证、待确认。

## 2. 已核实技术事实

| 项目 | 事实 |
|---|---|
| 后端 | Java 17、Spring Boot 2.5.15、Maven |
| 后端访问 | Spring JDBC `JdbcTemplate`，未发现 MyBatis 或 JPA |
| 安全 | Spring Security、服务端 Session、CSRF |
| 数据库 | MySQL，本机 `arrears_management`；具体服务发行版版本未在本次归档中确认 |
| Excel | Apache POI 5.2.5，后端直接解析上传文件 |
| 前端 | React 18、TypeScript、Vite |
| UI | Ant Design 5 |
| 数据请求 | TanStack Query 5；图表使用 ECharts |
| 运行绑定 | 后端默认 `127.0.0.1:8080`；前端由 Vite 提供开发服务 |
| 数据库迁移 | 项目内有 `db/migrations/V1__enterprise_arrears_schema.sql`，当前未发现 Flyway 自动执行配置 |

## 3. 数据库事实

本次只读查询确认 15 张表：`app_user`、`customer`、`business_service`、`arrears_bill`、`contract_expiry`、`task`、`follow_up`、`import_batch`、`import_row`、`risk_rule`、`risk_result`、`risk_recalculation_run`、`exception_record`、`operation_audit_log`、`user_customer_scope`。

查询记录数为：用户 4、客户 7、业务 3、欠费账单 3、到期记录 3、任务 11、跟进 1、导入批次 4、导入行 11、风险规则 4、风险结果 25、重算运行 5、异常 15、审计日志 338、客户范围 3。上述数字是归档时快照，不是永久约束。

已核实的核心唯一键包括：

- `customer.uk_customer_code`
- `business_service.uk_service_customer_code`
- `arrears_bill.uk_bill_customer_code_period`
- `risk_rule.uk_rule_code_version`
- `risk_result.uk_risk_source_rule_run`
- `task.uk_task_import_record`
- `user_customer_scope.uk_scope_user_customer`

外键通过 `information_schema.KEY_COLUMN_USAGE` 只读查询确认，完整清单见 [database-design.md](database-design.md)。

四条风险规则当前为：余额 `< 500`、累计欠费 `> 1000`、距离到期 `<= 30` 天、任务超期 `> 1` 天。规则名称已在当前数据库修复为可读中文；迁移脚本中仍存在历史编码异常文字，详见已知问题。

## 4. 已核实功能与验证

- 登录、退出、Session、CSRF、管理员与客户经理角色：源码存在，集成测试覆盖。
- 客户范围限制：客户查询、业务查询、风险和任务查询包含范围条件，管理员可维护范围。
- Excel 只读预检、预览、确认、取消、批次、行级追踪和异常记录：源码和测试存在。
- 客户、业务、账单、到期、任务、跟进、风险工作台和管理员页面：前端源码存在并调用后端接口。
- 风险结果持久化、自动任务生成、任务幂等和风险到任务跳转：源码与风险/工作台测试存在。
- Maven 测试：60 项通过，1 项跳过。
- 前端构建：已通过。
- 浏览器验收：导航、工作台、自动风险和完整导入脚本已通过。

## 5. 明确未发现或尚未实现

- 外部业务系统同步。
- 短信、邮件、企业微信等外部提醒发送。
- 正式统计报表模块和独立导出能力。
- 对外开放 API 的调用方认证体系。
- Flyway 自动迁移、Docker、云部署和安装包。
- 复杂 RBAC、部门层级权限和多租户。

## 6. 不允许从事实基线推断的事项

MySQL 发行版精确版本、生产部署方式、外部系统接口、正式提醒渠道、未来用户组织结构和真实 Excel 长期契约仍待确认。不得将测试账号的初始化配置或示例数据写入对外文档。
