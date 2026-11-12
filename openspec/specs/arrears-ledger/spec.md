# arrears-ledger Specification

## Purpose

让管理员和客户经理按各自权限查看集团账户月度欠费快照，并通过稳定的账户标识、历史月份切换和原始字段详情追溯 Excel 数据。

## Requirements

### Requirement: Arrears snapshot list
系统 MUST 在独立的“欠费明细”页面展示当前所选日期版本的欠费快照。主列表 MUST 显示日期、集团编码、集团名称、Excel 原始客户经理姓名、当前有效客户经理、账户 ID、承载产品名称、期末欠费金额和账龄。

#### Scenario: Open the arrears page
- **WHEN** 用户打开欠费明细页面
- **THEN** 系统默认显示最新成功导入统计日期的当前版本记录

#### Scenario: Show manager ownership columns
- **WHEN** 管理员查看欠费主列表
- **THEN** 每条记录分别显示 Excel 原始客户经理和当前有效客户经理

### Requirement: Arrears filters and ordering
系统 MUST 支持按统计月份起止范围、集团编码或名称关键词、账户 ID、承载产品名称和当前有效客户经理筛选欠费记录；统计月份范围包含起止月份，未设置范围时默认仅查询最新月份。欠费金额范围、账龄和是否达到停机标准 MUST NOT 作为首版列表筛选项。
#### Scenario: Filter arrears records
- **WHEN** 用户提交一个或多个筛选条件
- **THEN** 系统仅返回同时满足条件且用户有权查看的记录

#### Scenario: Filter inclusive month range
- **WHEN** 用户选择统计月份的开始值和结束值
- **THEN** 系统返回范围内各月份当前版本中的记录，且包含开始和结束月份

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

### Requirement: Arrears record details
系统 MUST 在不离开列表页的详情抽屉中按 Excel 原始列顺序展示该行所有有表头字段，并额外展示导入批次、工作表、原始行号、Excel 原始客户经理、当前有效客户经理和归属匹配状态。

#### Scenario: View arrears details
- **WHEN** 用户打开一条欠费记录详情
- **THEN** 系统显示所有有表头的原始 Excel 字段，包括“是否达到停机标准”，但不把该字段加入主列表

#### Scenario: Display empty and zero values
- **WHEN** 原始字段值为空或为数值零
- **THEN** 系统分别显示“—”和“0”

### Requirement: Arrears export
系统 MUST 支持导出当前统计月份范围和筛选条件匹配的全部可见欠费记录，不受分页限制；导出 MUST 包含 Excel 中所有有表头字段并追加当前有效客户经理，且 MUST NOT 添加系统计算的剩余天数字段。

#### Scenario: Export filtered arrears records
- **WHEN** 用户导出当前查询结果
- **THEN** 系统生成 Excel 文件，仅包含当前筛选条件和权限范围内的全部记录

#### Scenario: Manager exports own arrears data
- **WHEN** 客户经理执行欠费数据导出
- **THEN** 导出文件只包含当前有效归属为该客户经理且其有权查看的记录
