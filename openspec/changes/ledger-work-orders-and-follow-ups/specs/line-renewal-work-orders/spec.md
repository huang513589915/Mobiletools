# Spec Delta

## MODIFIED Requirements

### Requirement: Preserve work-order handling across revisions
系统 MUST 在同一统计日期的修正版确认后保留已有工单的当前状态、最后处理人、最后处理时间、状态变更历史和全部跟进说明；修正版新增的专线 MUST 创建为“待处理”。

#### Scenario: Revised row keeps handling data
- **WHEN** 修正版包含已有统计日期和成员服务号码，但 Excel 字段发生变化
- **THEN** 系统更新当前专线快照关联，但保留原工单的状态、最后处理信息、全部状态历史和跟进说明

#### Scenario: Revised row is newly added
- **WHEN** 修正版包含上一版本不存在的统计日期和成员服务号码
- **THEN** 系统创建一条新的“待处理”工单，且其最后处理人和最后处理时间为空

#### Scenario: Revised row is removed
- **WHEN** 修正版不再包含上一版本的某个成员服务号码
- **THEN** 该专线和工单不出现在当前版本查询结果中，但历史工单、状态历史和跟进说明仍可追溯

#### Scenario: Removed row reappears in a later revision
- **WHEN** 后续同日期修正版重新包含曾被移除的成员服务号码
- **THEN** 系统复用原工单和全部处理记录，并更新其当前快照关联

### Requirement: Allow authorized status updates
系统 MUST 支持“待处理”“跟进中”“已续签”“不续签”四种状态，并允许有权限的操作人将当前状态切换为任意一种状态；用户可在目标状态为“跟进中”时附带可选说明。

#### Scenario: Manager updates an assigned work order
- **WHEN** 客户经理更新当前有效归属为自己的专线工单状态
- **THEN** 系统保存新状态，并将该客户经理记录为最后处理人

#### Scenario: Manager accesses another manager's work order
- **WHEN** 客户经理尝试查看或更新当前有效归属不是自己的工单
- **THEN** 系统拒绝该请求且不改变工单状态或处理记录

#### Scenario: Administrator updates any work order
- **WHEN** 管理员查看或更新任意专线工单
- **THEN** 系统允许该操作并保存管理员为最后处理人

#### Scenario: Add follow-up without changing follow-up status
- **WHEN** 工单当前为“跟进中”，有权限的用户保持该状态并填写说明
- **THEN** 系统追加跟进说明并更新最后处理人和时间，不新增状态变化记录

#### Scenario: Submit unchanged status without a follow-up
- **WHEN** 用户提交与当前状态相同的状态且未填写跟进说明
- **THEN** 系统不新增任何处理记录，也不更新最后处理人和时间

### Requirement: Record status change history
系统 MUST 记录每次实际发生的状态变化，包括原状态、新状态、操作人和操作时间，并向有权查看该工单的用户提供状态变化与跟进说明按时间合并排序的处理时间线；提交与当前状态相同的状态 MUST NOT 新增状态变化历史。

#### Scenario: Record a changed status
- **WHEN** 工单从一个状态切换到另一个不同状态
- **THEN** 系统追加一条状态历史，并更新工单的最后处理人和最后处理时间

#### Scenario: Ignore a no-op status update
- **WHEN** 操作人提交与当前状态相同的状态且未追加跟进说明
- **THEN** 系统保持工单数据不变且不追加状态变化历史

#### Scenario: Change a terminal-looking status back
- **WHEN** 工单从“已续签”或“不续签”切换到任意其他状态
- **THEN** 系统允许切换并追加对应状态历史

#### Scenario: View status and follow-up timeline
- **WHEN** 管理员或当前有效归属客户经理查看工单详情
- **THEN** 系统按时间顺序展示全部状态变化和跟进说明，标明记录类型、操作人和时间，并对相同时间的记录采用稳定排序

#### Scenario: View status history
- **WHEN** 管理员或当前有效归属客户经理查看工单详情
- **THEN** 系统展示该工单全部实际状态变化，包含原状态、新状态、操作人和操作时间，并按时间顺序排列

### Requirement: Export administrator renewal work-order report
系统 MUST 允许管理员按当前统计日期范围、工单状态及已有专线筛选条件导出全部匹配工单，不受分页限制；导出 MUST 使用固定字段顺序且不包含跟进说明或状态历史。

#### Scenario: Export filtered work orders
- **WHEN** 管理员选择统计日期范围、工单状态和其他专线筛选条件后导出
- **THEN** 系统导出所有匹配且管理员可见的工单

#### Scenario: Export columns
- **WHEN** 管理员下载续签工单报表
- **THEN** 文件按顺序包含统计日期、集团编码、集团名称、产品编码、产品名称、成员服务号码、Excel 原始客户经理、当前有效客户经理、计费截止日期、状态、最后处理人和最后处理时间

#### Scenario: Manager attempts administrator report export
- **WHEN** 客户经理请求续签工单管理员报表
- **THEN** 系统拒绝请求

#### Scenario: Renewal report excludes follow-up details
- **WHEN** 管理员导出续签工单报表
- **THEN** 报表不包含跟进说明或状态变化历史

## ADDED Requirements

### Requirement: Append line renewal follow-up details
系统 MUST 在专线工单状态为“跟进中”时允许有权限的用户提交可选跟进说明；每条非空说明 MUST 追加保存并关联填写人和时间，不得覆盖已有说明。

#### Scenario: Add an optional note when entering follow-up
- **WHEN** 有权限的用户将专线工单改为“跟进中”并填写说明
- **THEN** 系统分别记录状态变化和跟进说明，均关联操作人及各自发生时间

#### Scenario: Add another note while following up
- **WHEN** 专线工单已经处于“跟进中”且用户不改变状态而填写新的说明
- **THEN** 系统新增一条跟进记录，旧记录及其填写人和时间保持不变

#### Scenario: Preserve note history after leaving follow-up
- **WHEN** 专线工单从“跟进中”切换为其他状态
- **THEN** 系统继续保留原有跟进说明并在处理时间线中显示

#### Scenario: Reject blank follow-up entries
- **WHEN** 用户提交空白或仅包含空格的跟进说明
- **THEN** 系统不创建跟进记录
