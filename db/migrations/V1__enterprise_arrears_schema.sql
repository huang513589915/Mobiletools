USE arrears_management;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_definition VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD COLUMN `',
            p_column_name, '` ', p_definition
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL add_column_if_missing('app_user', 'real_name', 'VARCHAR(100) NULL AFTER `username`');
CALL add_column_if_missing('app_user', 'role', 'VARCHAR(30) NULL AFTER `password_hash`');

UPDATE app_user
SET real_name = COALESCE(NULLIF(real_name, ''), username),
    role = COALESCE(NULLIF(role, ''), 'ADMIN')
WHERE real_name IS NULL OR role IS NULL;

ALTER TABLE app_user
    MODIFY COLUMN real_name VARCHAR(100) NOT NULL,
    MODIFY COLUMN role VARCHAR(30) NOT NULL;

CALL add_column_if_missing('customer', 'customer_code', 'VARCHAR(100) NULL AFTER `id`');
CALL add_column_if_missing('customer', 'manager_user_id', 'BIGINT NULL AFTER `manager_name`');
CALL add_column_if_missing('customer', 'source_batch_id', 'BIGINT NULL AFTER `manager_user_id`');
CALL add_column_if_missing('customer', 'record_status', 'VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER `due_summary`');

CALL add_column_if_missing('task', 'customer_id', 'BIGINT NULL AFTER `id`');
CALL add_column_if_missing('task', 'service_id', 'BIGINT NULL AFTER `customer_id`');
CALL add_column_if_missing('task', 'source_type', 'VARCHAR(40) NULL AFTER `service_id`');
CALL add_column_if_missing('task', 'source_record_id', 'BIGINT NULL AFTER `source_type`');
CALL add_column_if_missing('task', 'task_type', 'VARCHAR(40) NOT NULL DEFAULT ''FOLLOW_UP'' AFTER `source_record_id`');
CALL add_column_if_missing('task', 'rule_id', 'BIGINT NULL AFTER `task_type`');
CALL add_column_if_missing('task', 'idempotency_key', 'VARCHAR(160) NULL AFTER `rule_id`');
CALL add_column_if_missing('task', 'created_by', 'BIGINT NULL AFTER `idempotency_key`');
CALL add_column_if_missing('task', 'completed_at', 'DATETIME NULL AFTER `updated_at`');
CALL add_column_if_missing('task', 'closed_at', 'DATETIME NULL AFTER `completed_at`');
CALL add_column_if_missing('task', 'close_reason', 'VARCHAR(1000) NULL AFTER `closed_at`');
CALL add_column_if_missing('task', 'updated_by', 'BIGINT NULL AFTER `close_reason`');

CALL add_column_if_missing('import_batch', 'file_sha256', 'CHAR(64) NULL AFTER `source_type`');
CALL add_column_if_missing('import_batch', 'idempotency_key', 'VARCHAR(160) NULL AFTER `file_sha256`');
CALL add_column_if_missing('import_batch', 'operator_user_id', 'BIGINT NULL AFTER `idempotency_key`');
CALL add_column_if_missing('import_batch', 'started_at', 'DATETIME NULL AFTER `created_at`');
CALL add_column_if_missing('import_batch', 'finished_at', 'DATETIME NULL AFTER `started_at`');
CALL add_column_if_missing('import_batch', 'inserted_rows', 'INT NOT NULL DEFAULT 0 AFTER `invalid_rows`');
CALL add_column_if_missing('import_batch', 'updated_rows', 'INT NOT NULL DEFAULT 0 AFTER `inserted_rows`');
CALL add_column_if_missing('import_batch', 'duplicate_rows', 'INT NOT NULL DEFAULT 0 AFTER `updated_rows`');
CALL add_column_if_missing('import_batch', 'excluded_rows', 'INT NOT NULL DEFAULT 0 AFTER `duplicate_rows`');
CALL add_column_if_missing('import_batch', 'failed_rows', 'INT NOT NULL DEFAULT 0 AFTER `excluded_rows`');

CALL add_column_if_missing('import_row', 'sheet_name', 'VARCHAR(100) NULL AFTER `batch_id`');
CALL add_column_if_missing('import_row', 'business_key', 'VARCHAR(300) NULL AFTER `source_row_number`');
CALL add_column_if_missing('import_row', 'row_category', 'VARCHAR(30) NOT NULL DEFAULT ''VALID'' AFTER `row_status`');
CALL add_column_if_missing('import_row', 'error_code', 'VARCHAR(60) NULL AFTER `error_message`');
CALL add_column_if_missing('import_row', 'raw_payload', 'JSON NULL AFTER `error_code`');
CALL add_column_if_missing('import_row', 'updated_at', 'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`');

CALL add_column_if_missing('follow_up', 'created_by_user_id', 'BIGINT NULL AFTER `created_by`');
CALL add_column_if_missing('follow_up', 'next_action', 'VARCHAR(1000) NULL AFTER `note`');
CALL add_column_if_missing('follow_up', 'source_record_code', 'VARCHAR(160) NULL AFTER `next_action`');
CALL add_column_if_missing('follow_up', 'source_batch_id', 'BIGINT NULL AFTER `source_record_code`');
CALL add_column_if_missing('follow_up', 'updated_at', 'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`');

