# Tasks

## 1. Database and original workbook cleanup

- [x] 1.1 Add V8 nullable batch file references, erased-row aggregates, and a retryable source-file purge queue; update fresh schema initialization and verify the full V1–V8 migration chain plus FK constraints in isolated MySQL tests.
- [x] 1.2 Make batch details and original downloads handle destroyed files; implement idempotent post-commit file cleanup with startup/scheduled retries and sanitized failure state, verified by service and API tests.

## 2. Safe deletion preview and transaction

- [x] 2.1 Implement administrator-only, short-lived, one-use previews bound to selected current snapshot IDs and version state; return per-period history/work-order counts and all shared-workbook impacts, verified for expired, reused, stale, invalid, and unauthorized previews.
- [x] 2.2 Implement transactional hard deletion across all batches and versions for selected source/period/business keys, including row diffs, assignments, snapshots, orders and histories; preserve nonselected keys/periods and write identifier-free audit records, verified by MySQL-backed tests for both sources and rollback.
- [x] 2.3 Implement explicit whole-file deletion confirmation, clear references across every batch sharing a file, enqueue post-commit file purges, and report pending retries without claiming completion; verify shared-file cross-source/date impacts and retry behavior.
- [x] 2.4 Expose deletion preview and confirmation APIs to administrators only and verify request validation, response counts, stale confirmation rejection, and manager denial through controller integration tests.

## 3. Administrator interface and documentation

- [x] 3.1 Add admin-only row selection and permanent-delete impact dialogs to both ledger pages, including the opt-in whole-workbook checkbox, affected-record warning, typed confirmation and post-delete refresh; verify multi-select and pending-file states with component tests.
- [x] 3.2 Show erased-row and original-file deletion status in import batch details; verify batch navigation and downloads behave correctly for retained versus destroyed workbooks.
- [x] 3.3 Update current requirements and operating docs with deletion scope, migration V8, backup caveat and restore implications; verify OpenSpec change validation and documented migration order.

## 4. Integration verification

- [x] 4.1 Run isolated backend and frontend suites plus production build; verify no old records remain in targeted keys while unrelated ledger data, work orders and original workbook files remain unchanged.
