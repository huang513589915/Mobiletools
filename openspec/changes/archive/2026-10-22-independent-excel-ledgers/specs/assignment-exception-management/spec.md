# Spec Delta

## Purpose

让管理员处理 Excel 客户经理姓名无法唯一匹配账号的记录，并以逐行有效归属控制数据访问，同时保留每次人工分配和改派的审计轨迹。

## ADDED Requirements

### Requirement: Exact-name matching exceptions
系统 MUST 将 Excel 客户经理姓名与账号真实姓名精确匹配；未找到账号或匹配到多个同名账号的记录 MUST 标记为归属匹配异常，并保留原始 Excel 姓名。

#### Scenario: No account matches
- **WHEN** 某行 Excel 客户经理姓名没有对应账号真实姓名
- **THEN** 系统保留该行并将其标记为归属匹配异常

#### Scenario: Multiple accounts match
- **WHEN** 多个账号具有与 Excel 客户经理姓名完全相同的真实姓名
- **THEN** 系统不自动选择账号并将该行标记为归属匹配异常

### Requirement: Exception record visibility
系统 MUST 向管理员提供逐行异常列表，展示数据来源、统计日期、集团编码、集团名称、Excel 客户经理姓名、匹配失败原因、文件批次、工作表、原始行号和当前处理状态；不得按集团合并不同记录。

#### Scenario: Review unmatched rows
- **WHEN** 管理员打开归属匹配异常页面
- **THEN** 每条异常原始记录单独展示且可定位到其批次、工作表和原始行号

#### Scenario: Filter exception records
- **WHEN** 管理员按数据来源、统计日期、集团编码或名称、Excel 客户经理姓名、批次和处理状态筛选
- **THEN** 系统仅显示满足全部条件的异常记录

### Requirement: Bulk row assignment
系统 MUST 允许管理员逐条或批量选择异常记录并分配给一个客户经理账号；单次批量分配 MUST 仅包含同一种数据来源的记录；同一集团的记录 MUST 可分别分配给不同客户经理。

#### Scenario: Select current page
- **WHEN** 管理员使用全选当前页
- **THEN** 系统只选择当前页符合筛选条件的记录

#### Scenario: Select all filtered records
- **WHEN** 管理员选择当前筛选结果全部记录
- **THEN** 系统选择所有匹配记录，包括其他分页中的记录

#### Scenario: Confirm bulk assignment
- **WHEN** 管理员提交批量分配
- **THEN** 系统在执行前显示选中数量和目标客户经理并要求确认

#### Scenario: Selection includes different sources
- **WHEN** 当前选择包含欠费和专线两种数据来源
- **THEN** 系统要求管理员按来源分别执行分配，且不得在一次操作中跨来源修改归属

### Requirement: Reassignment history
系统 MUST 允许管理员再次分配已处理记录；每次分配只更新所选行的当前有效客户经理，并保存操作者、操作时间、原归属和新归属。

#### Scenario: Reassign a processed row
- **WHEN** 管理员将已处理记录改派给另一客户经理
- **THEN** 系统更新该行当前有效归属并保留新的审计记录

#### Scenario: Preserve original Excel manager
- **WHEN** 管理员分配或改派记录
- **THEN** Excel 原始客户经理姓名保持不变并仍可在页面详情中查看

### Requirement: Assignment scope isolation
系统 MUST 将人工分配限定在被选中的行、其数据来源和所属快照内；每次新导入 MUST 根据本次文件中的客户经理姓名重新匹配，不继承过往快照的人工归属。

#### Scenario: Import a later snapshot
- **WHEN** 管理员导入同一数据来源的后续统计日期
- **THEN** 系统按新文件重新匹配客户经理，不复制历史快照的人工分配

#### Scenario: Assign one source type
- **WHEN** 管理员分配欠费或专线记录
- **THEN** 系统不改变另一来源中的记录归属

### Requirement: Effective-assignment visibility
系统 MUST 按每条记录的当前有效客户经理分别控制欠费和专线数据访问；未分配或归属匹配异常的记录仅管理员可见，客户经理只能查看当前有效归属为本人的记录。

#### Scenario: Different ownership by source
- **WHEN** 同一集团的欠费和专线记录分配给不同客户经理
- **THEN** 每位客户经理只能查看分配给自己的对应来源记录

#### Scenario: Unassigned record
- **WHEN** 记录尚未分配当前有效客户经理
- **THEN** 客户经理无法查看该记录，管理员看到“未分配（归属匹配异常）”

#### Scenario: Reassignment changes access
- **WHEN** 管理员将记录从一名客户经理改派给另一名客户经理
- **THEN** 前一名客户经理失去该记录访问权，后一名客户经理获得该记录访问权

### Requirement: Snapshot-specific ownership history
系统 MUST 为不同来源和统计日期独立保存有效归属；历史快照按其自身当前有效归属授权，分配或改派不得影响其他日期或来源。

#### Scenario: View history as a manager
- **WHEN** 客户经理切换历史统计日期
- **THEN** 系统按该历史快照独立保存的当前有效归属过滤记录

#### Scenario: Reassign a historical row
- **WHEN** 管理员改派某一历史快照中的记录
- **THEN** 只有该来源和该历史快照中被选中的记录归属改变
