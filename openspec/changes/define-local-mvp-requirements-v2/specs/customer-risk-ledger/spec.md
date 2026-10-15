# Spec Delta

## ADDED Requirements

### Requirement: Unified customer and service ledger
系统 MUST 按客户编号和业务编号展示客户基础信息、业务、欠费、余额、到期、风险、来源批次和更新时间。

#### Scenario: Search ledger
- **WHEN** 用户输入客户编号、客户名称、业务编号或筛选条件
- **THEN** 系统只返回当前用户有权查看的匹配台账，并显示来源和更新时间

#### Scenario: Empty result
- **WHEN** 查询没有匹配记录
- **THEN** 系统显示明确的空结果状态，不使用演示数据填充

### Requirement: Ledger detail and traceability
系统 MUST 提供客户或业务详情，并展示相关账单、到期记录、风险结果、导入批次、任务和跟进历史。

#### Scenario: Detail opens
- **WHEN** 用户从工作台或台账打开一条记录
- **THEN** 系统展示该记录当前值、业务关联和可用历史来源

#### Scenario: History unavailable
- **WHEN** 某记录没有历史数据
- **THEN** 系统明确显示暂无历史，不伪造处理记录

### Requirement: V1 risk indicators
系统 MUST 展示由正式规则计算出的余额 `< 500` 元、累计欠费 `> 1000` 元、距离到期 `<= 30` 天和任务超期 `> 1` 天的风险指标。时间比较使用服务端计算时钟；自然日边界以最终确认的业务时区为准。

#### Scenario: Risk indicator is shown
- **WHEN** 关联数据满足任一 V1 规则且数据校验通过
- **THEN** 系统显示触发规则、计算时间和风险状态

#### Scenario: Threshold boundary is not ambiguous
- **WHEN** 余额等于 500 元、累计欠费等于 1000 元、距离到期等于 30 天或任务刚好超期 1 天
- **THEN** 系统按照严格比较符号判断，不把等于边界误判为对应风险

#### Scenario: Data is unresolved
- **WHEN** 关联数据缺失、金额异常或来源冲突未核查
- **THEN** 系统标记待核查，不展示为已确认风险结论

### Requirement: Ledger filtering and access scope
系统 MUST 支持按状态、负责人、风险级别、业务类型、日期和异常状态筛选，并在服务端执行用户数据范围限制。

#### Scenario: Manager views scope
- **WHEN** 客户经理查询台账
- **THEN** 系统只返回其客户范围内的记录

#### Scenario: Combined filters have no result
- **WHEN** 组合筛选无匹配记录
- **THEN** 系统显示空结果并允许清除筛选条件

### Requirement: Source conflict review
系统 MUST 将正式台账与其他来源不一致的记录标记为待核查，并保留原值、来源和处理结论。

#### Scenario: Conflict is found
- **WHEN** 新导入值与已确认值存在冲突
- **THEN** 系统不静默覆盖，显示冲突并进入异常核查流程

#### Scenario: Conflict is resolved
- **WHEN** 有权限用户提交核查结论
- **THEN** 系统记录处理人、时间和结论，且风险重新计算不会删除该人工记录
