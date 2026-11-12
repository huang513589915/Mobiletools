USE arrears_management;
SET NAMES utf8mb4;

-- Retire only tables from the unsupported customer-ledger, risk, manual-task,
-- and legacy-import implementation. Current excel_* snapshots, current work
-- orders, app_user, and all audit history are deliberately preserved.
DROP TABLE IF EXISTS risk_result;
DROP TABLE IF EXISTS exception_record;
DROP TABLE IF EXISTS user_customer_scope;
DROP TABLE IF EXISTS follow_up;
DROP TABLE IF EXISTS task;
DROP TABLE IF EXISTS risk_recalculation_run;
DROP TABLE IF EXISTS risk_rule;
DROP TABLE IF EXISTS arrears_bill;
DROP TABLE IF EXISTS contract_expiry;
DROP TABLE IF EXISTS business_service;
DROP TABLE IF EXISTS customer;
DROP TABLE IF EXISTS import_row;
DROP TABLE IF EXISTS import_batch;
