# data-ingestion

当前模块负责两套独立 Excel 台账的上传、预检、预览、确认、取消、版本追踪、原始工作簿留存和归属匹配异常处理：

- 集团账户欠费明细月表；
- 专线到期需续签明细表。

两种来源分开存储和授权，不在此模块建立跨表业务关联。详细需求见根目录 `REQUIREMENTS.md` 和 `docs/independent-excel-ledgers/project-design.md`。
