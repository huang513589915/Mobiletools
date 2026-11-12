# Design

## Context

See `proposal.md` for the motivation and scope. The repository is a local React/TypeScript/Vite frontend with a Java 17 Spring Boot backend, Spring JDBC, MySQL, and Apache POI. The current schema and older planning change model a broader customer/risk/task workflow; this design treats the two real Excel sources as the new source of truth for this change and does not modify runtime code while the plan is being authored.

## Goals / Non-Goals

**Goals:**

- Keep arrears and line-expiry data independent in storage, import, authorization, history, and export.
- Normalize the fields needed for filtering and sorting while retaining every non-empty-header source field in original column order.
- Make a confirmed batch and its current-version pointer auditable and recoverable.
- Make effective manager assignment row-specific, snapshot-specific, source-specific, and auditable.
- Make list, detail, history, export, and exception behavior consistent for administrators and customer managers.

**Non-Goals:**

- Risk thresholds, risk levels, stop-service decisions, tasks, reminders, renewal-processing status, or automated follow-up.
- A business relationship between an arrears account and a line instance; `集团编码` is retained as a search/display value, not a cross-table foreign key.
- Reusing the old sample business data or silently inheriting old manager assignments into a new import.
- Code, SQL, migration, or configuration changes in this planning change.

## Decisions

### 1. Separate source pipelines and tables

Use a source discriminator only for shared batch/audit infrastructure. Store normalized arrears rows and line-expiry rows in separate tables with separate read services and query contracts. Do not create a foreign key between them. This makes the two pages independently replaceable and prevents an account-to-line relationship from being inferred from the shared group code.

### 2. Snapshot and batch model

Each upload creates an immutable batch candidate with:

- source type (`ARREARS` or `LINE_EXPIRY`);
- Excel statistics date;
- original workbook bytes stored unchanged before parsing, with a batch reference and SHA-256 fingerprint;
- file name, sheet metadata, uploader and timestamps;
- preview/confirmation status and row counters;
- a current-version marker scoped to source type plus statistics date.

Every fully received upload attempt retains an immutable original workbook associated with its batch, whether it is later confirmed, rejected during validation, or identified as a duplicate. Duplicate binaries may be physically deduplicated by fingerprint as long as each attempt remains linked to the exact bytes and hash. Original workbooks do not expire or get automatically deleted. Only administrators can download them from batch details; customer managers cannot retrieve them. The binary storage must be included in backup and recovery alongside the database.

After confirmation, the batch rows remain immutable source snapshots. A source/date current-version pointer identifies the version used by default list queries. A later confirmed revision moves that pointer and marks the previous batch historical; it does not delete the old rows. Duplicate and validation-failed attempts remain batch records but never become current versions.

### 3. Normalized query fields plus raw row payload

Each source table stores its fixed business/query fields in typed columns and stores the ordered non-empty-header columns in a raw-field payload with column-position metadata. The payload preserves original labels, values, sheet name, and source row number. Empty-header columns are discarded during precheck. Duplicate labels use their column positions as stable keys. This avoids schema changes for every new descriptive Excel column while keeping list queries indexable.

The normalized identity fields are:

- arrears: `账户ID + 日期` within a source/date snapshot;
- line expiry: `成员服务号码 + 统计日期` within a source/date snapshot.

`集团编码` is indexed in both tables for filtering but is not a relational link between them. Values such as “是否达到停机标准”, “成员状态”, and “合同到期日” remain raw/detail fields where the page contract places them.

The additive MySQL 8 schema is `db/migrations/V2__independent_excel_ledgers.sql`. It uses `excel_source_file` for content-addressed source bytes, one `excel_import_batch` row per fully received upload attempt, and `excel_current_version` keyed by `(source_type, statistics_key)` with a composite foreign key to the exact batch/source/date. Arrears uses a six-digit `yyyyMM` key; line expiry uses an eight-digit `yyyyMMdd` key. `excel_import_row` retains preview rows and raw fields as an ordered JSON array with column positions; `excel_revision_diff` retains added, removed, changed, and unchanged identities. Confirmed rows use a shared `excel_snapshot_record` for Excel manager name and current effective manager, plus separate normalized `arrears_snapshot` and `line_expiry_snapshot` tables. Each normalized table has a unique `(batch_id, source identity)` key; fixed source checks and composite foreign keys prevent cross-source writes. There is no arrears-to-line foreign key. Exceptions and assignment history reference only the shared snapshot record, so assignments remain row-, source-, and date-specific. The migration adds an exact-match account index and binary collation to `app_user.real_name`; duplicate real names remain allowed.

The migration only adds the new ledger structures and matching index. It does not copy rows from the old customer, service, arrears, expiry, risk, or task tables. Constraint verification is kept in `db/tests/V2_independent_excel_ledgers_constraints.sql`, which refuses to run unless the selected schema is `arrears_management_test` and rolls back all inserted fixture rows.

### 4. Preview and revision comparison

Precheck parses the workbook without writing formal snapshot rows. It validates the source-specific required headers, required row values, date and numeric formats, and duplicate snapshot identities. If a source/date current version exists, the preview compares candidate rows by the source identity and classifies added, removed, changed, and unchanged rows. The confirmation endpoint revalidates the candidate status and writes the entire batch atomically.

The same file fingerprint within the same source is a duplicate. A different fingerprint for the same source/date is a revision candidate. Non-blocking warnings, including manager matching failures, do not prevent confirmation; blocking validation errors do.

