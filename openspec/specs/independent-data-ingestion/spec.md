# independent-data-ingestion Specification

## Purpose

为欠费月度快照和专线到期快照提供相互独立、可预览、可确认、可追溯的 Excel 导入契约，保证真实文件字段能够完整保留并支持历史版本查询。

## Requirements

### Requirement: Independent source types
系统 MUST 将集团账户欠费明细月表和专线到期需续签明细表作为两种独立数据来源导入、存储、查询和导出，不建立两类记录之间的业务关联。

#### Scenario: Import arrears workbook
- **WHEN** 管理员上传欠费明细月表
- **THEN** 系统只创建欠费批次和欠费快照候选记录

#### Scenario: Import line-expiry workbook
- **WHEN** 管理员上传专线到期明细表
- **THEN** 系统只创建专线批次和专线快照候选记录

### Requirement: Required field contract
系统 MUST 按来源校验必填表头。欠费表必填日期、集团编码、集团名称、集团客户经理姓名、账户 ID 和期末欠费金额；专线表必填统计日期、集团编码、集团名称、成员服务号码、客户经理姓名和计费截止日期。其他字段（包括承载产品名称、账龄、产品编码和产品名称）可以为空；有表头时仍 MUST 保留其原始字段和值。

#### Scenario: Required header is missing
- **WHEN** 上传文件缺少对应来源的任一必填表头
- **THEN** 预检标记阻断错误并禁止确认导入

#### Scenario: Required value is missing
- **WHEN** 某行的必填字段没有可用值
- **THEN** 预检显示工作表和原始行号，并禁止确认该批次

### Requirement: Raw field retention
系统 MUST 保留每个非空表头及其原始值供详情和导出使用；空表头列 MUST 忽略，重复的非空表头 MUST 按列位置区分而不得互相覆盖。

#### Scenario: Workbook has an empty header
- **WHEN** 文件存在没有表头的列
- **THEN** 该列不进入业务字段、详情或导出

#### Scenario: Workbook has duplicate headers
- **WHEN** 文件存在相同的非空表头
- **THEN** 系统分别保存各列值并以列位置形成稳定区分

### Requirement: Snapshot identity
系统 MUST 使用“账户 ID + 日期”作为欠费快照行的唯一业务键，使用“成员服务号码 + 统计日期”作为专线快照行的唯一业务键；唯一性只在对应来源和统计日期内约束。

#### Scenario: Duplicate arrears identity
- **WHEN** 同一欠费文件中相同日期和账户 ID 出现多行
- **THEN** 预检标记重复业务键并阻止确认导入

#### Scenario: Duplicate line identity
- **WHEN** 同一专线文件中相同统计日期和成员服务号码出现多行
- **THEN** 预检标记重复业务键并阻止确认导入

### Requirement: Preview and versioning
系统 MUST 在正式写入前生成预览；相同来源和统计日期的完全相同文件 MUST 判为重复；内容不同的文件 MUST 作为修正版预览新增、删除和变更，确认后成为该日期当前版本并保留旧批次。

#### Scenario: Same file is uploaded again
- **WHEN** 管理员再次上传相同来源的相同文件
- **THEN** 系统提示重复上传，不写入业务快照，并在批次记录保留该尝试

#### Scenario: Revised file is confirmed
- **WHEN** 管理员确认同一统计日期的不同内容文件
- **THEN** 新批次成为当前版本，旧批次仍可追溯且不再作为默认版本

### Requirement: Import validation outcomes
系统 MUST 将无法解析文件、缺少统计日期、缺少必填字段和快照内业务键重复作为阻断错误；非必填字段为空和归属匹配异常作为提示，允许管理员在没有阻断错误时确认导入。

#### Scenario: Blocking error exists
- **WHEN** 预览含有至少一项阻断错误
- **THEN** 确认操作不可用并显示工作表、原始行号和原因

#### Scenario: Warning only
- **WHEN** 预览只有非必填空值或归属匹配异常
- **THEN** 管理员可以确认导入并看到提示数量

### Requirement: Batch traceability
系统 MUST 记录每次上传的来源、文件名、文件指纹、统计日期、上传人、上传时间、状态、当前版本标记、工作表、原始行号和行级校验结果；失败或重复批次不得写入正式业务快照。

#### Scenario: Review a completed batch
- **WHEN** 管理员打开导入批次详情
- **THEN** 系统显示汇总数量并可定位到工作表和原始行号

#### Scenario: Failed confirmation
- **WHEN** 正式批量写入失败
- **THEN** 系统回滚该批次业务写入并保留失败状态和原因

### Requirement: Original workbook retention
系统 MUST 在每次完整接收上传文件后保留与上传尝试批次关联的原始工作簿字节，不得因导入成功、校验失败、重复识别或修正版替换而自动删除原文件；管理员明确确认永久删除相关业务记录及整份工作簿时例外。可按相同 SHA-256 指纹复用存储对象，但每个上传批次 MUST 可追溯到完全一致的原始字节，除非该工作簿已被管理员永久销毁。只有管理员 MUST 能下载批次原始工作簿，且原文件存储 MUST 纳入备份与恢复。

#### Scenario: Retrieve original workbook
- **WHEN** 管理员从导入批次详情下载原始工作簿
- **THEN** 系统返回与上传时字节一致且 SHA-256 匹配的文件

#### Scenario: Retain rejected or duplicate workbook
- **WHEN** 上传文件被判定为校验失败或重复
- **THEN** 批次仍保留对原始工作簿的引用供管理员追溯，且不创建正式业务快照

#### Scenario: Prevent manager access to original workbook
- **WHEN** 客户经理请求下载某批次的原始工作簿
- **THEN** 系统拒绝访问，不泄露文件内容或存储位置

#### Scenario: Explicitly destroy an original workbook
- **WHEN** 管理员在永久删除预览中选择删除原始工作簿并完成二次确认
- **THEN** 系统销毁整份关联文件，清除所有批次对其引用，并显示这些批次的原件不可下载