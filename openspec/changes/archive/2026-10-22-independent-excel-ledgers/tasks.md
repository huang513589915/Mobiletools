# Tasks

## 1. Freeze contracts and prepare the data model

- [x] 1.1 Freeze the two real workbook header maps, required fields, date/number parsing rules, duplicate-header position rules, and the policy to ignore empty-header columns; verify the mapping document matches both supplied `.xlsx` files.
- [x] 1.2 Document the replacement strategy for the old sample business tables, including database and original-workbook storage backup/rollback; verify sample business data is not migrated and no destructive reset runs without a backup.
- [x] 1.3 Design and review separate batch/current-version, immutable original-workbook storage, arrears-snapshot, line-expiry-snapshot, raw-field, exception, effective-assignment, assignment-history, and manager-account schema changes; verify keys and indexes enforce source/date/identity uniqueness.
- [x] 1.4 Add migration tests or database checks for `账户ID + 日期`, `成员服务号码 + 统计日期`, current-version uniqueness, and source-specific assignment isolation; verify duplicate inserts are rejected without cross-source coupling.

## 2. Implement independent import and batch traceability

- [x] 2.1 Implement source-aware workbook parsing and precheck for required headers, required values, date/number formats, empty headers, duplicate headers, and row locations; verify blocking errors identify sheet and original row number.
- [x] 2.2 Persist each fully received original workbook unchanged before parsing and link it to its upload-attempt batch; implement file fingerprinting, duplicate-upload detection, preview persistence, and failed/repeated batch records; verify exact bytes/hash remain retrievable for successful, failed, and duplicate attempts without creating extra formal snapshot rows.
- [x] 2.3 Implement source/date revision comparison for added, removed, changed, and unchanged identities; verify a confirmed revision becomes current while the old batch remains queryable.
- [x] 2.4 Implement atomic confirmation and rollback for each source, including raw non-empty-header field persistence and row-level counters; verify a failed write leaves no partial formal snapshot.
- [x] 2.5 Implement administrator batch list and detail views for source, date, file, uploader, status, current/history marker, warnings, errors, sheets, and row locations, with original-workbook download; verify failed and duplicate attempts are visible but excluded from business data and customer managers cannot access original files.
- [x] 2.6 Add parser, validation, revision, duplicate, and transaction tests for both real workbook shapes; verify the backend test suite passes for warning-only and blocking-error cases.

## 3. Implement manager accounts, matching, and exceptions

- [x] 3.1 Extend administrator account maintenance with real name, role, enabled state, pinyin default username, and unique suffix handling; verify duplicate usernames are rejected and disabled accounts cannot log in.
- [x] 3.2 Implement exact `real_name` matching per imported row, including zero-match and multiple-match exceptions without inheriting prior snapshot assignments; verify the raw Excel name remains unchanged.
- [x] 3.3 Implement exception list/detail queries with source, date, group, Excel manager, reason, batch, sheet, row number, and processing state; verify records are shown individually rather than merged by group.
- [x] 3.4 Implement current-page selection, all-filtered-results selection, single-source bulk assignment, confirmation with selected count/target manager, and reassignment of processed rows; verify a mixed-source selection cannot be committed as one operation.
- [x] 3.5 Implement assignment history and source/date/row isolation; verify changing one selected row does not alter another row, source type, historical date, or future import defaults.
- [x] 3.6 Add authorization tests for administrators, assigned managers, unassigned exceptions, historical snapshots, reassignment access changes, and independent arrears/line visibility; verify server-side predicates prevent out-of-scope reads.

## 4. Implement arrears ledger page and export

- [x] 4.1 Implement the arrears query API with latest-current-version default, historical date selection, filters for group/account/product/effective manager, and default group/account ordering; verify all predicates apply with pagination.
- [x] 4.2 Implement the arrears list showing date, group, Excel manager, effective manager, account, carrying product, ending arrears, and aging; verify the page defaults to 20 rows and supports 50/100 while retaining filters.
- [x] 4.3 Implement the arrears detail drawer with all non-empty-header raw fields in source order, stop-service field in detail only, batch/sheet/row metadata, both manager fields, and empty/zero rendering; verify no raw field is silently omitted.
- [x] 4.4 Implement permission-aware arrears export over the full filtered result, including all non-empty-header fields plus effective manager and excluding remaining days; verify manager exports contain only rows currently assigned to that manager.
- [x] 4.5 Add arrears API, UI, detail, history, pagination, and export tests; verify build and endpoint responses match the arrears capability specification.

## 5. Implement line-expiry ledger page and export

- [x] 5.1 Implement the line-expiry query API with latest-current-version default, historical date selection, group/product/effective-manager filters, inclusive remaining-day bounds, and required ordering; verify negative remaining days are accepted.
- [x] 5.2 Implement read-time remaining-days calculation as `计费截止日期 - current system date`; verify a date change changes the displayed value without reimporting and contract expiry date is not used in calculation.
- [x] 5.3 Implement the line-expiry list showing both manager fields, cutoff date, and dynamic remaining days; verify the page defaults to 20 rows, supports 50/100, and sorts earliest/overdue rows first.
- [x] 5.4 Implement the line detail drawer with every non-empty-header field, member status and contract expiry date in detail only, batch/sheet/row metadata, both manager fields, and empty/zero rendering; verify source column order is preserved.
- [x] 5.5 Implement permission-aware line export over the full filtered result, including all non-empty-header fields plus effective manager and excluding computed remaining days; verify the exported rows equal the server-side filtered result.
- [x] 5.6 Add line API, dynamic-date, UI, detail, history, pagination, and export tests; verify frontend build and backend tests pass.

## 6. Integrate navigation, documentation, and acceptance checks

- [x] 6.1 Add independent navigation entries for “欠费明细”, “专线到期明细”, “归属匹配异常”, “导入批次”, and administrator account maintenance; verify customer managers cannot access administrator-only routes.
- [x] 6.2 Add user-facing empty, loading, validation, duplicate, revision, warning, permission, and export-failure states; verify no page exposes hidden raw fields through an unprotected endpoint.
- [x] 6.3 Create `docs/independent-excel-ledgers/` technical documentation covering requirements, real workbook field maps, database design, API contracts, frontend presentation, business rules, acceptance criteria, and migration/rollback; verify links point to the new design set and the archived directory remains unchanged.
- [x] 6.4 Run the complete acceptance pass using both supplied workbooks: upload, warning-only import, duplicate detection, revision preview/confirmation, historical switching, exception assignment/reassignment, manager visibility, admin visibility, detail completeness, and filtered export; verify results against the acceptance checklist before deployment.

## Workflow follow-up

- After implementation and review, run the OpenSpec apply workflow and update task checkboxes as work lands.
- Archive the change only after implementation, tests, documentation, and acceptance evidence are complete.
