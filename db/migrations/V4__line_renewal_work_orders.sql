USE arrears_management;
SET NAMES utf8mb4;

-- Renewal handling is intentionally independent from the immutable Excel snapshots.
CREATE TABLE IF NOT EXISTS line_renewal_work_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    statistics_key VARCHAR(8) NOT NULL,
    member_service_number VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    current_snapshot_record_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT '待处理',
    last_handled_by_user_id BIGINT NULL,
    last_handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_line_renewal_identity (statistics_key, member_service_number),
    UNIQUE KEY uk_line_renewal_current_snapshot (current_snapshot_record_id),
    KEY idx_line_renewal_status_date (statistics_key, status),
    KEY idx_line_renewal_handler (last_handled_by_user_id, last_handled_at),
    KEY idx_line_renewal_snapshot (current_snapshot_record_id),
    CONSTRAINT chk_line_renewal_status CHECK (status IN ('待处理', '跟进中', '已续签', '不续签')),
    CONSTRAINT fk_line_renewal_snapshot FOREIGN KEY (current_snapshot_record_id)
        REFERENCES excel_snapshot_record(id),
    CONSTRAINT fk_line_renewal_handler FOREIGN KEY (last_handled_by_user_id)
        REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS line_renewal_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL,
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_line_renewal_history_order_time (work_order_id, changed_at, id),
    KEY idx_line_renewal_history_actor_time (changed_by_user_id, changed_at),
    CONSTRAINT chk_line_renewal_history_previous CHECK (previous_status IN ('待处理', '跟进中', '已续签', '不续签')),
    CONSTRAINT chk_line_renewal_history_new CHECK (new_status IN ('待处理', '跟进中', '已续签', '不续签')),
    CONSTRAINT chk_line_renewal_history_changed CHECK (previous_status <> new_status),
    CONSTRAINT fk_line_renewal_history_order FOREIGN KEY (work_order_id)
        REFERENCES line_renewal_work_order(id),
    CONSTRAINT fk_line_renewal_history_actor FOREIGN KEY (changed_by_user_id)
        REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Idempotent initialization for snapshots that already exist before this migration.
INSERT INTO line_renewal_work_order(statistics_key, member_service_number, current_snapshot_record_id)
SELECT cv.statistics_key, l.member_service_number, l.snapshot_record_id
FROM excel_current_version cv
JOIN line_expiry_snapshot l ON l.batch_id=cv.batch_id AND l.source_type='LINE_EXPIRY'
WHERE cv.source_type='LINE_EXPIRY'
  AND NOT EXISTS (
      SELECT 1 FROM line_renewal_work_order w
      WHERE w.statistics_key=cv.statistics_key
        AND w.member_service_number=l.member_service_number
  );