### 5. Effective manager assignment

At import time, match the Excel manager name exactly against account `real_name` values. One match sets the row's initial effective manager; zero or multiple matches leave it unassigned and create an exception record. The import never looks up or copies a prior snapshot's manual assignment.

Keep both `excel_manager_name` and `effective_manager_user_id` on each snapshot row. A separate assignment-history table records row id, source type, snapshot/batch, previous effective user, new effective user, operator, time, and reason/status. Bulk assignment accepts selected row IDs and a target user in one transaction. It can span the currently filtered result set, but each selected row remains independently auditable and can later be reassigned.

Authorization is enforced in backend queries: administrators can see and operate on all rows; a customer manager can see only rows whose effective manager is that user. A null effective manager is visible only to administrators and is rendered as `未分配（归属匹配异常）`. Arrears and line-expiry authorization queries never use the other source's assignment.

### 6. Page and query contracts

Provide separate navigation entries and read APIs for the two ledgers. Both pages use the latest confirmed current version by default and let users select historical statistics dates. Page state includes date, filters, page size, and page number.

Arrears list fields are date, group code, group name, Excel manager, effective manager, account ID, carrying product name, ending arrears amount, and aging. Filters are group code/name, account ID, carrying product, effective manager, and date. Default ordering is group code then account ID.

Line-expiry list fields are statistics date, group code, group name, product code, product name, member service number, Excel manager, effective manager, billing cutoff date, and dynamically calculated remaining days. Remaining days is `billing_cutoff_date - current system date`, can be negative, and is recalculated at read time for historical rows. Filters include group, product code/name, effective manager, date, and inclusive remaining-day minimum/maximum. Default ordering is remaining days ascending, then group code and member service number. Contract expiry date and member status are detail-only.

Both pages use 20 rows by default with 50 and 100 options, preserve filters while paging, open an in-page detail drawer, and show all non-empty-header raw fields in original column order. Empty values render as `—`; numeric zero renders as `0`. Export operates on the full filtered result, not the current page, includes all non-empty-header source fields plus effective manager, and excludes the computed remaining-days field.

### 7. Admin workflows and account identity

The administrator-only batch page lists upload attempts, source, statistics date, file, uploader, time, status, current/history marker, and warning/error counts. Batch details expose validation, revision diff, sheet, and row locations, and provide administrator-only download of the immutable original workbook. The administrator-only exception page shows one source row per exception, supports current-page select-all and all-filtered-results selection, requires confirmation with selected count and target manager, and supports reassignment of processed rows. Each bulk assignment is restricted to one data source. The administrator-only account page creates, queries, and disables accounts one at a time.

Usernames default to name pinyin and must be unique; administrators add a suffix on pinyin collisions. `real_name` remains the matching/display value and may be duplicated, which deliberately produces an import exception rather than an automatic choice.

### 8. API shape

The eventual API should separate command and query responsibilities while keeping source boundaries visible. Planned groups are:

- `/api/imports/precheck`, `/api/imports/{id}/preview`, `/api/imports/{id}/confirm`, `/api/import-batches`;
- `/api/import-batches/{id}/source-file` for administrator-only download of the original workbook;
- `/api/arrears` and `/api/arrears/{id}`;
- `/api/line-expiry` and `/api/line-expiry/{id}`;
- `/api/assignment-exceptions` and a bulk assignment/reassignment command;
- `/api/users` for administrator account maintenance;
- export endpoints that reuse the same server-side filters and permission predicates as list queries.

Exact paths and DTO names remain implementation tasks; the externally visible behaviors are defined in the capability specs.

## Risks / Trade-offs

- [Raw-field payloads are less convenient for ad-hoc SQL reporting] -> keep all list/filter fields normalized and index the source/date/identity/manager columns; use raw payloads for detail and export.
- [A revised file can remove rows from the current view] -> compare by stable source identity, show deletions in preview, retain the old batch, and never physically delete historical rows.
- [Duplicate real names prevent automatic assignment] -> treat ambiguity as a visible exception and provide row-level bulk assignment with a full audit trail.
- [Dynamic remaining days changes without a new import] -> calculate only at read/export time from the current system date and the stored billing cutoff date.
- [Old schema and planning documents conflict with this model] -> do not reuse their business-table assumptions; implement against this change's contracts after a deliberate migration decision.
- [Large filtered exports can consume memory] -> implement server-side streaming or bounded batch generation while applying the same authorization predicate as the list query.
- [Retaining source workbooks increases storage use] -> preserve exact uploaded bytes in protected storage, allow content-addressed deduplication by SHA-256, and include that storage in backup/recovery and capacity monitoring.

## Migration Plan

This change is planning-only. Before implementation, freeze the two workbook contracts against the real files, prepare the replacement schema, and back up both the database and original-workbook storage for rollback. The old self-designed sample business data does not need to be migrated. During implementation, retain the original workbook before parsing, build precheck and batch traceability before formal snapshot writes, then add the two ledger queries, assignment exceptions, permissions, and exports. Validate exact file recovery and hashes, along with import behavior, against a temporary environment before replacing local runtime data. No migration or data deletion is executed by this planning change.

## Open Questions

Original workbook retention is required. The exact storage backend can be selected during implementation, but it must preserve the uploaded bytes, bind them to each batch attempt, support administrator-only retrieval, and be covered by backup and recovery.
