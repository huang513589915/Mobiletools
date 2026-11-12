USE arrears_management;

DROP PROCEDURE IF EXISTS assert_v7_legacy_cleanup;
DELIMITER //
CREATE PROCEDURE assert_v7_legacy_cleanup()
BEGIN
    DECLARE legacy_tables INT DEFAULT 0;
    DECLARE current_tables INT DEFAULT 0;
    DECLARE retained_rows INT DEFAULT 0;

    SELECT COUNT(*) INTO legacy_tables
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN (
          'customer', 'business_service', 'arrears_bill', 'contract_expiry',
          'task', 'follow_up', 'import_batch', 'import_row', 'user_customer_scope',
          'risk_rule', 'risk_recalculation_run', 'risk_result', 'exception_record'
      );
    IF legacy_tables <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V7 left a retired legacy table behind';
    END IF;

    SELECT COUNT(*) INTO current_tables
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN (
          'app_user', 'operation_audit_log', 'excel_import_batch', 'excel_import_row',
          'excel_current_version', 'excel_snapshot_record', 'arrears_snapshot',
          'line_expiry_snapshot', 'arrears_work_order', 'line_renewal_work_order'
      );
    IF current_tables <> 10 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V7 removed or missed a current application table';
    END IF;

    SELECT COUNT(*) INTO retained_rows
    FROM app_user u
    JOIN operation_audit_log a ON a.operator_user_id = u.id
    WHERE u.username = 'migration-check'
      AND a.operation_type = 'V7_CLEANUP_TEST';
    IF retained_rows <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V7 changed shared account or audit history';
    END IF;
END//
DELIMITER ;

CALL assert_v7_legacy_cleanup();
DROP PROCEDURE assert_v7_legacy_cleanup;
