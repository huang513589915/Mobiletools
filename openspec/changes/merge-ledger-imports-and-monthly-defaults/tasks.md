# Tasks

## 1. Composed dataset storage and migration

- [x] 1.1 Add immutable dataset-version and effective-row tables, version-membership mapping, statistics-period locking, and retained-row summary support; verify schema migration and constraints on an isolated MySQL database.
- [x] 1.2 Backfill one composed dataset version for each existing current source/statistics key from its active batch, preserving source-row, assignment, and work-order references; verify row counts and business keys match the pre-migration current batches.
- [x] 1.3 Add migration coverage for multiple current source batches contributing rows to one dataset version, unique identity per version, and independent arrears/line periods; run database migration tests.

## 2. Merge preview and confirmation

- [x] 2.1 Compare candidate rows with the target period's current dataset and classify added, updated, unchanged, and retained rows; verify import-service tests for new periods, partial uploads, and each classification.
- [x] 2.2 Implement present-header overlay, blank-cell clearing, absent-optional-column preservation, duplicate-header identity, and effective-field provenance; verify parser and merge tests include empty values and heterogeneous workbook headers.
- [x] 2.3 Determine duplicate status from semantic no-op comparison rather than file hash alone; verify an unchanged file is blocked while a previously confirmed file that changes current values reaches preview.
- [x] 2.4 Confirm a merge atomically under a statistics-period lock, create an immutable dataset version, preserve untouched membership rows, refresh stale previews without auto-confirming, and roll back on failure; verify transaction and concurrent-confirmation tests.
- [x] 2.5 Store before/after field differences including explicit clears, four preview counts, and each row's latest batch/sheet/source row; verify batch detail and preview API tests, including retained-row counts.

## 3. Ownership and work-order continuity

- [x] 3.1 Preserve current effective manager assignment when an updated row's Excel manager name is unchanged, and rematch only when that name changes; verify manual assignment, exact matching, unmatched exception, and next-period isolation tests.
- [x] 3.2 Reuse arrears and line work orders for updated, unchanged, and retained identities while preserving statuses, follow-up records, last-handler data, and histories; initialize only newly added identities; verify work-order transaction tests.

## 4. Current-data queries and exports

- [x] 4.1 Refactor arrears and line list, details, filters, pagination, and access-scope queries to read the composed current dataset membership; verify API tests for multi-batch periods and per-row manager visibility.
- [x] 4.2 Update original-field exports to read effective values and produce a deterministic union of columns across selected records, preserving duplicate headers and source provenance; verify exported workbook headers and representative row values.
- [x] 4.3 Update import-batch list/detail indicators to show how many rows from each batch contribute to current data and distinguish all, partial, and no contribution; verify batch-query and API response tests.

## 5. Frontend and user-facing guidance

- [x] 5.1 Update the import preview to show added, updated, unchanged, and retained counts plus field-level old/new values and visibly marked clears; verify frontend component tests for empty values and semantic duplicate previews.
- [x] 5.2 Default the arrears filter to the current business month and the line filter to the current month's inclusive date range, retaining an empty result when no current-month data exists; verify frontend/API tests across month boundaries and date overrides.
- [x] 5.3 Update the import-batch page and row details to explain batch contribution and latest row provenance; update README/requirements guidance for merge, duplicate detection, current-month defaults, and out-of-scope deletion/restore; verify build and documented behavior.

## 6. Integrated acceptance

- [x] 6.1 Run migration and backend integration tests on an isolated database with existing active batches; verify backfilled record counts, independent sources, assignment rules, and work-order continuity.
- [x] 6.2 Exercise a partial arrears merge and a partial line merge through preview, confirm, list, detail, filters, and exports; verify updated/new/unchanged/retained counts and that omitted rows remain visible.
- [x] 6.3 Verify a no-op reupload is rejected, a changed historical-period file merges into only its own period, current-month empty states do not fall back, and stale previews require a fresh confirmation; record results in acceptance notes.



