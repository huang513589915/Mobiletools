SET NAMES utf8mb4;

-- Current ledger periods are composed from immutable row versions produced by
-- multiple confirmed uploads. The period row is also the serialization lock.
CREATE TABLE IF NOT EXISTS excel_dataset_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL,
    statistics_key VARCHAR(8) NOT NULL,
    source_batch_id BIGINT NOT NULL,
    based_on_version_id BIGINT NULL,
    created_by_user_id BIGINT NOT NULL,
    added_rows INT NOT NULL DEFAULT 0,
    updated_rows INT NOT NULL DEFAULT 0,
    unchanged_rows INT NOT NULL DEFAULT 0,
    retained_rows INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_dataset_source_batch (source_batch_id),
    UNIQUE KEY uk_excel_dataset_source_key_id (id, source_type, statistics_key),
    KEY idx_excel_dataset_period (source_type, statistics_key, created_at),
    CONSTRAINT chk_excel_dataset_source CHECK (source_type IN ('ARREARS', 'LINE_EXPIRY')),
    CONSTRAINT chk_excel_dataset_statistics_key CHECK (
        (source_type='ARREARS' AND statistics_key REGEXP '^[0-9]{6}$') OR
        (source_type='LINE_EXPIRY' AND statistics_key REGEXP '^[0-9]{8}$')
    ),
    CONSTRAINT fk_excel_dataset_batch FOREIGN KEY (source_batch_id) REFERENCES excel_import_batch(id),
    CONSTRAINT fk_excel_dataset_base FOREIGN KEY (based_on_version_id) REFERENCES excel_dataset_version(id),
    CONSTRAINT fk_excel_dataset_actor FOREIGN KEY (created_by_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_dataset_period (
    source_type VARCHAR(20) NOT NULL,
    statistics_key VARCHAR(8) NOT NULL,
    current_version_id BIGINT NULL,
    changed_by_user_id BIGINT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (source_type, statistics_key),
    CONSTRAINT chk_excel_dataset_period_source CHECK (source_type IN ('ARREARS', 'LINE_EXPIRY')),
    CONSTRAINT chk_excel_dataset_period_key CHECK (
        (source_type='ARREARS' AND statistics_key REGEXP '^[0-9]{6}$') OR
        (source_type='LINE_EXPIRY' AND statistics_key REGEXP '^[0-9]{8}$')
    ),
    CONSTRAINT fk_excel_dataset_period_version FOREIGN KEY (current_version_id, source_type, statistics_key)
        REFERENCES excel_dataset_version(id, source_type, statistics_key),
    CONSTRAINT fk_excel_dataset_period_actor FOREIGN KEY (changed_by_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_effective_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL,
    statistics_key VARCHAR(8) NOT NULL,
    identity_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    latest_batch_id BIGINT NOT NULL,
    snapshot_record_id BIGINT NOT NULL,
    raw_fields JSON NOT NULL,
    normalized_fields JSON NOT NULL,
    field_provenance JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_excel_effective_snapshot (snapshot_record_id),
    UNIQUE KEY uk_excel_effective_identity_id (id, identity_key),
    UNIQUE KEY uk_excel_effective_scope_identity (id, source_type, statistics_key, identity_key),
    KEY idx_excel_effective_latest_batch (latest_batch_id),
    KEY idx_excel_effective_period_identity (source_type, statistics_key, identity_key),
    CONSTRAINT chk_excel_effective_source CHECK (source_type IN ('ARREARS', 'LINE_EXPIRY')),
    CONSTRAINT fk_excel_effective_batch FOREIGN KEY (latest_batch_id) REFERENCES excel_import_batch(id),
    CONSTRAINT fk_excel_effective_snapshot FOREIGN KEY (snapshot_record_id) REFERENCES excel_snapshot_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS excel_dataset_version_row (
    dataset_version_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    statistics_key VARCHAR(8) NOT NULL,
    identity_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    effective_row_id BIGINT NOT NULL,
    PRIMARY KEY (dataset_version_id, identity_key),
    UNIQUE KEY uk_excel_dataset_member_row (dataset_version_id, effective_row_id),
    CONSTRAINT fk_excel_dataset_member_version FOREIGN KEY (dataset_version_id)
        REFERENCES excel_dataset_version(id),
    CONSTRAINT fk_excel_dataset_member_scope FOREIGN KEY (dataset_version_id, source_type, statistics_key)
        REFERENCES excel_dataset_version(id, source_type, statistics_key),
    CONSTRAINT fk_excel_dataset_member_effective FOREIGN KEY (effective_row_id)
        REFERENCES excel_effective_row(id),
    CONSTRAINT fk_excel_dataset_member_identity FOREIGN KEY (effective_row_id, source_type, statistics_key, identity_key)
        REFERENCES excel_effective_row(id, source_type, statistics_key, identity_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE excel_import_batch
    ADD COLUMN preview_base_version_id BIGINT NULL,
    ADD COLUMN retained_rows INT NOT NULL DEFAULT 0;

ALTER TABLE excel_revision_diff
    DROP CHECK chk_excel_revision_change_type,
    ADD COLUMN previous_fields JSON NULL,
    ADD COLUMN candidate_fields JSON NULL,
    ADD COLUMN changed_fields JSON NULL,
    ADD CONSTRAINT chk_excel_revision_change_type CHECK (
        change_type IN ('ADDED', 'UPDATED', 'UNCHANGED', 'RETAINED', 'REMOVED', 'CHANGED')
    );

-- Backfill the single active batch for each period as the initial composed
-- version. Historical batches remain untouched and are not silently merged.
INSERT INTO excel_dataset_version (
    source_type, statistics_key, source_batch_id, based_on_version_id,
    created_by_user_id, added_rows, updated_rows, unchanged_rows, retained_rows, created_at
)
SELECT cv.source_type, cv.statistics_key, cv.batch_id, NULL, cv.changed_by_user_id,
       (SELECT COUNT(*) FROM excel_snapshot_record s WHERE s.batch_id=cv.batch_id),
       0, 0, 0, cv.changed_at
FROM excel_current_version cv
WHERE NOT EXISTS (
    SELECT 1 FROM excel_dataset_version v WHERE v.source_batch_id=cv.batch_id
);

INSERT INTO excel_effective_row (
    source_type, statistics_key, identity_key, latest_batch_id, snapshot_record_id,
    raw_fields, normalized_fields, field_provenance, created_at
)
SELECT b.source_type, b.statistics_key, r.identity_key, b.id, s.id,
       r.raw_fields, COALESCE(r.normalized_fields, JSON_OBJECT()), JSON_OBJECT(), s.created_at
FROM excel_current_version cv
JOIN excel_dataset_version v ON v.source_batch_id=cv.batch_id
JOIN excel_import_batch b ON b.id=cv.batch_id
JOIN excel_snapshot_record s ON s.batch_id=b.id AND s.source_type=b.source_type
JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=b.id
WHERE r.identity_key IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM excel_effective_row e WHERE e.snapshot_record_id=s.id
  );

INSERT INTO excel_dataset_version_row(dataset_version_id, source_type, statistics_key, identity_key, effective_row_id)
SELECT v.id, e.source_type, e.statistics_key, e.identity_key, e.id
FROM excel_current_version cv
JOIN excel_dataset_version v ON v.source_batch_id=cv.batch_id
JOIN excel_effective_row e ON e.latest_batch_id=cv.batch_id
    AND e.source_type=cv.source_type AND e.statistics_key=cv.statistics_key
WHERE NOT EXISTS (
    SELECT 1 FROM excel_dataset_version_row m
    WHERE m.dataset_version_id=v.id AND m.identity_key=e.identity_key
);

INSERT INTO excel_dataset_period(source_type, statistics_key, current_version_id, changed_by_user_id, changed_at)
SELECT cv.source_type, cv.statistics_key, v.id, cv.changed_by_user_id, cv.changed_at
FROM excel_current_version cv
JOIN excel_dataset_version v ON v.source_batch_id=cv.batch_id
ON DUPLICATE KEY UPDATE current_version_id=VALUES(current_version_id),
    changed_by_user_id=VALUES(changed_by_user_id), changed_at=VALUES(changed_at);

ALTER TABLE excel_import_batch
    ADD CONSTRAINT fk_excel_batch_preview_base FOREIGN KEY (preview_base_version_id)
        REFERENCES excel_dataset_version(id);

CREATE OR REPLACE VIEW excel_current_dataset AS
SELECT p.source_type, p.statistics_key, p.current_version_id,
       e.latest_batch_id AS batch_id, e.snapshot_record_id, e.id AS effective_row_id,
       m.identity_key, e.raw_fields AS effective_raw_fields,
       e.normalized_fields AS effective_normalized_fields,
       e.field_provenance, p.changed_by_user_id, p.changed_at
FROM excel_dataset_period p
JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id
JOIN excel_effective_row e ON e.id=m.effective_row_id;
