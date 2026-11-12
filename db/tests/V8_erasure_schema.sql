USE arrears_management;

DROP PROCEDURE IF EXISTS assert_v8_erasure_schema;
DELIMITER //
CREATE PROCEDURE assert_v8_erasure_schema()
BEGIN
    DECLARE source_file_nullable INT DEFAULT 0;
    DECLARE erased_rows_exists INT DEFAULT 0;
    DECLARE purge_status_columns INT DEFAULT 0;
    DECLARE queue_columns INT DEFAULT 0;
    DECLARE purge_tables INT DEFAULT 0;

    SELECT COUNT(*) INTO source_file_nullable
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='excel_import_batch'
      AND column_name='source_file_id' AND is_nullable='YES';
    IF source_file_nullable <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V8 must allow destroyed workbook references';
    END IF;

    SELECT COUNT(*) INTO erased_rows_exists
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='excel_import_batch'
      AND column_name='erased_rows' AND column_default='0';
    IF erased_rows_exists <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V8 erased row aggregate is missing';
    END IF;

    SELECT COUNT(*) INTO purge_status_columns
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='excel_import_batch'
      AND column_name IN ('original_file_purge_operation_id','original_file_purge_status');
    IF purge_status_columns <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V8 original workbook deletion status is missing';
    END IF;

    SELECT COUNT(*) INTO queue_columns
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='excel_original_file_purge_queue'
      AND column_name IN ('operation_id','storage_key','attempt_count','last_error_code','next_attempt_at');
    IF queue_columns <> 5 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V8 purge retry queue schema is incomplete';
    END IF;

    SELECT COUNT(*) INTO purge_tables
    FROM information_schema.tables
    WHERE table_schema=DATABASE()
      AND table_name IN ('excel_import_batch','excel_source_file','excel_original_file_purge_queue');
    IF purge_tables <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V8 must preserve current import tables';
    END IF;
END//
DELIMITER ;

CALL assert_v8_erasure_schema();
DROP PROCEDURE assert_v8_erasure_schema;
