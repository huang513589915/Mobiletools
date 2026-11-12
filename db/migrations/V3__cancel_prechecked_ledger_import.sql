-- A prechecked candidate can be cancelled without deleting its workbook or audit trail.
ALTER TABLE excel_import_batch
    DROP CHECK chk_excel_batch_status,
    ADD CONSTRAINT chk_excel_batch_status CHECK (
        batch_status IN ('RECEIVED', 'PRECHECKED', 'BLOCKED', 'DUPLICATE', 'CONFIRMED', 'FAILED', 'CANCELLED')
    );