CREATE TABLE IF NOT EXISTS user_customer_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_user_customer (user_id, customer_id),
    KEY idx_scope_customer (customer_id),
    CONSTRAINT fk_scope_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_scope_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_scope_created_by FOREIGN KEY (created_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS business_service (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    service_code VARCHAR(100) NOT NULL,
    service_name VARCHAR(200) NULL,
    service_type VARCHAR(100) NULL,
    manager_user_id BIGINT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    source_batch_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_customer_code (customer_id, service_code),
    KEY idx_service_customer_status (customer_id, status),
    KEY idx_service_manager (manager_user_id),
    CONSTRAINT fk_service_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_service_manager FOREIGN KEY (manager_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_service_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS arrears_bill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    bill_code VARCHAR(100) NOT NULL,
    billing_period VARCHAR(30) NOT NULL,
    due_amount DECIMAL(18,2) NOT NULL,
    paid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    arrears_amount DECIMAL(18,2) NOT NULL,
    balance_amount DECIMAL(18,2) NULL,
    bill_date DATE NULL,
    due_date DATE NULL,
    source_batch_id BIGINT NULL,
    record_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_customer_code_period (customer_id, bill_code, billing_period),
    KEY idx_bill_customer_period (customer_id, billing_period),
    KEY idx_bill_arrears (arrears_amount),
    CONSTRAINT fk_bill_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_bill_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_bill_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS contract_expiry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    contract_code VARCHAR(100) NULL,
    expiry_date DATE NOT NULL,
    renewal_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    source_batch_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_expiry_date_status (expiry_date, renewal_status),
    KEY idx_expiry_customer (customer_id),
    CONSTRAINT fk_expiry_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_expiry_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_expiry_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS risk_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_code VARCHAR(60) NOT NULL,
    rule_name VARCHAR(150) NOT NULL,
    rule_type VARCHAR(40) NOT NULL,
    threshold_value DECIMAL(18,2) NULL,
    operator VARCHAR(10) NOT NULL,
    version INT NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from DATETIME NULL,
    effective_to DATETIME NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_code_version (rule_code, version),
    KEY idx_rule_enabled_effective (is_enabled, effective_from),
    CONSTRAINT fk_rule_created_by FOREIGN KEY (created_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS risk_recalculation_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_version_from VARCHAR(100) NOT NULL,
    rule_version_to VARCHAR(100) NOT NULL,
    scope_type VARCHAR(30) NOT NULL,
    scope_value VARCHAR(200) NULL,
    status VARCHAR(30) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    started_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_risk_run_status_started (status, started_at),
    CONSTRAINT fk_risk_run_started_by FOREIGN KEY (started_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS risk_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    rule_id BIGINT NOT NULL,
    rule_version INT NOT NULL,
    source_record_type VARCHAR(40) NOT NULL,
    source_record_id BIGINT NOT NULL,
    observed_value DECIMAL(18,2) NULL,
    threshold_value DECIMAL(18,2) NULL,
    risk_level VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    calculated_at DATETIME NOT NULL,
    run_id BIGINT NOT NULL,
    source_batch_id BIGINT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_source_rule_run (source_record_type, source_record_id, rule_id, run_id),
    KEY idx_risk_customer_status (customer_id, status),
    KEY idx_risk_run (run_id),
    CONSTRAINT fk_risk_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_risk_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_risk_rule FOREIGN KEY (rule_id) REFERENCES risk_rule(id),
    CONSTRAINT fk_risk_run FOREIGN KEY (run_id) REFERENCES risk_recalculation_run(id),
    CONSTRAINT fk_risk_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id),
    CONSTRAINT fk_risk_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS exception_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NULL,
    source_row_id BIGINT NULL,
    exception_type VARCHAR(50) NOT NULL,
    business_key VARCHAR(300) NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    resolution VARCHAR(1000) NULL,
    handled_by BIGINT NULL,
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_exception_batch_status (batch_id, status),
    KEY idx_exception_type_status (exception_type, status),
    CONSTRAINT fk_exception_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id),
    CONSTRAINT fk_exception_row FOREIGN KEY (source_row_id) REFERENCES import_row(id),
    CONSTRAINT fk_exception_handled_by FOREIGN KEY (handled_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS operation_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operator_user_id BIGINT NULL,
    operation_type VARCHAR(60) NOT NULL,
    target_type VARCHAR(60) NULL,
    target_id BIGINT NULL,
    result VARCHAR(30) NOT NULL,
    detail VARCHAR(2000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_operator_time (operator_user_id, created_at),
    KEY idx_audit_type_time (operation_type, created_at),
    CONSTRAINT fk_audit_operator FOREIGN KEY (operator_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO risk_rule (rule_code, rule_name, rule_type, threshold_value, operator, version, is_enabled)
VALUES
    ('R001', '余额风险预警', 'BALANCE', 500, 'LT', 1, TRUE),
    ('R002', '欠费金额预警', 'ARREARS', 1000, 'GT', 1, TRUE),
    ('R003', '专线到期预警', 'EXPIRY_DAYS', 30, 'LTE', 1, TRUE),
    ('R004', '任务逾期预警', 'TASK_OVERDUE_DAYS', 1, 'GT', 1, TRUE)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), is_enabled = VALUES(is_enabled);

DROP PROCEDURE IF EXISTS add_column_if_missing;
