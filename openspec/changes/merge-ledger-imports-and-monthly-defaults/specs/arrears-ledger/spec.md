# Spec Delta

## MODIFIED Requirements

### Requirement: Arrears snapshot list
系统 MUST 在独立的“欠费明细”页面展示当前所选月份范围内各统计月份的合并后当前记录。主列表 MUST 显示日期、集团编码、集团名称、Excel 原始客户经理姓名、当前有效客户经理、账户 ID、承载产品名称、期末欠费金额和账龄。

#### Scenario: Open the arrears page
- **WHEN** 用户打开欠费明细页面且未指定日期范围
- **THEN** 系统默认筛选系统业务时区内的当前自然月；如果该月没有当前记录，则显示空结果且不回退到其他月份

#### Scenario: Show manager ownership columns
- **WHEN** 管理员查看欠费主列表
- **THEN** 每条记录分别显示 Excel 原始客户经理和当前有效客户经理

### Requirement: Arrears filters and ordering
系统 MUST 支持按统计月份起止范围、集团编码或名称关键词、账户 ID、承载产品名称和当前有效客户经理筛选欠费记录；统计月份范围包含起止月份。未提供统计月份筛选时 MUST 默认筛选当前自然月。欠费金额范围、账龄和是否达到停机标准 MUST NOT 作为首版列表筛选项。

#### Scenario: Filter arrears records
- **WHEN** 用户提交一个或多个筛选条件
- **THEN** 系统仅返回同时满足条件且用户有权查看的记录

#### Scenario: Filter inclusive month range
- **WHEN** 用户选择统计月份的开始值和结束值
- **THEN** 系统返回范围内各月份合并后的当前记录，且包含开始和结束月份

#### Scenario: Default ordering
- **WHEN** 查询未指定其他排序
- **THEN** 系统按集团编码、账户 ID 排序

### Requirement: Arrears history and pagination
系统 MUST 允许用户按统计月份范围查看范围内各月份合并后的当前数据；未设置日期范围时 MUST 仅查询当前自然月。页面 MUST 默认每页 20 条，并支持切换到每页 50 或 100 条，翻页时保留统计月份范围和其他筛选条件。

#### Scenario: Browse historical months
- **WHEN** 用户选择一个或多个历史统计月份
- **THEN** 系统显示范围内各月份合并后的当前数据并按每条记录的有效归属执行权限过滤

#### Scenario: Current month has no imported rows
- **WHEN** 当前自然月尚无欠费数据且用户未设置历史日期范围
- **THEN** 系统返回空列表并保留当前月份筛选

#### Scenario: Change page
- **WHEN** 用户切换分页页码或每页数量
- **THEN** 系统保留当前月份范围和其他筛选条件
