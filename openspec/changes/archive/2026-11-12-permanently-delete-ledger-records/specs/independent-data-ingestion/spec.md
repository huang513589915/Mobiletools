# Spec Delta

## MODIFIED Requirements

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
