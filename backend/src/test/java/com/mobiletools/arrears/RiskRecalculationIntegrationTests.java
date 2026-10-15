package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RiskRecalculationIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @SpyBean AdminAccess adminAccess;

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentRunsCreateOnlyOneTask() throws Exception {
        long id = customer("499");
        jdbc.update("UPDATE customer SET manager_user_id=(SELECT id FROM app_user WHERE username='huangling') WHERE id=?", id);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var runs = new java.util.ArrayList<Long>();
        try {
            java.util.concurrent.Callable<Long> action = () -> { gate.await(); return run(List.of(id), "COMPLETED"); };
            var first = executor.submit(action);
            var second = executor.submit(action);
            gate.countDown();
            runs.add(first.get(30, java.util.concurrent.TimeUnit.SECONDS));
            runs.add(second.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?", Integer.class, id));
        } finally {
            executor.shutdown();
            executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            var savedRuns = jdbc.queryForList("SELECT DISTINCT run_id FROM risk_result WHERE customer_id=?", Long.class, id);
            jdbc.update("DELETE FROM task WHERE customer_id=?", id);
            jdbc.update("DELETE FROM risk_result WHERE customer_id=?", id);
            for (long run : savedRuns) {
                jdbc.update("DELETE FROM operation_audit_log WHERE target_type='RISK_RUN' AND target_id=?", run);
                jdbc.update("DELETE FROM risk_recalculation_run WHERE id=?", run);
            }
            jdbc.update("DELETE FROM customer WHERE id=?", id);
        }
    }

    @Test
    void newTaskRollsBackWithFailedRiskPublication() throws Exception {
        long id = customer("499");
        jdbc.update("UPDATE customer SET manager_user_id=(SELECT id FROM app_user WHERE username='huangling') WHERE id=?", id);
        doThrow(new IllegalStateException("late failure")).when(adminAccess)
                .audit(anyLong(), eq("RISK_RECALCULATION"), eq("RISK_RUN"), anyLong(), eq("SUCCESS"), anyString());
        run(List.of(id), "FAILED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE customer_id=?", Integer.class, id));
    }

    @Test
    void overdueRiskLinksOriginalTaskWithoutRecursiveCreation() throws Exception {
        long id = customer("500");
        jdbc.update("UPDATE customer SET manager_user_id=(SELECT id FROM app_user WHERE username='huangling') WHERE id=?", id);
        long original = task(id, 2, "待处理");
        long run = run(List.of(id), "COMPLETED");
        long risk = jdbc.queryForObject("SELECT id FROM risk_result WHERE run_id=?", Long.class, run);
        mvc.perform(get("/api/risk-recalculation/{id}/task", risk).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").value(original));
        run(List.of(id), "COMPLETED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?", Integer.class, id));
    }

    @Test
    void automaticTaskIsReusedUntilRiskClearsAndNewCyclePreservesHistory() throws Exception {
        long id = customer("499.99");
        jdbc.update("UPDATE customer SET manager_user_id=(SELECT id FROM app_user WHERE username='huangling') WHERE id=?", id);
        long first = run(List.of(id), "COMPLETED");
        long task = jdbc.queryForObject("SELECT id FROM task WHERE customer_id=? AND task_type='R001'", Long.class, id);
        assertEquals(3, jdbc.queryForObject("SELECT DATEDIFF(deadline,CURDATE()) FROM task WHERE id=?", Integer.class, task));
        jdbc.update("INSERT INTO follow_up(task_id,note,created_by) VALUES (?,'保留人工历史','huangling')", task);
        jdbc.update("UPDATE task SET task_status='已完成',completed_at=NOW() WHERE id=?", task);
        run(List.of(id), "COMPLETED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?", Integer.class, id));
        jdbc.update("UPDATE customer SET balance=500 WHERE id=?", id);
        run(List.of(id), "COMPLETED");
        jdbc.update("UPDATE customer SET balance=499 WHERE id=?", id);
        run(List.of(id), "COMPLETED");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?", Integer.class, id));
        assertEquals("已完成", jdbc.queryForObject("SELECT task_status FROM task WHERE id=?", String.class, task));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE task_id=?", Integer.class, task));
        long risk = jdbc.queryForObject("SELECT MIN(id) FROM risk_result WHERE run_id=?", Long.class, first);
        mvc.perform(get("/api/risk-recalculation/{id}/task", risk).with(user("zhoumin").roles("CUSTOMER_MANAGER"))).andExpect(status().isForbidden());
    }

    @Test
    void lateFailureRestoresRetiredSnapshotAndScopeRunPreservesOtherCustomers() throws Exception {
        long firstCustomer = customer("499.99");
        long otherCustomer = customer("499.99");
        long original = run(List.of(firstCustomer, otherCustomer), "COMPLETED");
        long scoped = run(List.of(firstCustomer), "COMPLETED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=? AND customer_id=? AND status='ACTIVE'", Integer.class, original, otherCustomer));
        doThrow(new IllegalStateException("simulated late failure")).when(adminAccess)
                .audit(anyLong(), eq("RISK_RECALCULATION"), eq("RISK_RUN"), anyLong(), eq("SUCCESS"), anyString());
        long failed = run(List.of(firstCustomer), "FAILED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=?", Integer.class, failed));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=? AND status='ACTIVE'", Integer.class, scoped));
    }

    @Test
    void fourBoundariesPersistTraceableResultsAndRepeatedRunsPreserveManualHistory() throws Exception {
        long boundary = customer("500");
        long triggered = customer("499.99");
        bill(boundary, "1000.00");
        bill(triggered, "1000.01");
        expiry(boundary, 30);
        expiry(triggered, 31);
        task(boundary, 1, "待处理");
        long overdue = task(triggered, 2, "处理中");
        task(triggered, 2, "已完成");
        jdbc.update("INSERT INTO follow_up(task_id,note,next_action,created_by) VALUES (?,'人工跟进保留','下次联系','huangling')", overdue);
        var tasks = jdbc.queryForList("SELECT id,task_status,processing_result,completed_at,closed_at FROM task ORDER BY id");
        var history = jdbc.queryForList("SELECT id,note,next_action,created_by,created_at FROM follow_up ORDER BY id");

        long first = run(List.of(boundary, triggered), "COMPLETED");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=?", Integer.class, first));
        assertEquals(List.of("R003"), rulesFor(boundary, first));
        assertEquals(List.of("R001", "R002", "R004"), rulesFor(triggered, first));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=? AND source_batch_id IS NOT NULL AND observed_value IS NOT NULL AND threshold_value IS NOT NULL AND rule_version=1", Integer.class, first));
        long second = run(List.of(boundary, triggered), "COMPLETED");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=? AND status='ACTIVE'", Integer.class, second));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=? AND status='CLEARED'", Integer.class, first));
        assertEquals(tasks, jdbc.queryForList("SELECT id,task_status,processing_result,completed_at,closed_at FROM task ORDER BY id"));
        assertEquals(history, jdbc.queryForList("SELECT id,note,next_action,created_by,created_at FROM follow_up ORDER BY id"));

        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='huangling'", Long.class);
        jdbc.update("INSERT INTO user_customer_scope(user_id,customer_id) VALUES (?,?)", userId, boundary);
        mvc.perform(get("/api/risk-recalculation/results").with(user("huangling").roles("CUSTOMER_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.customerId == " + triggered + ")]").isEmpty());
    }

    @Test
    void failedRunRollsBackPartialResultsRetainsPreviousSnapshotAndCanRetry() throws Exception {
        long id = customer("499.99");
        long first = run(List.of(id), "COMPLETED");
        jdbc.update("INSERT INTO risk_rule(rule_code,rule_name,rule_type,threshold_value,operator,version,is_enabled) VALUES ('R999','测试无效规则','INVALID',1,'INVALID',1,1)");
        long failed = run(List.of(id), "FAILED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=?", Integer.class, failed));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=? AND status='ACTIVE'", Integer.class, first));
        assertNotNull(jdbc.queryForObject("SELECT error_message FROM risk_recalculation_run WHERE id=?", String.class, failed));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM exception_record WHERE exception_type='RISK_CALCULATION_FAILED' AND business_key=?", Integer.class, "RISK_RUN:" + failed));
        jdbc.update("UPDATE risk_rule SET is_enabled=0 WHERE rule_code='R999'");
        run(List.of(id), "COMPLETED");
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM risk_recalculation_run WHERE id=?", String.class, failed));
    }

    @Test
    void unknownBalanceDoesNotProduceFalseRiskAndManagerCannotRecalculate() throws Exception {
        long id = customer("0");
        jdbc.update("UPDATE customer SET balance_known=0 WHERE id=?", id);
        long run = run(List.of(id), "COMPLETED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_result WHERE run_id=?", Integer.class, run));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM exception_record WHERE business_key=?", Integer.class, "RISK_RUN:" + run + ":CUSTOMER:" + id));
        mvc.perform(post("/api/risk-recalculation").with(user("huangling").roles("CUSTOMER_MANAGER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private long run(List<Long> ids, String expected) throws Exception {
        var result = mvc.perform(post("/api/risk-recalculation").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("customerIds", ids))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(expected)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("runId").asLong();
    }

    private long customer(String balance) {
        String code = "C-RISK-" + UUID.randomUUID();
        long batch = jdbc.queryForObject("SELECT MIN(id) FROM import_batch", Long.class);
        jdbc.update("INSERT INTO customer(customer_code,customer_name,manager_name,business_type,balance,balance_known,risk_status,due_summary,source_batch_id) VALUES (?,'测试客户','黄铃','测试',?,1,'待核查','',?)", code, balance, batch);
        return jdbc.queryForObject("SELECT id FROM customer WHERE customer_code=?", Long.class, code);
    }

    private void bill(long customer, String amount) {
        jdbc.update("INSERT INTO arrears_bill(customer_id,bill_code,billing_period,due_amount,paid_amount,arrears_amount,source_batch_id) VALUES (?,?,'2026-10',?,0,?,(SELECT MIN(id) FROM import_batch))", customer, UUID.randomUUID().toString(), amount, amount);
    }

    private void expiry(long customer, int days) {
        jdbc.update("INSERT INTO contract_expiry(customer_id,expiry_date,source_batch_id) VALUES (?,DATE_ADD(CURDATE(),INTERVAL ? DAY),(SELECT MIN(id) FROM import_batch))", customer, days);
    }

    private long task(long customer, int days, String state) {
        jdbc.update("INSERT INTO task(customer_id,title,owner_name,deadline,task_status,priority,source_batch_id) VALUES (?,'风险测试任务','黄铃',DATE_SUB(CURDATE(),INTERVAL ? DAY),?,'中',(SELECT MIN(id) FROM import_batch))", customer, days, state);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private List<String> rulesFor(long customer, long run) {
        return jdbc.queryForList("SELECT rr.rule_code FROM risk_result r JOIN risk_rule rr ON rr.id=r.rule_id WHERE r.customer_id=? AND r.run_id=? ORDER BY rr.rule_code", String.class, customer, run);
    }
}
