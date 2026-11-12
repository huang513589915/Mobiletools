USE arrears_management;

-- Model the retired schema and its foreign-key dependencies. This fixture
-- ensures V7 can remove legacy tables in dependency order without touching
-- current data or shared audit history.
CREATE TABLE customer (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE import_batch (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE import_row (
    id BIGINT NOT NULL PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    CONSTRAINT fk_v7_fixture_import_row_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB;
CREATE TABLE risk_rule (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE risk_recalculation_run (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE business_service (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    source_batch_id BIGINT NULL,
    CONSTRAINT fk_v7_fixture_service_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_v7_fixture_service_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB;
CREATE TABLE user_customer_scope (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    CONSTRAINT fk_v7_fixture_scope_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_v7_fixture_scope_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
) ENGINE=InnoDB;
CREATE TABLE task (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    rule_id BIGINT NULL,
    CONSTRAINT fk_v7_fixture_task_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_v7_fixture_task_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_v7_fixture_task_rule FOREIGN KEY (rule_id) REFERENCES risk_rule(id)
) ENGINE=InnoDB;
CREATE TABLE follow_up (
    id BIGINT NOT NULL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    CONSTRAINT fk_v7_fixture_follow_up_task FOREIGN KEY (task_id) REFERENCES task(id)
) ENGINE=InnoDB;
CREATE TABLE arrears_bill (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    source_batch_id BIGINT NULL,
    CONSTRAINT fk_v7_fixture_bill_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_v7_fixture_bill_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_v7_fixture_bill_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB;
CREATE TABLE contract_expiry (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    source_batch_id BIGINT NULL,
    CONSTRAINT fk_v7_fixture_expiry_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_v7_fixture_expiry_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_v7_fixture_expiry_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB;
CREATE TABLE risk_result (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    service_id BIGINT NULL,
    rule_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    source_batch_id BIGINT NULL,
    CONSTRAINT fk_v7_fixture_result_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_v7_fixture_result_service FOREIGN KEY (service_id) REFERENCES business_service(id),
    CONSTRAINT fk_v7_fixture_result_rule FOREIGN KEY (rule_id) REFERENCES risk_rule(id),
    CONSTRAINT fk_v7_fixture_result_run FOREIGN KEY (run_id) REFERENCES risk_recalculation_run(id),
    CONSTRAINT fk_v7_fixture_result_batch FOREIGN KEY (source_batch_id) REFERENCES import_batch(id)
) ENGINE=InnoDB;
CREATE TABLE exception_record (
    id BIGINT NOT NULL PRIMARY KEY,
    batch_id BIGINT NULL,
    source_row_id BIGINT NULL,
    CONSTRAINT fk_v7_fixture_exception_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id),
    CONSTRAINT fk_v7_fixture_exception_row FOREIGN KEY (source_row_id) REFERENCES import_row(id)
) ENGINE=InnoDB;

INSERT INTO app_user(username, real_name, password_hash, role, enabled)
VALUES ('migration-check', 'Migration Check', 'unused', 'ADMIN', TRUE);
INSERT INTO operation_audit_log(operator_user_id, operation_type, result, detail)
SELECT id, 'V7_CLEANUP_TEST', 'SUCCESS', 'preserved audit fixture'
FROM app_user WHERE username='migration-check';
INSERT INTO customer(id) VALUES (1);
INSERT INTO import_batch(id) VALUES (1);
INSERT INTO import_row(id, batch_id) VALUES (1, 1);
INSERT INTO risk_rule(id) VALUES (1);
INSERT INTO risk_recalculation_run(id) VALUES (1);
INSERT INTO business_service(id, customer_id, source_batch_id) VALUES (1, 1, 1);
INSERT INTO user_customer_scope(id, user_id, customer_id)
SELECT 1, id, 1 FROM app_user WHERE username='migration-check';
INSERT INTO task(id, customer_id, service_id, rule_id) VALUES (1, 1, 1, 1);
INSERT INTO follow_up(id, task_id) VALUES (1, 1);
INSERT INTO arrears_bill(id, customer_id, service_id, source_batch_id) VALUES (1, 1, 1, 1);
INSERT INTO contract_expiry(id, customer_id, service_id, source_batch_id) VALUES (1, 1, 1, 1);
INSERT INTO risk_result(id, customer_id, service_id, rule_id, run_id, source_batch_id) VALUES (1, 1, 1, 1, 1, 1);
INSERT INTO exception_record(id, batch_id, source_row_id) VALUES (1, 1, 1);
