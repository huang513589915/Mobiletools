USE arrears_management;
SET NAMES utf8mb4;

-- Additive schema for the independent arrears and line-expiry ledgers.
-- The existing sample business tables and rows are intentionally untouched.

DROP PROCEDURE IF EXISTS add_index_if_missing;
DELIMITER //
CREATE PROCEDURE add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_columns VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD INDEX `', p_index_name, '` (', p_index_columns, ')'
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL add_index_if_missing('app_user', 'idx_app_user_manager_match', '`role`, `enabled`, `real_name`, `id`');

ALTER TABLE app_user
    MODIFY COLUMN real_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

CREATE TABLE IF NOT EXISTS excel_source_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    byte_size BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_source_file_sha256 (sha256),
    UNIQUE KEY uk_excel_source_file_storage_key (storage_key),
    CONSTRAINT chk_excel_source_file_sha256 CHECK (sha256 REGEXP '^[0-9a-fA-F]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL,
    statistics_key VARCHAR(8) NULL,
    original_filename VARCHAR(255) NOT NULL,
    source_file_id BIGINT NOT NULL,
    uploaded_by_user_id BIGINT NOT NULL,
    batch_status VARCHAR(20) NOT NULL,
    revision_of_batch_id BIGINT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    valid_rows INT NOT NULL DEFAULT 0,
    warning_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    added_rows INT NOT NULL DEFAULT 0,
    removed_rows INT NOT NULL DEFAULT 0,
    changed_rows INT NOT NULL DEFAULT 0,
    unchanged_rows INT NOT NULL DEFAULT 0,
    validation_summary JSON NULL,
    uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_batch_composite_ref (id, source_type, statistics_key),
    UNIQUE KEY uk_excel_batch_id_source (id, source_type),
    KEY idx_excel_batch_source_date_status (source_type, statistics_key, batch_status, uploaded_at),
    KEY idx_excel_batch_source_file (source_file_id, uploaded_at),
    KEY idx_excel_batch_uploader (uploaded_by_user_id, uploaded_at),
    CONSTRAINT chk_excel_batch_source_type CHECK (source_type IN ('ARREARS', 'LINE_EXPIRY')),
    CONSTRAINT chk_excel_batch_status CHECK (batch_status IN ('RECEIVED', 'PRECHECKED', 'BLOCKED', 'DUPLICATE', 'CONFIRMED', 'FAILED')),
    CONSTRAINT chk_excel_batch_statistics_key CHECK (
        statistics_key IS NULL OR
        (source_type = 'ARREARS' AND statistics_key REGEXP '^[0-9]{6}$') OR
        (source_type = 'LINE_EXPIRY' AND statistics_key REGEXP '^[0-9]{8}$')
    ),
    CONSTRAINT fk_excel_batch_source_file FOREIGN KEY (source_file_id) REFERENCES excel_source_file(id),
    CONSTRAINT fk_excel_batch_uploader FOREIGN KEY (uploaded_by_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_excel_batch_revision FOREIGN KEY (revision_of_batch_id) REFERENCES excel_import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_current_version (
    source_type VARCHAR(20) NOT NULL,
    statistics_key VARCHAR(8) NOT NULL,
    batch_id BIGINT NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_type, statistics_key),
    UNIQUE KEY uk_excel_current_batch (batch_id),
    CONSTRAINT chk_excel_current_source_type CHECK (source_type IN ('ARREARS', 'LINE_EXPIRY')),
    CONSTRAINT chk_excel_current_statistics_key CHECK (
        (source_type = 'ARREARS' AND statistics_key REGEXP '^[0-9]{6}$') OR
        (source_type = 'LINE_EXPIRY' AND statistics_key REGEXP '^[0-9]{8}$')
    ),
    CONSTRAINT fk_excel_current_batch FOREIGN KEY (batch_id, source_type, statistics_key)
        REFERENCES excel_import_batch(id, source_type, statistics_key),
    CONSTRAINT fk_excel_current_changed_by FOREIGN KEY (changed_by_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_import_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    sheet_name VARCHAR(100) NOT NULL,
    source_row_number INT NOT NULL,
    identity_key VARCHAR(255) COLLATE utf8mb4_bin NULL,
    row_hash CHAR(64) NULL,
    validation_status VARCHAR(20) NOT NULL,
    raw_fields JSON NOT NULL,
    normalized_fields JSON NULL,
    errors JSON NULL,
    warnings JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_import_row_location (batch_id, sheet_name, source_row_number),
    UNIQUE KEY uk_excel_import_row_batch_id (batch_id, id),
    KEY idx_excel_import_row_identity (batch_id, identity_key),
    KEY idx_excel_import_row_status (batch_id, validation_status, source_row_number),
    CONSTRAINT chk_excel_import_row_status CHECK (validation_status IN ('VALID', 'WARNING', 'BLOCKED')),
    CONSTRAINT fk_excel_import_row_batch FOREIGN KEY (batch_id) REFERENCES excel_import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_revision_diff (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    identity_key VARCHAR(255) NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    previous_import_row_id BIGINT NULL,
    candidate_import_row_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_revision_identity (batch_id, identity_key),
    KEY idx_excel_revision_type (batch_id, change_type),
    CONSTRAINT chk_excel_revision_change_type CHECK (change_type IN ('ADDED', 'REMOVED', 'CHANGED', 'UNCHANGED')),
    CONSTRAINT fk_excel_revision_batch FOREIGN KEY (batch_id) REFERENCES excel_import_batch(id),
    CONSTRAINT fk_excel_revision_previous_row FOREIGN KEY (previous_import_row_id) REFERENCES excel_import_row(id),
    CONSTRAINT fk_excel_revision_candidate_row FOREIGN KEY (candidate_import_row_id) REFERENCES excel_import_row(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_snapshot_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    import_row_id BIGINT NOT NULL,
    excel_manager_name VARCHAR(255) NULL,
    effective_manager_user_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_snapshot_import_row (import_row_id),
    UNIQUE KEY uk_excel_snapshot_id_batch_source (id, batch_id, source_type),
    KEY idx_excel_snapshot_manager_batch (effective_manager_user_id, batch_id, id),
    KEY idx_excel_snapshot_batch_manager (batch_id, effective_manager_user_id, id),
    CONSTRAINT fk_excel_snapshot_batch FOREIGN KEY (batch_id, source_type)
        REFERENCES excel_import_batch(id, source_type),
    CONSTRAINT fk_excel_snapshot_import_row FOREIGN KEY (batch_id, import_row_id)
        REFERENCES excel_import_row(batch_id, id),
    CONSTRAINT fk_excel_snapshot_manager FOREIGN KEY (effective_manager_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS arrears_snapshot (
    snapshot_record_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    account_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    group_code VARCHAR(100) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    carrying_product_name VARCHAR(255) NULL,
    ending_arrears_amount DECIMAL(20, 4) NOT NULL,
    aging_value VARCHAR(100) NULL,
    PRIMARY KEY (snapshot_record_id),
    UNIQUE KEY uk_arrears_batch_account (batch_id, account_id),
    KEY idx_arrears_batch_group_code (batch_id, group_code),
    KEY idx_arrears_batch_group_name (batch_id, group_name),
    KEY idx_arrears_batch_product (batch_id, carrying_product_name),
    CONSTRAINT chk_arrears_snapshot_source CHECK (source_type = 'ARREARS'),
    CONSTRAINT fk_arrears_snapshot_record FOREIGN KEY (snapshot_record_id, batch_id, source_type)
        REFERENCES excel_snapshot_record(id, batch_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE arrears_snapshot
    MODIFY COLUMN carrying_product_name VARCHAR(255) NULL,
    MODIFY COLUMN aging_value VARCHAR(100) NULL;

CREATE TABLE IF NOT EXISTS line_expiry_snapshot (
    snapshot_record_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    member_service_number VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    group_code VARCHAR(100) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    product_code VARCHAR(100) COLLATE utf8mb4_bin NULL,
    product_name VARCHAR(255) NULL,
    billing_cutoff_date DATE NOT NULL,
    PRIMARY KEY (snapshot_record_id),
    UNIQUE KEY uk_line_batch_service_number (batch_id, member_service_number),
    KEY idx_line_batch_group_code (batch_id, group_code),
    KEY idx_line_batch_group_name (batch_id, group_name),
    KEY idx_line_batch_product_code (batch_id, product_code),
    KEY idx_line_batch_product_name (batch_id, product_name),
    KEY idx_line_batch_cutoff_date (batch_id, billing_cutoff_date),
    CONSTRAINT chk_line_snapshot_source CHECK (source_type = 'LINE_EXPIRY'),
    CONSTRAINT fk_line_snapshot_record FOREIGN KEY (snapshot_record_id, batch_id, source_type)
        REFERENCES excel_snapshot_record(id, batch_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE line_expiry_snapshot
    MODIFY COLUMN product_code VARCHAR(100) COLLATE utf8mb4_bin NULL,
    MODIFY COLUMN product_name VARCHAR(255) NULL;

CREATE TABLE IF NOT EXISTS assignment_exception (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_record_id BIGINT NOT NULL,
    exception_type VARCHAR(30) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution VARCHAR(1000) NULL,
    handled_by_user_id BIGINT NULL,
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assignment_exception_record_type (snapshot_record_id, exception_type),
    KEY idx_assignment_exception_status_time (status, created_at),
    CONSTRAINT chk_assignment_exception_type CHECK (exception_type IN ('EMPTY_NAME', 'NO_MATCH', 'MULTIPLE_MATCHES')),
    CONSTRAINT chk_assignment_exception_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT fk_assignment_exception_record FOREIGN KEY (snapshot_record_id) REFERENCES excel_snapshot_record(id),
    CONSTRAINT fk_assignment_exception_handler FOREIGN KEY (handled_by_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assignment_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_record_id BIGINT NOT NULL,
    previous_manager_user_id BIGINT NULL,
    new_manager_user_id BIGINT NOT NULL,
    change_type VARCHAR(30) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    reason VARCHAR(1000) NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_assignment_history_record_time (snapshot_record_id, changed_at),
    KEY idx_assignment_history_operator_time (changed_by_user_id, changed_at),
    CONSTRAINT chk_assignment_history_type CHECK (change_type IN ('IMPORT_MATCH', 'MANUAL_ASSIGNMENT', 'REASSIGNMENT')),
    CONSTRAINT fk_assignment_history_record FOREIGN KEY (snapshot_record_id) REFERENCES excel_snapshot_record(id),
    CONSTRAINT fk_assignment_history_previous_manager FOREIGN KEY (previous_manager_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_assignment_history_new_manager FOREIGN KEY (new_manager_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_assignment_history_operator FOREIGN KEY (changed_by_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS add_index_if_missing;
