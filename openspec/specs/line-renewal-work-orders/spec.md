# line-renewal-work-orders Specification

## Purpose

为专线到期快照提供可追溯的续签处理工单，让客户经理能够更新处理状态，让管理员能够按统计日期查看、导出和审计工单处理结果。

## Requirements

### Requirement: Create a renewal work order for each line snapshot
系统 MUST 为每个专线快照维护一条续签工单，工单业务唯一键为统计日期与成员服务号码的组合；工单初始状态 MUST 为“待处理”。

#### Scenario: New line snapshot creates a work order
- **WHEN** 管理员确认导入包含某统计日期和成员服务号码的有效新专线记录
- **THEN** 系统为该组合创建一条“待处理”续签工单，且最后处理人和最后处理时间为空

#### Scenario: Same line identity is encountered again
- **WHEN** 同一统计日期和成员服务号码在该统计日期的修正版中再次出现
- **THEN** 系统复用该组合对应的续签工单，不创建第二条当前工单

### Requirement: Preserve work-order handling across revisions
系统 MUST 在同一统计日期的修正版确认后保留已有工单的当前状态、最后处理人、最后处理时间和状态变更历史；修正版新增的专线 MUST 创建为“待处理”。

#### Scenario: Revised row keeps handling data
- **WHEN** 修正版包含已有统计日期和成员服务号码，但 Excel 字段发生变化
- **THEN** 系统更新当前专线快照关联，但保留原工单的状态、最后处理人、最后处理时间和全部状态历史

#### Scenario: Revised row is newly added
- **WHEN** 修正版包含上一版本不存在的统计日期和成员服务号码
- **THEN** 系统创建一条新的“待处理”工单，且其最后处理人和最后处理时间为空

#### Scenario: Revised row is removed
- **WHEN** 修正版不再包含上一版本的某个成员服务号码
- **THEN** 该专线和工单不出现在当前版本查询结果中，但历史工单及其状态历史仍可追溯

### Requirement: Allow authorized status updates
系统 MUST 支持“待处理”“跟进中”“已续签”“不续签”四种状态，并允许有权限的操作人将当前状态切换为任意一种状态。

#### Scenario: Manager updates an assigned work order
- **WHEN** 客户经理更新当前有效归属为自己的专线工单状态
- **THEN** 系统保存新状态，并将该客户经理记录为最后处理人

#### Scenario: Manager accesses another manager's work order
- **WHEN** 客户经理尝试查看或更新当前有效归属不是自己的工单
- **THEN** 系统拒绝该请求且不改变工单状态

#### Scenario: Administrator updates any work order
- **WHEN** 管理员查看或更新任意专线工单
- **THEN** 系统允许该操作并保存管理员为最后处理人

### Requirement: Record status change history
系统 MUST 记录每次实际发生的状态变化，包括原状态、新状态、操作人和操作时间，并向有权查看该工单的用户提供按时间排序的历史；提交与当前状态相同的状态 MUST NOT 新增状态变化历史。

#### Scenario: Record a changed status
- **WHEN** 工单从一个状态切换到另一个不同状态
- **THEN** 系统追加一条状态历史，并更新工单的最后处理人和最后处理时间

#### Scenario: Ignore a no-op status update
- **WHEN** 操作人提交与当前状态相同的状态
- **THEN** 系统保持工单数据不变且不追加状态历史

#### Scenario: Change a terminal-looking status back
- **WHEN** 工单从“已续签”或“不续签”切换到任意其他状态
- **THEN** 系统允许切换并追加对应状态历史

#### Scenario: View status history
- **WHEN** 管理员或当前有效归属客户经理查看工单详情
- **THEN** 系统展示该工单全部实际状态变化，包含原状态、新状态、操作人和操作时间，并按时间顺序排列

### Requirement: Enforce work-order visibility and handling scope
系统 MUST 按专线快照的当前有效客户经理控制客户经理的数据范围；未分配或归属异常的工单仅管理员可见，管理员可查看全部工单。

#### Scenario: Manager lists work orders
- **WHEN** 客户经理查询续签工单
- **THEN** 返回结果只包含当前有效归属为该客户经理的专线工单

#### Scenario: Unassigned work order is queried
- **WHEN** 专线没有当前有效客户经理或处于归属匹配异常
- **THEN** 该工单不返回给任何客户经理，但管理员可以查看和处理

### Requirement: Export administrator renewal work-order report
系统 MUST 允许管理员按当前统计日期范围、工单状态及已有专线筛选条件导出全部匹配工单，不受分页限制；导出 MUST 使用固定字段顺序。

#### Scenario: Export filtered work orders
- **WHEN** 管理员选择统计日期范围、工单状态和其他专线筛选条件后导出
- **THEN** 系统导出所有匹配且管理员可见的工单

#### Scenario: Export columns
- **WHEN** 管理员下载续签工单报表
- **THEN** 文件按顺序包含统计日期、集团编码、集团名称、产品编码、产品名称、成员服务号码、Excel 原始客户经理、当前有效客户经理、计费截止日期、状态、最后处理人和最后处理时间

#### Scenario: Manager attempts administrator report export
- **WHEN** 客户经理请求续签工单管理员报表
- **THEN** 系统拒绝请求
