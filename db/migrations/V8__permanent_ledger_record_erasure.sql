USE arrears_management;
SET NAMES utf8mb4;

-- A destroyed workbook remains represented by batch metadata only. Nullable
-- source_file_id distinguishes this from batches whose original is retained.
ALTER TABLE excel_import_batch
    MODIFY COLUMN source_file_id BIGINT NULL,
    ADD COLUMN erased_rows INT NOT NULL DEFAULT 0 AFTER retained_rows,
    ADD COLUMN original_file_purge_operation_id CHAR(36) NULL AFTER erased_rows,
    ADD COLUMN original_file_purge_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' AFTER original_file_purge_operation_id;

-- This queue temporarily stores only a validated content-addressed storage key
-- so physical deletion can be retried after the database transaction commits.
-- Rows are removed immediately after the file is absent from disk.
CREATE TABLE excel_original_file_purge_queue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_id CHAR(36) NOT NULL,
    storage_key VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(60) NULL,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_purge_storage_key (storage_key),
    KEY idx_excel_purge_retry (next_attempt_at, id),
    KEY idx_excel_purge_operation (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
