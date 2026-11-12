# Spec Delta

## MODIFIED Requirements

### Requirement: Arrears snapshot list
系统 MUST 在独立的“欠费明细”页面展示当前所选日期版本的欠费快照。主列表 MUST 显示日期、集团编码、集团名称、Excel 原始客户经理姓名、当前有效客户经理、账户 ID、承载产品名称、期末欠费金额、账龄和工单处理状态。

#### Scenario: Open the arrears page
- **WHEN** 用户打开欠费明细页面
- **THEN** 系统默认显示最新成功导入统计日期的当前版本记录及每条记录的当前工单状态

#### Scenario: Show manager ownership columns
- **WHEN** 管理员查看欠费主列表
- **THEN** 每条记录分别显示 Excel 原始客户经理、当前有效客户经理和工单处理状态

#### Scenario: Show current manager scope
- **WHEN** 客户经理查看欠费主列表
- **THEN** 页面只显示当前有效归属为该客户经理的欠费记录及其工单状态

### Requirement: Arrears filters and ordering
系统 MUST 支持按统计月份起止范围、集团编码或名称关键词、账户 ID、承载产品名称、当前有效客户经理和工单状态筛选欠费记录；统计月份范围包含起止月份，未设置范围时默认仅查询最新月份。欠费金额范围、账龄和是否达到停机标准 MUST NOT 作为首版列表筛选项。

#### Scenario: Filter arrears records
- **WHEN** 用户提交一个或多个筛选条件
- **THEN** 系统仅返回同时满足条件且用户有权查看的记录

#### Scenario: Filter inclusive month range
- **WHEN** 用户选择统计月份的开始值和结束值
- **THEN** 系统返回范围内各月份当前版本中的记录，且包含开始和结束月份

#### Scenario: Filter by work-order status
- **WHEN** 用户选择“待处理”“跟进中”或“已结清”中的一个工单状态
- **THEN** 系统只返回当前状态等于所选状态的欠费记录

#### Scenario: Default ordering
- **WHEN** 查询未指定其他排序
- **THEN** 系统按集团编码、账户 ID 排序

### Requirement: Arrears history and pagination
系统 MUST 允许用户按统计月份范围查看范围内各月份的当前版本；未设置范围时显示最新月份。页面 MUST 默认每页 20 条，并支持切换到每页 50 或 100 条，翻页时保留统计月份范围和其他筛选条件。

#### Scenario: Browse historical months
- **WHEN** 用户选择一个或多个历史统计月份
- **THEN** 系统显示范围内各月份的当前版本并按每条记录的有效归属执行权限过滤

#### Scenario: Change page
- **WHEN** 用户切换分页页码或每页数量
- **THEN** 系统保留当前月份范围和筛选条件

#### Scenario: Continue handling historical month work orders
- **WHEN** 有权限的客户经理或管理员处理历史月份中的欠费工单
- **THEN** 系统允许其继续修改状态或追加跟进说明，且不影响其他月份同一账户的工单

### Requirement: Arrears record details
系统 MUST 在不离开列表页的详情抽屉中按 Excel 原始列顺序展示该行所有有表头字段，并额外展示导入批次、工作表、原始行号、Excel 原始客户经理、当前有效客户经理、归属匹配状态、当前工单状态、最后处理人、最后处理时间及按时间排序的统一处理时间线。

#### Scenario: View arrears details
- **WHEN** 用户打开一条欠费记录详情
- **THEN** 系统显示所有有表头的原始 Excel 字段，包括“是否达到停机标准”，但不把该字段加入主列表，并显示工单当前状态、最后处理信息及处理时间线

#### Scenario: Append optional follow-up detail
- **WHEN** 有权限的用户将欠费工单设置为“跟进中”并填写可选情况说明
- **THEN** 系统追加一条包含说明、填写人和填写时间的跟进记录，不覆盖旧记录

#### Scenario: Add follow-up without changing status
- **WHEN** 欠费工单当前状态为“跟进中”且有权限的用户只填写新的跟进说明
- **THEN** 系统追加跟进记录并更新最后处理人和时间，但不新增状态变化记录

#### Scenario: View chronological handling timeline
- **WHEN** 用户查看欠费工单详情
- **THEN** 系统将状态变化和跟进说明按时间顺序合并展示，并标明记录类型、操作人和时间

#### Scenario: Display empty and zero values
- **WHEN** 原始字段值为空或为数值零
- **THEN** 系统分别显示“—”和“0”

### Requirement: Arrears export
系统 MUST 支持导出当前统计月份范围和筛选条件匹配的全部可见欠费原始记录，不受分页限制；原始明细导出 MUST 包含 Excel 中所有有表头字段并追加当前有效客户经理。工单报表 MUST 通过独立管理员导出提供，且不包含跟进说明或状态历史。

#### Scenario: Export filtered arrears records
- **WHEN** 用户导出当前欠费明细查询结果
- **THEN** 系统生成 Excel 文件，仅包含当前筛选条件和权限范围内的全部原始欠费记录

#### Scenario: Manager exports own arrears data
- **WHEN** 客户经理执行欠费原始数据导出
- **THEN** 导出文件只包含当前有效归属为该客户经理且其有权查看的记录

