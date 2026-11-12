USE arrears_management_test;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS verify_line_renewal_work_orders;
DELIMITER //
CREATE PROCEDURE verify_line_renewal_work_orders()
BEGIN
    DECLARE v_index_count INT DEFAULT 0;
    DECLARE v_constraint_count INT DEFAULT 0;
    DECLARE v_linked_count INT DEFAULT 0;
    DECLARE v_current_line_count INT DEFAULT 0;
    DECLARE v_count_after_first_init INT DEFAULT 0;
    DECLARE v_count_after_second_init INT DEFAULT 0;
    DECLARE v_test_user_id BIGINT;
    DECLARE v_test_work_order_id BIGINT;
    DECLARE v_first_history_id BIGINT;
    DECLARE v_second_history_id BIGINT;
    DECLARE v_first_history_in_order BIGINT;

    IF DATABASE() <> 'arrears_management_test' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing to run outside arrears_management_test';
    END IF;

    SELECT COUNT(*) INTO v_index_count
    FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='line_renewal_work_order'
      AND index_name='uk_line_renewal_identity' AND non_unique=0;
    IF v_index_count <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing unique statistics-date/service-number key';
    END IF;

    SELECT COUNT(*) INTO v_index_count
    FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='line_renewal_status_history'
      AND index_name='idx_line_renewal_history_order_time';
    IF v_index_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing work-order history time index';
    END IF;

    SELECT COUNT(*) INTO v_constraint_count
    FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE()
      AND ((table_name='line_renewal_work_order' AND constraint_name='chk_line_renewal_status')
        OR (table_name='line_renewal_status_history' AND constraint_name IN (
            'chk_line_renewal_history_previous', 'chk_line_renewal_history_new',
            'chk_line_renewal_history_changed')));
    IF v_constraint_count <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Missing renewal status or history constraints';
    END IF;

    INSERT INTO app_user(username, real_name, password_hash, role, enabled)
    VALUES (CONCAT('v4-sql-test-', REPLACE(UUID(), '-', '')), 'V4 SQL 测试', 'test-hash', 'ADMIN', TRUE);
    SET v_test_user_id = LAST_INSERT_ID();
    INSERT INTO line_renewal_work_order(statistics_key, member_service_number, status)
    VALUES ('20260101', CONCAT('v4-sql-test-', v_test_user_id),
            CONVERT(0xE5BE85E5A484E79086 USING utf8mb4));
    SET v_test_work_order_id = LAST_INSERT_ID();
    INSERT INTO line_renewal_status_history(
        work_order_id, previous_status, new_status, changed_by_user_id, changed_at
    ) VALUES (
        v_test_work_order_id, CONVERT(0xE5BE85E5A484E79086 USING utf8mb4),
        CONVERT(0xE8B79FE8BF9BE4B8AD USING utf8mb4), v_test_user_id, '2026-10-01 00:00:00'
    );
    SET v_first_history_id = LAST_INSERT_ID();
    INSERT INTO line_renewal_status_history(
        work_order_id, previous_status, new_status, changed_by_user_id, changed_at
    ) VALUES (
        v_test_work_order_id, CONVERT(0xE8B79FE8BF9BE4B8AD USING utf8mb4),
        CONVERT(0xE5B7B2E7BBADE7ADBE USING utf8mb4), v_test_user_id, '2026-10-02 00:00:00'
    );
    SET v_second_history_id = LAST_INSERT_ID();
    SELECT id INTO v_first_history_in_order
    FROM line_renewal_status_history
    WHERE work_order_id=v_test_work_order_id
    ORDER BY changed_at, id LIMIT 1;
    IF v_first_history_in_order <> v_first_history_id OR v_second_history_id <= v_first_history_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Work-order history does not sort stably by time and id';
    END IF;

    -- Repeat the migration's insert-select backfill twice. The second run must
    -- leave the work-order count unchanged and every current line row linked.
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
    SELECT COUNT(*) INTO v_count_after_first_init FROM line_renewal_work_order;

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
    SELECT COUNT(*) INTO v_count_after_second_init FROM line_renewal_work_order;
    IF v_count_after_second_init <> v_count_after_first_init THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Renewal work-order initialization is not idempotent';
    END IF;

    SELECT COUNT(*) INTO v_current_line_count
    FROM excel_current_version cv
    JOIN line_expiry_snapshot l ON l.batch_id=cv.batch_id AND l.source_type='LINE_EXPIRY'
    WHERE cv.source_type='LINE_EXPIRY';
    SELECT COUNT(*) INTO v_linked_count
    FROM excel_current_version cv
    JOIN line_expiry_snapshot l ON l.batch_id=cv.batch_id AND l.source_type='LINE_EXPIRY'
    JOIN line_renewal_work_order w
      ON w.statistics_key=cv.statistics_key
     AND w.member_service_number=l.member_service_number
     AND w.current_snapshot_record_id=l.snapshot_record_id
    WHERE cv.source_type='LINE_EXPIRY';
    IF v_linked_count <> v_current_line_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A current line snapshot has no linked renewal work order';
    END IF;

    SELECT 'PASS: renewal identity uniqueness, status/history constraints, current snapshot links, idempotent initialization' AS result;
END//
DELIMITER ;

START TRANSACTION;
CALL verify_line_renewal_work_orders();
ROLLBACK;
DROP PROCEDURE verify_line_renewal_work_orders;
