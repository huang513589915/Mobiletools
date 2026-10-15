# Spec Delta

## Purpose

为风险结果提供可解释的规则配置、重算记录、异常核查和关键操作审计，保证风险变化不会覆盖人工处理证据。

## ADDED Requirements

### Requirement: V1 rule configuration
系统 MUST 支持管理员查看和维护四条 V1 规则：余额 `< 500` 元、累计欠费 `> 1000` 元、距离到期 `<= 30` 天、任务超期 `> 1` 天。规则比较符、阈值、版本和生效时间必须可追溯。

#### Scenario: Manager cannot edit rules
- **WHEN** 客户经理提交规则修改
- **THEN** 系统拒绝请求且规则保持不变

#### Scenario: Administrator changes rule
- **WHEN** 管理员提交通过校验的阈值变更
- **THEN** 系统保存新版本、操作人和生效时间，不删除历史版本

#### Scenario: Boundary values are validated
- **WHEN** 管理员提交缺少比较符、负数金额阈值或不符合规则类型的天数阈值
- **THEN** 系统拒绝修改，不创建新版本，并返回可定位的校验原因

### Requirement: Explainable risk calculation
系统 MUST 为每个风险结果保存规则编码、触发值、阈值、计算时间、来源批次和核查状态。

#### Scenario: Rule is triggered
- **WHEN** 正式台账数据满足某条启用规则
- **THEN** 系统生成或更新对应风险结果并显示解释信息

#### Scenario: Rule input is invalid
- **WHEN** 计算所需数据缺失或金额异常
- **THEN** 系统不生成已确认风险，记录异常原因

### Requirement: Recalculation run
系统 MUST 记录风险重算的范围、规则版本、开始/结束时间、成功失败状态和可恢复错误信息。

#### Scenario: Recalculation fails
- **WHEN** 重算中发生部分或系统错误
- **THEN** 系统保留本次运行失败记录，不覆盖人工任务和跟进，并允许后续重试

#### Scenario: Recalculation succeeds
- **WHEN** 管理员确认执行重算
- **THEN** 系统只更新派生风险结果，不重复创建未关闭任务

### Requirement: Exception and audit records
系统 MUST 为导入冲突、客户关联失败、金额异常、风险计算失败和关键权限操作提供可查询记录。

#### Scenario: Exception is reviewed
- **WHEN** 有权限用户处理异常
- **THEN** 系统记录状态、结论、处理人和时间，并保留原始原因

#### Scenario: Sensitive operation is audited
- **WHEN** 用户登录、导入、规则修改、范围调整或导出数据
- **THEN** 系统记录操作者、操作类型、目标、结果和时间，且不记录密码
