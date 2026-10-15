# Spec Delta

## Purpose

为客户经理和管理员提供基于正式台账、风险结果和任务状态的风险工作台，帮助用户快速定位需要核查、催缴、续签或跟进的客户与业务。

## ADDED Requirements

### Requirement: Risk overview
系统 MUST 展示当前授权范围内的高风险客户、待核查数据、临近到期业务、待处理任务和逾期任务数量。

#### Scenario: Dashboard loads
- **WHEN** 已登录用户打开工作台
- **THEN** 系统按其数据范围返回可追溯的数量和更新时间

#### Scenario: No formal data
- **WHEN** 数据库没有正式导入数据
- **THEN** 系统显示空状态，不使用 Demo 数据冒充真实统计

### Requirement: Workbench filtering
系统 MUST 支持按风险级别、负责人、业务类型、日期范围和异常状态筛选工作台列表。

#### Scenario: Filter dashboard
- **WHEN** 用户选择筛选条件
- **THEN** 系统刷新统计和列表，仅展示授权范围内匹配数据

#### Scenario: Clear filter
- **WHEN** 用户清除筛选条件
- **THEN** 系统恢复当前授权范围的默认概览

### Requirement: Navigation to actions
系统 MUST 支持从工作台的统计项或列表跳转客户台账、异常核查、任务中心和导入批次详情。

#### Scenario: Open task list
- **WHEN** 用户点击待处理或逾期任务统计
- **THEN** 系统打开对应筛选条件的任务中心

#### Scenario: Open unresolved data
- **WHEN** 用户点击待核查或异常统计
- **THEN** 系统打开可定位到批次和原始行号的核查列表
