DELIMITER //
CREATE PROCEDURE assert_v6(IN condition_ok BOOLEAN, IN failure_message VARCHAR(255))
BEGIN
    IF condition_ok IS NULL OR condition_ok = FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;
END//
DELIMITER ;

CALL assert_v6(
    (SELECT COUNT(*) FROM excel_dataset_period) = 2,
    'V6 did not create one current composed version for each independent period'
);
CALL assert_v6(
    (SELECT COUNT(*) FROM excel_dataset_version_row m
      JOIN excel_dataset_version v ON v.id=m.dataset_version_id
     WHERE v.source_type='ARREARS' AND v.statistics_key='202610') = 1,
    'V6 did not backfill the active arrears row'
);
CALL assert_v6(
    (SELECT COUNT(*) FROM excel_dataset_version_row m
      JOIN excel_effective_row e ON e.id=m.effective_row_id
      JOIN excel_dataset_version v ON v.id=m.dataset_version_id
     WHERE v.source_type='LINE_EXPIRY' AND v.statistics_key='20261101'
       AND e.identity_key='20261101:S-1') = 1,
    'V6 did not backfill the active line row'
);
CALL assert_v6(
    (SELECT COUNT(*) FROM excel_dataset_version_row m
      JOIN excel_effective_row e ON e.id=m.effective_row_id
      JOIN excel_dataset_version v ON v.id=m.dataset_version_id
     WHERE v.source_type='ARREARS' AND e.latest_batch_id=1) = 0,
    'V6 merged an inactive historical batch during initial backfill'
);
CALL assert_v6(
    (SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=10) = 2,
    'V6 migration changed the effective manager assignment on an active row'
);
CALL assert_v6(
    (SELECT COUNT(*) FROM line_renewal_work_order w
      JOIN line_renewal_status_history h ON h.work_order_id=w.id
     WHERE w.statistics_key='20261101' AND w.member_service_number='S-1'
       AND w.current_snapshot_record_id=11 AND w.status='跟进中'
       AND w.last_handled_by_user_id=2 AND h.new_status='跟进中') = 1,
    'V6 migration changed existing renewal work-order handling or history'
);

-- A later composed version may contain row versions from different batches.
INSERT INTO excel_effective_row (
    source_type, statistics_key, identity_key, latest_batch_id, snapshot_record_id,
    raw_fields, normalized_fields, field_provenance
)
VALUES ('ARREARS', '202610', '202610:A-OLD', 1, 9,
        JSON_ARRAY(JSON_OBJECT('header','账户ID','value','A-OLD')),
        JSON_OBJECT('账户ID','A-OLD'), JSON_OBJECT());
SET @old_effective_row_id=LAST_INSERT_ID();
INSERT INTO excel_dataset_version_row(dataset_version_id, source_type, statistics_key, identity_key, effective_row_id)
SELECT p.current_version_id, 'ARREARS', '202610', '202610:A-OLD', @old_effective_row_id
FROM excel_dataset_period p WHERE p.source_type='ARREARS' AND p.statistics_key='202610';
CALL assert_v6(
    (SELECT COUNT(DISTINCT e.latest_batch_id) FROM excel_dataset_version_row m
      JOIN excel_effective_row e ON e.id=m.effective_row_id
      JOIN excel_dataset_period p ON p.current_version_id=m.dataset_version_id
     WHERE p.source_type='ARREARS' AND p.statistics_key='202610') = 2,
    'A composed version cannot contain effective rows from multiple batches'
);

-- Identity is unique within one version.
DELIMITER //
CREATE PROCEDURE assert_duplicate_member_rejected()
BEGIN
    DECLARE duplicate_rejected BOOLEAN DEFAULT FALSE;
    DECLARE CONTINUE HANDLER FOR 1062 SET duplicate_rejected=TRUE;
    INSERT INTO excel_dataset_version_row(dataset_version_id, source_type, statistics_key, identity_key, effective_row_id)
    SELECT p.current_version_id, 'ARREARS', '202610', '202610:A-OLD', @old_effective_row_id
      FROM excel_dataset_period p WHERE p.source_type='ARREARS' AND p.statistics_key='202610';
    IF duplicate_rejected=FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A dataset version accepted the same business identity twice';
    END IF;
END//
DELIMITER ;
CALL assert_duplicate_member_rejected();

