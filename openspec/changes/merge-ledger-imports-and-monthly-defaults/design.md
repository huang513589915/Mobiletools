# Design

## Context

See `proposal.md` for motivation and `specs/` for observable behavior. The current implementation stores parsed rows and snapshots per import batch, while `excel_current_version` points to one batch per source and statistics key. Ledger queries therefore read only that batch. Snapshot records also carry manager ownership and are referenced by work orders, so replacing the single-batch pointer with a composed dataset must preserve record identity, ownership, and work-order history.

## Goals / Non-Goals

**Goals:**

- Represent a current statistics period as a stable, auditable set of rows whose latest changes may come from different batches.
- Apply row-level merges atomically while preserving immutable upload attempts and prior composed versions.
- Keep current ledger queries, assignment authorization, details, exports, and work orders consistent with the composed version.
- Make semantic differences independent of Excel formatting or row order.

**Non-Goals:**

- Provide a user-facing historical-version restore, batch rollback, permanent deletion, or individual-row deletion workflow.
- Change the required-field contract, the two independent source ledgers, work-order statuses, or risk rules.

## Decisions

### 1. Separate immutable upload batches from composed statistics-period versions

Add an immutable dataset-version record per confirmed merge, an immutable effective-row record per added or updated business identity, and a membership table mapping each dataset version to one effective row per business identity. The effective-row record references the latest snapshot/source row and stores the merged field payload and field provenance. Keep one current-version pointer for each `(source_type, statistics_key)` and serialize confirmations with a stable period lock row. A confirmation copies the previous membership map, replaces or inserts only rows classified as updated or added, then atomically moves the current pointer. Unchanged and retained rows continue to reference their existing effective-row records, snapshot records, and source batches. Earlier dataset-version membership remains for audit, while this change does not expose a restore action.

This avoids mutating source batches or copying every raw row into a new batch. An alternative that keeps the current pointer on one batch cannot represent a dataset assembled from partial uploads. A mutable current-row table alone would be smaller, but would not preserve a complete prior composed state for audit or stale-preview detection.

The migration creates one initial composed version for each currently active source/statistics key and maps every row in its active batch. Existing historical batches remain untouched and are not merged into that current version.

### 2. Store effective row values separately from source upload rows

The candidate `excel_import_row` remains the immutable representation of the uploaded row. Each immutable effective-row record stores the merged field payload, latest snapshot/source-row reference, and field provenance where values are inherited from an earlier row because an optional header was absent. Dataset versions store only membership references to effective-row records. For a changed row, overlay values for every header present in the uploaded worksheet onto the previous effective field map; a present but empty cell replaces the old value with empty. A whole optional header absent from the workbook leaves its previous value and provenance unchanged. Empty headers remain ignored, and repeated headers are distinguished by their occurrence within the ordered header list.

When a row has no effective value change, keep its existing effective-row record, snapshot record, and ownership. When a row changes, create a new snapshot record tied to the incoming import row and a new effective-row record containing the merged payload. This keeps row-level source location available without losing values supplied by an older workbook. Exports over a composed dataset use the ordered union of effective headers from selected rows; duplicate headers use stable occurrence labels so their values remain distinct.

### 3. Compare semantic source values, not workbook bytes

Use SHA-256 to identify and retrieve original workbook bytes, not as the final business duplicate decision. For each candidate identity, compare its effective field values to the current membership after applying the present-header/absent-header rules. Normalize typed numbers and dates consistently with the parser; ignore styling, cell formatting, row order, and column order. The raw workbook remains unchanged for exact audit.

Classify each upload as ADDED, UPDATED, UNCHANGED, or RETAINED. `RETAINED` means a current identity was absent from the candidate file and therefore remains untouched. A batch is a semantic duplicate only if it contains no added or updated rows. Extend persisted batch summaries and diff rows to represent retained counts and field-level before/after values, including explicit empty new values. The existing duplicate batch status remains available for a no-op upload.

