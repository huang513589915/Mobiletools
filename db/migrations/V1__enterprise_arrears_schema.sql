CREATE DATABASE IF NOT EXISTS arrears_management
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE arrears_management;
SET NAMES utf8mb4;

-- Base tables shared by the independent Excel-ledger features.
-- Retired customer, import, risk, and task tables are not created here.
CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    real_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
