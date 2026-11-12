# Spec Delta

## Purpose

让管理员和客户经理独立查看专线产品实例的到期快照，按计费截止日期动态计算剩余天数，并保留完整 Excel 原始字段和历史批次追溯能力。

## ADDED Requirements

### Requirement: Line-expiry snapshot list
系统 MUST 在独立的“专线到期明细”页面展示当前所选统计日期版本。主列表 MUST 显示统计日期、集团编码、集团名称、产品编码、产品名称、成员服务号码、Excel 原始客户经理、当前有效客户经理、计费截止日期和剩余天数。

#### Scenario: Open the line-expiry page
- **WHEN** 用户打开专线到期明细页面
- **THEN** 系统默认显示最新成功导入统计日期的当前版本记录

#### Scenario: Show line manager ownership columns
- **WHEN** 管理员查看专线主列表
- **THEN** 每条记录分别显示 Excel 原始客户经理和当前有效客户经理

### Requirement: Dynamic remaining days
系统 MUST 将剩余天数计算为计费截止日期减去系统当前日期；合同到期日不得参与剩余天数计算、首版筛选或排序。

#### Scenario: Calculate remaining days
- **WHEN** 用户查看任意统计日期的专线记录
- **THEN** 系统按当前日期动态计算剩余天数，允许结果为负数

#### Scenario: Date advances
- **WHEN** 系统当前日期变化而记录未重新导入
- **THEN** 同一条记录的剩余天数随查询重新计算

### Requirement: Line-expiry filters and ordering
系统 MUST 支持按统计日期、集团编码或名称关键词、产品编码或名称关键词、当前有效客户经理和剩余天数最小值/最大值筛选；剩余天数范围包含输入边界。

#### Scenario: Filter by remaining-day range
- **WHEN** 用户输入最小值、最大值或两者之一
- **THEN** 系统返回剩余天数落在该范围内且用户有权查看的记录

#### Scenario: Default ordering
- **WHEN** 查询未指定其他排序
- **THEN** 系统按剩余天数升序，剩余天数相同时按集团编码、成员服务号码排序

### Requirement: Line-expiry history and pagination
系统 MUST 允许用户切换历史统计日期并查看该日期当前版本；页面 MUST 默认每页 20 条，并支持切换到每页 50 或 100 条，翻页时保留筛选条件。

#### Scenario: Switch historical date
- **WHEN** 用户选择一个历史统计日期
- **THEN** 系统显示该日期的当前版本并按该版本记录的有效归属执行权限过滤

#### Scenario: Paginate line records
- **WHEN** 用户切换分页页码或每页数量
- **THEN** 系统保留当前日期、筛选和动态剩余天数计算

### Requirement: Line-expiry record details
系统 MUST 在不离开列表页的详情抽屉中按 Excel 原始列顺序展示该行所有有表头字段，并额外展示导入批次、工作表、原始行号、Excel 原始客户经理、当前有效客户经理和归属匹配状态；成员状态和合同到期日只在详情中展示。

#### Scenario: View line details
- **WHEN** 用户打开一条专线记录详情
- **THEN** 系统显示所有有表头的原始 Excel 字段，包括成员状态和合同到期日

#### Scenario: Display empty and zero values
- **WHEN** 原始字段值为空或为数值零
- **THEN** 系统分别显示“—”和“0”

### Requirement: Line-expiry export
系统 MUST 支持导出当前统计日期和筛选条件匹配的全部可见专线记录，不受分页限制；导出 MUST 包含 Excel 中所有有表头字段并追加当前有效客户经理，且 MUST NOT 添加系统计算的剩余天数字段。

#### Scenario: Export filtered line records
- **WHEN** 用户导出当前查询结果
- **THEN** 系统生成 Excel 文件，仅包含当前筛选条件和权限范围内的全部记录

#### Scenario: Manager exports own line data
- **WHEN** 客户经理执行专线数据导出
- **THEN** 导出文件只包含当前有效归属为该客户经理且其有权查看的记录
