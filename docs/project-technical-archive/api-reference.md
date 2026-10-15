# API 接口说明书

接口前缀为 `/api`。除健康检查、CSRF 和登录外，接口需要已认证 Session；管理员接口还需要 `ROLE_ADMIN`。成功响应统一使用 `ApiResponse` 的 `data`，异常由 `ApiExceptionHandler` 转换为错误码和消息。下面列出源码中实际存在的路由，字段详情以对应 Java record 和 SQL 为准。

## 认证与系统

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| GET | `/auth/csrf` | 匿名 | 获取 CSRF token |
| POST | `/auth/login` | 匿名 | 用户名密码登录并建立 Session |
| GET | `/auth/me` | 已登录 | 当前用户信息 |
| POST | `/auth/logout` | 已登录 | 失效 Session |
| GET | `/health` | 依配置 | 应用健康状态 |

登录请求为 `{username,password}`；密码不会写入日志。登录失败返回 HTTP 401。实际登录凭证通过环境配置或数据库初始化维护，本文不记录任何密码。

## 客户与台账

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/customers` | 客户列表 |
| GET | `/customers/page` | 客户服务端筛选、排序、分页 |
| GET | `/customers/{id}` | 客户详情及关联信息 |
| GET | `/ledger/services` | 业务列表 |
| GET | `/ledger/services/page` | 业务分页查询 |
| GET | `/ledger/services/{id}` | 业务详情 |
| GET | `/ledger/bills` | 欠费账单列表 |
| GET | `/ledger/expiries` | 到期记录列表 |
| GET | `/ledger/expiries/page` | 到期服务端筛选、排序、分页 |

客户经理查询自动加入其授权客户范围；管理员查询全量。分页参数和筛选参数以控制器 `@RequestParam` 定义为准，非法分页参数由统一异常处理。

## 导入

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| POST | `/imports/precheck-readonly` | 已登录 | 只读解析 Excel，不创建批次 |
| POST | `/imports/preview` | 管理员 | 解析并保存可确认预览 |
| POST | `/imports/confirm` | 管理员 | 确认批次并写入正式数据 |
| POST | `/imports/{batchId}/cancel` | 管理员 | 取消可取消批次 |
| GET | `/imports` | 已登录 | 批次列表 |
| GET | `/imports/{batchId}/rows` | 已登录 | 批次原始行及筛选 |
| GET | `/imports/{batchId}/preview` | 已登录 | 已保存预览 |
| GET | `/imports/{batchId}/exceptions` | 已登录 | 批次异常 |
| GET | `/imports/exceptions` | 已登录 | 异常列表及状态筛选 |

上传使用 multipart 的 `file` 字段。预览/确认流程记录文件摘要、工作表、原始行、业务键、分类和错误原因。客户关联失败只进入剔除/异常追踪，不进入正式账单或任务。

## 风险与工作台

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| GET | `/workbench/summary` | 已登录 | 工作台统计 |
| GET | `/workbench/items` | 已登录 | 风险、到期、异常和任务事项 |
| POST | `/risk-recalculation` | 管理员 | 执行风险重算 |
| GET | `/risk-recalculation/results` | 已登录 | 风险结果 |
| GET | `/risk-recalculation/runs` | 已登录 | 重算运行记录 |
| GET | `/risk-recalculation/{id}/task` | 已登录 | 风险结果关联任务 |
| GET | `/risk-rules` | 已登录 | 规则列表 |
| POST | `/risk-rules` | 管理员 | 更新规则并触发相应重算流程 |

当前规则编码为 R001-R004。规则结果和任务状态独立保存；自动任务的负责人、期限和持续风险去重逻辑以 `RiskRecalculationController` 及相关服务代码为准。

## 任务与跟进

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| GET | `/tasks` | 已登录 | 任务查询 |
| POST | `/tasks` | 已登录 | 新建任务 |
| GET | `/tasks/{id}` | 已登录 | 任务详情和跟进列表 |
| POST | `/tasks/{id}/start` | 已登录 | 开始处理 |
| POST | `/tasks/{id}/complete` | 已登录 | 完成任务 |
| POST | `/tasks/{id}/close` | 已登录 | 关闭任务 |
| POST | `/tasks/{id}/follow-ups` | 已登录 | 追加跟进记录 |

客户经理只能操作授权客户范围内任务；自动任务和人工任务通过来源字段区分，跟进记录追加保存。

## 管理与审计

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| GET | `/users` | 管理员 | 账号列表 |
| POST | `/users` | 管理员 | 创建账号 |
| PATCH | `/users/{id}` | 管理员 | 启用/停用账号 |
| GET | `/users/{id}/scope` | 管理员 | 查看客户范围 |
| PUT | `/users/{id}/scope` | 管理员 | 替换客户范围 |
| GET | `/audit-logs` | 管理员 | 分页审计查询 |

账号创建要求密码满足控制器中的长度校验，数据库只保存密码哈希。审计查询支持操作人、操作类型、目标、结果、时间范围和分页。
