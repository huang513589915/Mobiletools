# Proposal

## Why

The current planning change models a broader five-workbook risk and task workflow, while the actual first release is centered on two independent Excel sources: a monthly group-account arrears snapshot and a line-expiry snapshot. The real files, row identities, versioning rules, manager matching behavior, and page presentation now need a coherent contract before any database or product code is changed.

## What Changes

- Define two independent data domains and storage flows: arrears snapshots and line-expiry snapshots.
- Define the required fields, row identities, raw-field retention, empty-header handling, validation, duplicate detection, revision preview, current-version selection, and batch traceability for both Excel types.
- Retain every fully received original workbook as immutable source material linked to its upload-attempt batch, including confirmed, failed, and duplicate attempts; restrict retrieval to administrators.
- Add administrator upload, preview, confirmation, history, export, and import-batch review behavior without linking arrears accounts to line instances.
- Define exact-name manager matching using `real_name`, row-level effective assignment, abnormal-match handling, bulk selection, reassignment, and assignment history.
- Define administrator-maintained accounts, pinyin-based unique usernames, duplicate-name behavior, role permissions, and server-side data visibility.
- Define independent arrears and line-expiry list pages, filters, sorting, pagination, detail drawers, history-date switching, and permission-aware export.
- Keep all non-empty Excel fields available in details and export; ignore columns with empty headers.
- Keep risk rules, risk levels, tasks, renewal-processing status, and automated reminders out of this scope.

## Capabilities

### New Capabilities

- `independent-data-ingestion`: Import, validate, preview, version, confirm, and trace the two independent Excel sources.
- `arrears-ledger`: Store and present monthly arrears snapshots with filtering, details, history, and permission-aware export.
- `line-expiry-ledger`: Store and present line-expiry snapshots with dynamic remaining days, filtering, details, history, and export.
- `assignment-exception-management`: Match manager names, expose failures, support row-level bulk assignment and reassignment, and retain audit history.
- `manager-account-management`: Let administrators maintain manager accounts and enforce role and effective-assignment visibility.

### Modified Capabilities

- None. The repository currently has no durable capabilities under `openspec/specs/`; the completed older planning change is not used as the source of truth for this scope.

## Impact

- Planning impact: replaces the older five-workbook/risk-task assumptions for this scope with two independent ledgers and explicit import contracts.
- Eventual backend impact: new batch, original-workbook storage, snapshot, raw-field, effective-assignment, assignment-history, and account/permission models; upload, preview, list, detail, source-file retrieval, exception, reassignment, and export APIs.
- Eventual frontend impact: two independent ledger pages, upload/preview and batch history views, assignment-exception page, account administration, detail drawers, filters, pagination, and exports.
- No product code, database schema, migration, or runtime configuration is changed while this planning change is being authored.
