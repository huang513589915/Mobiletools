# Spec Delta

## MODIFIED Requirements

### Requirement: Line-expiry snapshot list
系统 MUST 在独立的“专线到期明细”页面展示当前所选统计日期范围内各统计日期合并后的当前记录。主列表 MUST 显示统计日期、集团编码、集团名称、产品编码、产品名称、成员服务号码、Excel 原始客户经理、当前有效客户经理、计费截止日期、剩余天数和续签处理状态。

#### Scenario: Open the line-expiry page
- **WHEN** 用户打开专线到期明细页面且未指定日期范围
- **THEN** 系统默认筛选系统业务时区内当前自然月首日至月末的全部统计日期；如果该范围没有当前记录，则显示空结果且不回退到其他月份

#### Scenario: Show line manager ownership columns
- **WHEN** 管理员查看专线主列表
- **THEN** 每条记录分别显示 Excel 原始客户经理、当前有效客户经理和续签处理状态

#### Scenario: Manager views assigned line work orders
- **WHEN** 客户经理打开专线到期明细页面
- **THEN** 页面只显示当前有效归属为该客户经理的专线记录及其续签处理状态

### Requirement: Line-expiry filters and ordering
系统 MUST 支持按统计日期起止范围、集团编码或名称关键词、产品编码或名称关键词、当前有效客户经理、续签处理状态和剩余天数最小值/最大值筛选；统计日期范围、状态筛选和剩余天数范围均包含输入边界或所选状态。未提供统计日期范围时 MUST 默认筛选系统业务时区内的当前自然月。

#### Scenario: Filter inclusive statistics-date range
- **WHEN** 用户选择统计日期的开始值和结束值
- **THEN** 系统返回范围内各日期合并后的当前记录，且包含开始和结束日期

#### Scenario: Filter by renewal status
- **WHEN** 用户选择“待处理”“跟进中”“已续签”或“不续签”中的一个状态
- **THEN** 系统只返回当前状态等于所选状态的专线工单

#### Scenario: Filter by remaining-day range
- **WHEN** 用户输入最小值、最大值或两者之一
- **THEN** 系统返回剩余天数落在该范围内且用户有权查看的记录

#### Scenario: Default ordering
- **WHEN** 查询未指定其他排序
- **THEN** 系统按剩余天数升序，剩余天数相同时按集团编码、成员服务号码排序

### Requirement: Line-expiry history and pagination
系统 MUST 允许用户按统计日期范围查看范围内各日期合并后的当前数据；未设置统计日期范围时 MUST 仅查询系统业务时区内当前自然月。页面 MUST 默认每页 20 条，并支持切换到每页 50 或 100 条，翻页时保留统计日期范围和其他筛选条件。

#### Scenario: Browse historical statistics dates
- **WHEN** 用户选择一个或多个历史统计日期
- **THEN** 系统显示范围内各日期合并后的当前记录并按每条记录的有效归属执行权限过滤

#### Scenario: Current month has no imported rows
- **WHEN** 当前自然月尚无专线数据且用户未设置历史日期范围
- **THEN** 系统返回空列表并保留当前自然月范围

#### Scenario: Paginate line records
- **WHEN** 用户切换分页页码或每页数量
- **THEN** 系统保留当前统计日期范围、其他筛选和动态剩余天数计算
