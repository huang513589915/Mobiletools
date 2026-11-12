USE arrears_management_test;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS verify_ledger_work_orders;
DELIMITER //
CREATE PROCEDURE verify_ledger_work_orders()
BEGIN
    DECLARE v_before BIGINT DEFAULT 0;
    DECLARE v_after BIGINT DEFAULT 0;
    DECLARE v_current BIGINT DEFAULT 0;
    DECLARE v_linked BIGINT DEFAULT 0;
    DECLARE v_count INT DEFAULT 0;

    IF BINARY DATABASE() <> BINARY 'arrears_management_test' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing to run outside arrears_management_test';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM information_schema.statistics
    WHERE BINARY table_schema=BINARY DATABASE() AND BINARY table_name=BINARY 'arrears_work_order'
      AND BINARY index_name=BINARY 'uk_arrears_work_order_identity' AND non_unique=0;
    IF v_count <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing monthly arrears natural key';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM information_schema.table_constraints
    WHERE BINARY constraint_schema=BINARY DATABASE()
      AND ((BINARY table_name=BINARY 'arrears_work_order' AND BINARY constraint_name=BINARY 'chk_arrears_work_order_status')
        OR (BINARY table_name=BINARY 'arrears_work_order_status_history' AND BINARY constraint_name IN (
            BINARY 'chk_arrears_status_history_previous', BINARY 'chk_arrears_status_history_new',
            BINARY 'chk_arrears_status_history_changed'))
        OR (BINARY table_name=BINARY 'arrears_work_order_follow_up' AND BINARY constraint_name=BINARY 'chk_arrears_follow_up_nonempty')
        OR (BINARY table_name=BINARY 'line_renewal_work_order_follow_up' AND BINARY constraint_name=BINARY 'chk_line_follow_up_nonempty'));
    IF v_count <> 6 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing work-order status or nonempty-note constraints';
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM information_schema.table_constraints
    WHERE BINARY constraint_schema=BINARY DATABASE()
      AND BINARY constraint_type=BINARY 'FOREIGN KEY'
      AND BINARY constraint_name IN (
        BINARY 'fk_arrears_work_order_snapshot', BINARY 'fk_arrears_work_order_handler',
        BINARY 'fk_arrears_status_history_order', BINARY 'fk_arrears_status_history_actor',
        BINARY 'fk_arrears_follow_up_order', BINARY 'fk_arrears_follow_up_actor',
        BINARY 'fk_line_follow_up_order', BINARY 'fk_line_follow_up_actor');
    IF v_count <> 8 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing work-order or actor foreign keys';
    END IF;

    SELECT COUNT(*) INTO v_current
    FROM excel_current_version cv
    JOIN arrears_snapshot a ON a.batch_id=cv.batch_id AND BINARY a.source_type=BINARY 'ARREARS'
    WHERE BINARY cv.source_type=BINARY 'ARREARS';
    SELECT COUNT(*) INTO v_linked
    FROM excel_current_version cv
    JOIN arrears_snapshot a ON a.batch_id=cv.batch_id AND BINARY a.source_type=BINARY 'ARREARS'
    JOIN arrears_work_order w ON w.statistics_key=cv.statistics_key
       AND w.account_id=a.account_id AND w.current_snapshot_record_id=a.snapshot_record_id
    WHERE BINARY cv.source_type=BINARY 'ARREARS';
    IF v_linked <> v_current THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A current arrears snapshot has no linked monthly work order';
    END IF;

    SELECT COUNT(*) INTO v_before FROM arrears_work_order;
    INSERT IGNORE INTO arrears_work_order(statistics_key, account_id, current_snapshot_record_id)
    SELECT cv.statistics_key, a.account_id, a.snapshot_record_id
    FROM excel_current_version cv
    JOIN arrears_snapshot a ON a.batch_id=cv.batch_id AND BINARY a.source_type=BINARY 'ARREARS'
    WHERE BINARY cv.source_type=BINARY 'ARREARS';
    SELECT COUNT(*) INTO v_after FROM arrears_work_order;
    IF v_after <> v_before THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Arrears work-order backfill is not idempotent';
    END IF;

    -- Confirm follow-up rows have deterministic timeline indexes.
    SELECT COUNT(*) INTO v_count
    FROM information_schema.statistics
    WHERE BINARY table_schema=BINARY DATABASE() AND BINARY index_name IN (
        BINARY 'idx_arrears_status_history_order_time', BINARY 'idx_arrears_follow_up_order_time',
        BINARY 'idx_line_follow_up_order_time');
    IF v_count <> 9 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing handling timeline indexes';
    END IF;

    SELECT 'PASS: monthly identity, status/note constraints, current links, backfill idempotency, timeline indexes' AS result;
END//
DELIMITER ;

START TRANSACTION;
CALL verify_ledger_work_orders();
ROLLBACK;
DROP PROCEDURE verify_ledger_work_orders;
