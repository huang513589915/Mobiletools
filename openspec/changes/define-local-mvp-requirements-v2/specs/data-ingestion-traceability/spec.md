# Spec Delta

## ADDED Requirements

### Requirement: V1 workbook contract
系统 MUST 支持一个包含【客户基础信息】【欠费账单明细】【专线合同台账】【跟进催缴记录】【预警规则配置】的工作簿，并支持五个业务类型的独立 Excel 分别导入。

#### Scenario: Workbook passes precheck
- **WHEN** 用户上传符合 V1 工作表和表头契约的文件
- **THEN** 系统通过文件、工作表、表头、必填项、日期、金额和业务关联预检

#### Scenario: Invalid sheet is rejected
- **WHEN** 文件缺少必需工作表或表头
- **THEN** 系统指出缺失项并阻止确认导入

### Requirement: Field and amount validation
系统 MUST 校验必填字段、日期格式、金额类型和欠费金额，欠费金额按应缴金额减已缴金额计算。

#### Scenario: Amount is calculated
- **WHEN** 账单包含应缴金额和已缴金额
- **THEN** 系统计算欠费金额并校验非负和业务允许范围

#### Scenario: Formula text is unusable
- **WHEN** Excel 只有无法读取结果的公式文本或金额格式错误
- **THEN** 系统将该行标记失败并阻止进入正式账单

### Requirement: Business identity and idempotent import
系统 MUST 使用客户编号、业务编号、账单编号、跟进记录编号和规则编码作为候选业务标识，并结合真实业务唯一性确认去重和更新范围。

#### Scenario: Same file is imported twice
- **WHEN** 同一文件或相同业务内容重复提交
- **THEN** 系统通过文件指纹和业务唯一约束识别重复，不生成重复正式记录

#### Scenario: Empty cells are imported
- **WHEN** 更新文件的非必填单元格为空
- **THEN** 默认不覆盖已有有效值；主动清空必须使用明确的清空操作

### Requirement: Preview classification and orphan exclusion
系统 MUST 在正式写入前展示新增、更新、重复、冲突、无效和剔除记录，并剔除无法关联客户的欠费账单和跟进记录。

#### Scenario: Orphan record is excluded
- **WHEN** 账单或跟进记录引用不存在的客户编号，例如 `C-10765`
- **THEN** 系统不写入正式账单、任务或台账，只在批次结果保留原始行号、剔除数量和“客户关联失败”原因

#### Scenario: Unresolved conflict is blocked
- **WHEN** 记录无法依据已确认唯一性和更新规则处理
- **THEN** 系统标记待核查，不静默新增或覆盖

### Requirement: Explicit import confirmation
系统 MUST 将预检和正式写入分离，用户确认前不得改变正式业务数据。

#### Scenario: User cancels import
- **WHEN** 用户取消预览结果
- **THEN** 系统不改变正式数据，但保留本次预检结果

#### Scenario: Batch write fails
- **WHEN** 批量写入发生不可恢复错误
- **THEN** 系统回滚事务或明确标记未完成，并返回成功、剔除和失败明细

### Requirement: Import traceability
系统 MUST 记录批次文件信息、操作者、开始/结束时间、状态、成功数、更新数、重复数、剔除数、失败数和行级原因。

#### Scenario: Batch is reviewed
- **WHEN** 用户打开历史导入批次
- **THEN** 系统展示汇总和可定位到工作表、行号、业务标识的明细

#### Scenario: Source is retained
- **WHEN** 导入值与人工维护值冲突
- **THEN** 系统保留原值、来源、处理结论和审计信息