#### Scenario: Administrator exports arrears work-order report
- **WHEN** 管理员按统计月份范围和工单状态导出欠费工单
- **THEN** 系统不受分页限制，按固定顺序导出统计日期、集团编码、集团名称、Excel 原始客户经理、当前有效客户经理、账户 ID、承载产品名称、期末欠费金额、账龄、工单状态、最后处理人和最后处理时间

#### Scenario: Work-order report excludes follow-up content
- **WHEN** 管理员导出欠费工单报表
- **THEN** 报表不包含跟进说明或状态变化历史，且客户经理不能调用该管理员报表

## ADDED Requirements

### Requirement: Create monthly arrears work orders
系统 MUST 按统计月份与账户 ID 的组合为欠费快照维护唯一工单；每个新月份的工单独立创建且初始状态为“待处理”。

#### Scenario: Create a work order for a new monthly snapshot
- **WHEN** 管理员确认某月份包含有效账户欠费记录
- **THEN** 系统为该统计月份和账户 ID 创建一条“待处理”工单，最后处理人和时间为空

#### Scenario: Same account in another month
- **WHEN** 同一账户 ID 出现在另一个统计月份
- **THEN** 系统创建该月份独立的“待处理”工单，不继承其他月份的状态或处理记录

#### Scenario: Reconfirm same monthly account
- **WHEN** 同一月份和账户 ID 在该月修正版中再次出现
- **THEN** 系统复用该月唯一工单，不创建重复工单

### Requirement: Preserve arrears work orders across revisions
系统 MUST 在同一统计月份修正版确认时保留既有账户工单的当前状态、状态历史和跟进说明；新增账户创建“待处理”工单；被修正版移除的账户仅从当前版本隐藏，其工单及记录保留以供追溯。

#### Scenario: Revised account preserves handling records
- **WHEN** 修正版包含既有账户但 Excel 欠费字段发生变化
- **THEN** 系统更新当前快照引用并保留工单状态、状态历史和全部跟进说明

#### Scenario: Revised account is newly added
- **WHEN** 修正版包含上一版本不存在的账户 ID
- **THEN** 系统创建该月新的“待处理”工单，最后处理人和时间为空

#### Scenario: Revised account is removed
- **WHEN** 修正版不再包含上一版本的账户 ID
- **THEN** 工单不出现在当前欠费查询结果中，但工单和全部处理记录仍保留

#### Scenario: Removed account reappears
- **WHEN** 后续同月修正版重新包含曾被移除的账户 ID
- **THEN** 系统复用其原月度工单和处理记录，并将当前快照引用更新为最新记录

### Requirement: Allow arrears work-order status changes
欠费工单 MUST 支持“待处理”“跟进中”“已结清”三种状态，并允许有权限的操作人在任意状态之间切换。

#### Scenario: Change arrears work-order status
- **WHEN** 有权限的用户将欠费工单切换到与当前状态不同的支持状态
- **THEN** 系统保存新状态并记录原状态、新状态、操作人和操作时间

#### Scenario: Submit unchanged status without a follow-up
- **WHEN** 用户提交与当前状态相同的状态且未填写跟进说明
- **THEN** 系统不新增状态变化或跟进记录，且不更新最后处理人和时间

#### Scenario: Change settled work order back
- **WHEN** 有权限的用户将“已结清”工单切回“跟进中”或“待处理”
- **THEN** 系统允许状态切换并记录实际状态变化

### Requirement: Enforce arrears work-order access scope
系统 MUST 按欠费快照当前有效客户经理控制客户经理查看和处理范围；未分配或归属异常的工单仅管理员可见和处理，管理员可访问全部当前版本及历史月份工单。

#### Scenario: Manager views assigned arrears work orders
- **WHEN** 客户经理查询欠费工单
- **THEN** 系统只返回当前有效归属为该客户经理的工单

#### Scenario: Manager accesses unassigned arrears work order
- **WHEN** 欠费记录未分配当前有效客户经理或存在归属异常
- **THEN** 系统拒绝客户经理查看或处理该工单，管理员仍可查看和处理

### Requirement: Record arrears status history and follow-up details
系统 MUST 为欠费工单保存所有实际状态变化和所有非空跟进说明；跟进说明只允许在状态为“跟进中”时提交，且始终追加保存并关联填写人和时间。实际状态变化或新增说明均更新最后处理人和时间。

#### Scenario: Append follow-up when entering follow-up status
- **WHEN** 有权限的用户将工单切换为“跟进中”并填写说明
- **THEN** 系统分别记录状态变化和跟进说明，且两条记录都关联该操作人及对应时间

#### Scenario: Append follow-up while remaining in follow-up status
- **WHEN** 工单已处于“跟进中”且用户不改变状态而填写说明
- **THEN** 系统追加一条跟进记录并更新最后处理人和时间，不新增状态历史

#### Scenario: Preserve previous follow-up details
- **WHEN** 用户再次为工单填写跟进说明
- **THEN** 新说明追加在既有记录之后，旧说明及其填写人和时间不变

#### Scenario: Merge handling records by time
- **WHEN** 用户查看欠费工单处理时间线
- **THEN** 系统合并状态变化和跟进说明并按时间顺序展示，且同一时间的记录采用稳定排序