Persist the dataset version used to create a preview. At confirmation, lock the statistics-period anchor and compare that version with the current pointer. If it changed, recompute the preview against the latest version and return it for a fresh explicit confirmation; do not apply stale differences.

### 4. Preserve assignment and work-order state by business identity

For an unchanged row, reuse the prior snapshot record, effective manager, and assignment-exception state. For a changed row whose Excel manager name is unchanged, copy the current effective manager so a manual assignment is not lost. If the Excel manager name changed, match the submitted name exactly and record a new exception when it does not resolve to exactly one enabled account. New identities use the submitted Excel name for matching. A new statistics period is independent and never inherits manual ownership from another period.

When a changed arrears or line row receives a new snapshot record, update the existing work-order pointer for its business identity in the same transaction. Keep the arrears handling status, last handler, follow-up entries, and status history; keep the line renewal status, last handler, and status history. Create the existing initial work-order state only for a genuinely new identity. Omitted rows remain members of the composed version and keep their current work orders.

### 5. Query composed memberships and expose batch contribution

Change ledger list, detail, export, date-option, assignment-exception, and work-order joins to start from the current dataset version membership rather than assuming one current batch. Apply manager visibility to the effective ownership associated with each membership row. Details show the latest row update source; retained rows continue showing their earlier source. The import-batch list should replace the singular “current batch” interpretation with whether and how many rows from a batch currently contribute to the composed dataset. Current-month defaults apply to the ledger pages; explicitly requested report date ranges remain unchanged.

For default filters, use the configured application business timezone:欠费 defaults to the current `yyyyMM`;专线 defaults to the inclusive first and last calendar dates of the current month. An empty current-month result is valid and must not trigger a latest-available fallback. Explicit user date ranges remain unchanged.

### 6. Keep merge confirmation transactional and source-local

During confirmation, lock the source/statistics-period anchor, validate the preview base version, insert the new composed version and its changed rows, update only affected work-order pointers and assignment records, then switch the current pointer and mark the batch confirmed in one transaction. A rollback leaves the old current dataset intact. Arrears and line-expiry period locks and versions remain independent.

## Risks / Trade-offs

- [Dataset-version membership grows with each confirmed merge] → Store immutable row payloads once per actual add/update and let new dataset versions copy only membership references; index by version and identity, and measure growth using representative upload volumes before choosing compaction.
- [Exports may combine rows with different optional headers] → Use a deterministic ordered union of effective headers and keep absent values blank; verify duplicate-header handling and workbook column limits.
- [Assignment changes may be subtle during row merges] → Cover unchanged-name manual assignment preservation, changed-name exact matching, and new-period isolation in transaction and acceptance tests.
- [Two admins may confirm previews against the same base version] → Serialize by source/statistics key and refresh stale previews instead of applying them.
- [Server timezone may differ from the business timezone] → Use the configured application business timezone for month boundaries and test month rollover explicitly.

## Migration Plan

1. Back up the database and deploy additive tables/columns for dataset versions, version membership, effective row payload/provenance, retained-row counts, and period locking.
2. Backfill one composed version per existing `excel_current_version` entry from its active batch rows; preserve all existing batches, raw workbooks, assignment rows, and work orders.
3. Deploy backend writes and queries against composed versions, including merge preview refresh and atomic confirmation. Keep old historical batches accessible through batch detail and raw-workbook download.
4. Deploy the frontend preview classifications, field differences, batch contribution indicators, and current-month filters.
5. Verify record counts and business keys for every migrated current period, then test partial merges, semantic duplicates, manual ownership, work-order history, exports, empty current months, and month-boundary behavior in a separate database before production use.
6. If rollback is required before any merge is confirmed, roll back the application while preserving the additive schema. After composed versions exist, do not point the application back to the old single-batch query; use a forward-compatible deployment rollback or restore the database backup under an explicit recovery plan.
