SET NAMES utf8mb4;

-- Run this migration with arrears_management selected as the connection database.

-- Work-order history is additive and remains independent for the two ledgers.
CREATE TABLE IF NOT EXISTS arrears_work_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    statistics_key VARCHAR(6) NOT NULL,
    account_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    current_snapshot_record_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT '待处理',
    last_handled_by_user_id BIGINT NULL,
    last_handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_arrears_work_order_identity (statistics_key, account_id),
    UNIQUE KEY uk_arrears_work_order_snapshot (current_snapshot_record_id),
    KEY idx_arrears_work_order_status_date (statistics_key, status),
    KEY idx_arrears_work_order_handler (last_handled_by_user_id, last_handled_at),
    KEY idx_arrears_work_order_snapshot (current_snapshot_record_id),
    CONSTRAINT chk_arrears_work_order_status CHECK (status IN ('待处理', '跟进中', '已结清')),
    CONSTRAINT fk_arrears_work_order_snapshot FOREIGN KEY (current_snapshot_record_id)
        REFERENCES excel_snapshot_record(id),
    CONSTRAINT fk_arrears_work_order_handler FOREIGN KEY (last_handled_by_user_id)
        REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS arrears_work_order_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL,
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_arrears_status_history_order_time (work_order_id, changed_at, id),
    KEY idx_arrears_status_history_actor_time (changed_by_user_id, changed_at),
    CONSTRAINT chk_arrears_status_history_previous CHECK (previous_status IN ('待处理', '跟进中', '已结清')),
    CONSTRAINT chk_arrears_status_history_new CHECK (new_status IN ('待处理', '跟进中', '已结清')),
    CONSTRAINT chk_arrears_status_history_changed CHECK (previous_status <> new_status),
    CONSTRAINT fk_arrears_status_history_order FOREIGN KEY (work_order_id)
        REFERENCES arrears_work_order(id),
    CONSTRAINT fk_arrears_status_history_actor FOREIGN KEY (changed_by_user_id)
        REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS arrears_work_order_follow_up (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL,
    note TEXT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_arrears_follow_up_order_time (work_order_id, created_at, id),
    KEY idx_arrears_follow_up_actor_time (created_by_user_id, created_at),
    CONSTRAINT chk_arrears_follow_up_nonempty CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT fk_arrears_follow_up_order FOREIGN KEY (work_order_id)
        REFERENCES arrears_work_order(id),
    CONSTRAINT fk_arrears_follow_up_actor FOREIGN KEY (created_by_user_id)
        REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS line_renewal_work_order_follow_up (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL,
    note TEXT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_line_follow_up_order_time (work_order_id, created_at, id),
    KEY idx_line_follow_up_actor_time (created_by_user_id, created_at),
    CONSTRAINT chk_line_follow_up_nonempty CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT fk_line_follow_up_order FOREIGN KEY (work_order_id)
        REFERENCES line_renewal_work_order(id),
    CONSTRAINT fk_line_follow_up_actor FOREIGN KEY (created_by_user_id)
        REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill current arrears snapshots only. INSERT IGNORE makes reruns idempotent
-- and does not fabricate status history or follow-up content.
INSERT IGNORE INTO arrears_work_order(statistics_key, account_id, current_snapshot_record_id)
SELECT cv.statistics_key, a.account_id, a.snapshot_record_id
FROM excel_current_version cv
JOIN arrears_snapshot a ON a.batch_id=cv.batch_id AND a.source_type='ARREARS'
WHERE cv.source_type='ARREARS';
