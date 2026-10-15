# Spec Delta

## ADDED Requirements

### Requirement: Automatic risk task policy
系统 MUST 在成功风险重算中为 R001、R002、R003 创建或复用来源关联任务，负责人取客户当前有效责任客户经理，截止日取数据库当前日期后3个自然日。R004 MUST 关联并督办原任务，不递归创建新任务。负责人不可用时记录异常，不默认分配管理员。

#### Scenario: Continuous risk after task completion
- WHEN 同一来源和规则持续触发且原任务已完成或关闭
- THEN 系统保留任务终态，不创建新任务；只有成功重算确认风险解除后再次触发才开始新周期

#### Scenario: Concurrent or failed calculation
- WHEN 同一来源规则发生并发重算或任务创建后重算失败
- THEN 系统通过规则锁串行发布避免重复任务，失败时一并回滚新任务和风险结果，保留人工跟进

### Requirement: Actionable task creation
系统 MUST 允许从已确认的风险、到期事项或异常记录创建督办任务，并保存任务与客户、业务、账单或到期记录的来源关联。

#### Scenario: Create from ledger
- **WHEN** 用户从客户或业务详情发起跟进任务
- **THEN** 系统带出来源对象、任务类型和可选负责人

#### Scenario: Orphan cannot create task
- **WHEN** 来源是客户关联失败的剔除记录
- **THEN** 系统禁止创建正式客户任务

### Requirement: Task idempotency
系统 MUST 根据来源对象、规则编码、任务类型和未关闭状态判断自动或重复创建任务，避免同一触发条件产生重复未完成任务。

#### Scenario: Same trigger repeats
- **WHEN** 同一客户业务的同一规则重复计算
- **THEN** 系统复用或更新已有未关闭任务，不重复创建

#### Scenario: New cycle creates task
- **WHEN** 前一任务已关闭且业务再次进入新的触发周期
- **THEN** 系统允许按新的触发时间和计算批次创建新任务，并保留历史

### Requirement: Task list and scope
系统 MUST 提供按状态、优先级、负责人、截止时间和来源类型筛选的任务列表，并执行用户客户范围限制。

#### Scenario: Manager views tasks
- **WHEN** 客户经理打开任务中心
- **THEN** 系统只显示其客户范围内的任务，并允许创建和处理跟进

#### Scenario: Overdue filter
- **WHEN** 用户选择逾期筛选
- **THEN** 系统显示截止时间已过且未完成或未关闭的任务

### Requirement: Task lifecycle
系统 MUST 支持待处理、处理中、待核实、已完成和已关闭等状态，并限制无依据的状态跳转。

#### Scenario: Process task
- **WHEN** 用户开始处理待处理任务
- **THEN** 系统更新状态、操作人和操作时间

#### Scenario: Close task
- **WHEN** 用户关闭任务
- **THEN** 系统要求关闭原因或结论并保留关闭记录

### Requirement: Append-only follow-up
系统 MUST 允许向任务追加跟进记录，保存内容、下一步、操作者和时间，不覆盖历史记录。

#### Scenario: Add follow-up
- **WHEN** 用户提交一条有效跟进记录
- **THEN** 系统追加记录并按时间顺序展示

#### Scenario: Empty note is rejected
- **WHEN** 跟进内容和下一步均为空
- **THEN** 系统拒绝保存并提示必填信息

### Requirement: Overdue indication and reminders
系统 MUST 根据截止时间和未完成状态计算逾期，并在工作台和任务中心提供站内提示；MVP 不默认发送外部消息。

#### Scenario: Task becomes overdue
- **WHEN** 当前时间晚于截止时间且任务未完成或关闭
- **THEN** 系统显示逾期状态和逾期天数

#### Scenario: Completed task stays completed
- **WHEN** 任务在截止时间前完成或关闭
- **THEN** 系统不再将其显示为逾期
