USE arrears_management_test;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS verify_independent_excel_ledger_constraints;
DELIMITER //
CREATE PROCEDURE verify_independent_excel_ledger_constraints()
BEGIN
    DECLARE v_token VARCHAR(36) DEFAULT REPLACE(UUID(), '-', '');
    DECLARE v_admin BIGINT;
    DECLARE v_manager_a BIGINT;
    DECLARE v_manager_b BIGINT;
    DECLARE v_file_a BIGINT;
    DECLARE v_file_b BIGINT;
    DECLARE v_file_c BIGINT;
    DECLARE v_arrears_batch BIGINT;
    DECLARE v_arrears_revision BIGINT;
    DECLARE v_line_batch BIGINT;
    DECLARE v_arrears_import BIGINT;
    DECLARE v_arrears_duplicate_import BIGINT;
    DECLARE v_line_import BIGINT;
    DECLARE v_line_duplicate_import BIGINT;
    DECLARE v_arrears_record BIGINT;
    DECLARE v_arrears_duplicate_record BIGINT;
    DECLARE v_line_record BIGINT;
    DECLARE v_line_duplicate_record BIGINT;
    DECLARE v_rejected BOOLEAN DEFAULT FALSE;
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_assigned_manager BIGINT;

    IF DATABASE() <> 'arrears_management_test' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing to run outside arrears_management_test';
    END IF;

    START TRANSACTION;

    INSERT INTO app_user (username, real_name, password_hash, role, enabled)
    VALUES (CONCAT('test-admin-', v_token), '测试管理员', 'test-hash', 'ADMIN', TRUE);
    SET v_admin = LAST_INSERT_ID();
    INSERT INTO app_user (username, real_name, password_hash, role, enabled)
    VALUES (CONCAT('test-manager-a-', v_token), '测试经理甲', 'test-hash', 'CUSTOMER_MANAGER', TRUE);
    SET v_manager_a = LAST_INSERT_ID();
    INSERT INTO app_user (username, real_name, password_hash, role, enabled)
    VALUES (CONCAT('test-manager-b-', v_token), '测试经理乙', 'test-hash', 'CUSTOMER_MANAGER', TRUE);
    SET v_manager_b = LAST_INSERT_ID();

    INSERT INTO excel_source_file (sha256, storage_key, byte_size)
    VALUES (SHA2(CONCAT(v_token, '-arrears'), 256), CONCAT('test/', v_token, '-arrears.xlsx'), 1);
    SET v_file_a = LAST_INSERT_ID();
    INSERT INTO excel_source_file (sha256, storage_key, byte_size)
    VALUES (SHA2(CONCAT(v_token, '-line'), 256), CONCAT('test/', v_token, '-line.xlsx'), 1);
    SET v_file_b = LAST_INSERT_ID();
    INSERT INTO excel_source_file (sha256, storage_key, byte_size)
    VALUES (SHA2(CONCAT(v_token, '-revision'), 256), CONCAT('test/', v_token, '-revision.xlsx'), 1);
    SET v_file_c = LAST_INSERT_ID();

    INSERT INTO excel_import_batch (source_type, statistics_key, original_filename, source_file_id, uploaded_by_user_id, batch_status)
    VALUES ('ARREARS', '202609', 'arrears.xlsx', v_file_a, v_admin, 'CONFIRMED');
    SET v_arrears_batch = LAST_INSERT_ID();
    INSERT INTO excel_import_batch (source_type, statistics_key, original_filename, source_file_id, uploaded_by_user_id, batch_status, revision_of_batch_id)
    VALUES ('ARREARS', '202609', 'arrears-revision.xlsx', v_file_c, v_admin, 'PRECHECKED', v_arrears_batch);
    SET v_arrears_revision = LAST_INSERT_ID();
    INSERT INTO excel_import_batch (source_type, statistics_key, original_filename, source_file_id, uploaded_by_user_id, batch_status)
    VALUES ('LINE_EXPIRY', '20260901', 'line.xlsx', v_file_b, v_admin, 'CONFIRMED');
    SET v_line_batch = LAST_INSERT_ID();

    INSERT INTO excel_current_version (source_type, statistics_key, batch_id, changed_by_user_id)
    VALUES ('ARREARS', '202609', v_arrears_batch, v_admin), ('LINE_EXPIRY', '20260901', v_line_batch, v_admin);

    SET v_rejected = FALSE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR 1062 SET v_rejected = TRUE;
        INSERT INTO excel_current_version (source_type, statistics_key, batch_id, changed_by_user_id)
        VALUES ('ARREARS', '202609', v_arrears_revision, v_admin);
    END;
    IF NOT v_rejected THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate current source/date was not rejected';
    END IF;

    INSERT INTO excel_import_row (batch_id, sheet_name, source_row_number, identity_key, validation_status, raw_fields, normalized_fields)
    VALUES (v_arrears_batch, 'Sheet1', 2, 'account-1', 'VALID', JSON_ARRAY(), JSON_OBJECT());
    SET v_arrears_import = LAST_INSERT_ID();
    INSERT INTO excel_snapshot_record (batch_id, source_type, import_row_id, excel_manager_name, effective_manager_user_id)
    VALUES (v_arrears_batch, 'ARREARS', v_arrears_import, '测试经理甲', v_manager_a);
    SET v_arrears_record = LAST_INSERT_ID();
    INSERT INTO arrears_snapshot (snapshot_record_id, batch_id, source_type, account_id, group_code, group_name, carrying_product_name, ending_arrears_amount, aging_value)
    VALUES (v_arrears_record, v_arrears_batch, 'ARREARS', 'account-1', 'group-1', '集团一', NULL, 0.00, NULL);

    INSERT INTO excel_import_row (batch_id, sheet_name, source_row_number, identity_key, validation_status, raw_fields, normalized_fields)
    VALUES (v_arrears_batch, 'Sheet1', 3, 'account-1', 'VALID', JSON_ARRAY(), JSON_OBJECT());
    SET v_arrears_duplicate_import = LAST_INSERT_ID();
    INSERT INTO excel_snapshot_record (batch_id, source_type, import_row_id, excel_manager_name, effective_manager_user_id)
    VALUES (v_arrears_batch, 'ARREARS', v_arrears_duplicate_import, '测试经理甲', v_manager_a);
    SET v_arrears_duplicate_record = LAST_INSERT_ID();
    SET v_rejected = FALSE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR 1062 SET v_rejected = TRUE;
        INSERT INTO arrears_snapshot (snapshot_record_id, batch_id, source_type, account_id, group_code, group_name, carrying_product_name, ending_arrears_amount, aging_value)
        VALUES (v_arrears_duplicate_record, v_arrears_batch, 'ARREARS', 'account-1', 'group-1', '集团一', '产品一', 1.00, '1');
    END;
    IF NOT v_rejected THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate account identity in one batch was not rejected';
    END IF;

    INSERT INTO excel_import_row (batch_id, sheet_name, source_row_number, identity_key, validation_status, raw_fields, normalized_fields)
    VALUES (v_line_batch, 'Sheet1', 2, 'service-1', 'VALID', JSON_ARRAY(), JSON_OBJECT());
    SET v_line_import = LAST_INSERT_ID();
    INSERT INTO excel_snapshot_record (batch_id, source_type, import_row_id, excel_manager_name, effective_manager_user_id)
    VALUES (v_line_batch, 'LINE_EXPIRY', v_line_import, '测试经理甲', v_manager_a);
    SET v_line_record = LAST_INSERT_ID();
    INSERT INTO line_expiry_snapshot (snapshot_record_id, batch_id, source_type, member_service_number, group_code, group_name, product_code, product_name, billing_cutoff_date)
    VALUES (v_line_record, v_line_batch, 'LINE_EXPIRY', 'service-1', 'group-1', '集团一', NULL, NULL, '2026-10-31');

    INSERT INTO excel_import_row (batch_id, sheet_name, source_row_number, identity_key, validation_status, raw_fields, normalized_fields)
    VALUES (v_line_batch, 'Sheet1', 3, 'service-1', 'VALID', JSON_ARRAY(), JSON_OBJECT());
    SET v_line_duplicate_import = LAST_INSERT_ID();
    INSERT INTO excel_snapshot_record (batch_id, source_type, import_row_id, excel_manager_name, effective_manager_user_id)
    VALUES (v_line_batch, 'LINE_EXPIRY', v_line_duplicate_import, '测试经理甲', v_manager_a);
    SET v_line_duplicate_record = LAST_INSERT_ID();
    SET v_rejected = FALSE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR 1062 SET v_rejected = TRUE;
        INSERT INTO line_expiry_snapshot (snapshot_record_id, batch_id, source_type, member_service_number, group_code, group_name, product_code, product_name, billing_cutoff_date)
        VALUES (v_line_duplicate_record, v_line_batch, 'LINE_EXPIRY', 'service-1', 'group-1', '集团一', 'product-1', '产品一', '2026-10-31');
    END;
    IF NOT v_rejected THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate member service number in one batch was not rejected';
    END IF;

    SET v_rejected = FALSE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET v_rejected = TRUE;
        INSERT INTO arrears_snapshot (snapshot_record_id, batch_id, source_type, account_id, group_code, group_name, carrying_product_name, ending_arrears_amount, aging_value)
        VALUES (v_line_record, v_line_batch, 'ARREARS', 'cross-source', 'group-1', '集团一', '产品一', 0.00, '0');
    END;
    IF NOT v_rejected THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cross-source snapshot row was not rejected';
    END IF;

    UPDATE excel_snapshot_record SET effective_manager_user_id = v_manager_b WHERE id = v_line_record;
    INSERT INTO assignment_history (snapshot_record_id, previous_manager_user_id, new_manager_user_id, change_type, changed_by_user_id, reason)
    VALUES (v_line_record, v_manager_a, v_manager_b, 'REASSIGNMENT', v_admin, 'isolation check');

    SELECT effective_manager_user_id INTO v_assigned_manager
    FROM excel_snapshot_record WHERE id = v_arrears_record;
    IF v_assigned_manager <> v_manager_a THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A line assignment changed the arrears assignment';
    END IF;
    SELECT COUNT(*) INTO v_count FROM assignment_history WHERE snapshot_record_id = v_arrears_record;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A line history entry was attached to an arrears record';
    END IF;

    ROLLBACK;
    SELECT 'PASS: source/date pointer, arrears key, line key, source FK, and assignment isolation' AS result;
END//
DELIMITER ;

CALL verify_independent_excel_ledger_constraints();
DROP PROCEDURE verify_independent_excel_ledger_constraints;
