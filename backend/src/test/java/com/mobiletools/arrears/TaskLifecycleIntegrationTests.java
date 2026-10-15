package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskLifecycleIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    private String manager;
    private long customerId;
    private String owner;

    @BeforeEach
    void findExistingScopedCustomer() {
        var scope = jdbc.queryForMap("""
                SELECT u.username,c.id,c.manager_name FROM user_customer_scope s
                JOIN app_user u ON u.id=s.user_id JOIN customer c ON c.id=s.customer_id
                WHERE u.role='CUSTOMER_MANAGER' AND u.enabled=TRUE
                  AND c.record_status='ACTIVE' AND c.customer_code IS NOT NULL AND c.customer_code<>''
                ORDER BY c.id LIMIT 1
                """);
        manager = (String) scope.get("username");
        customerId = ((Number) scope.get("id")).longValue();
        owner = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createDetailFollowUpStartCompleteAndAudit() throws Exception {
        String title = "lifecycle-" + UUID.randomUUID();
        mvc.perform(post("/api/tasks").with(user(manager).roles("CUSTOMER_MANAGER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", title, "customerId", customerId,
                                "deadline", LocalDate.now().minusDays(3).toString(), "priority", "高"))))
                .andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title);
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("待处理"))
                .andExpect(jsonPath("$.data.overdue").value(true)).andExpect(jsonPath("$.data.overdueDays").value(3));
        action(id, "follow-ups", Map.of("nextAction", "  call tomorrow  ", "createdBy", "spoofed"))
                .andExpect(status().isOk());
        action(id, "follow-ups", Map.of("note", "  contacted  ")).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.data.followUps.length()").value(2))
                .andExpect(jsonPath("$.data.followUps[0].note").value(""))
                .andExpect(jsonPath("$.data.followUps[0].nextAction").value("call tomorrow"))
                .andExpect(jsonPath("$.data.followUps[0].createdBy").value(manager))
                .andExpect(jsonPath("$.data.followUps[1].note").value("contacted"));
        action(id, "start", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("处理中"));
        action(id, "start", Map.of()).andExpect(status().isBadRequest());
        action(id, "complete", Map.of()).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.data.status").value("已完成"))
                .andExpect(jsonPath("$.data.overdue").value(false)).andExpect(jsonPath("$.data.overdueDays").value(0));
        rejectTerminalMutations(id);
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM task WHERE id=?", java.sql.Timestamp.class, id));
        assertEquals(manager, jdbc.queryForObject("SELECT u.username FROM task t JOIN app_user u ON u.id=t.updated_by WHERE t.id=?", String.class, id));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE target_type='TASK' AND target_id=? AND result='SUCCESS'", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE target_type='TASK' AND target_id=? AND operation_type='TASK_START'", Integer.class, id));
    }

    @Test
    void validatesEverySourceTypeAndCustomerScope() throws Exception {
        long service = jdbc.queryForObject("SELECT MIN(id) FROM business_service WHERE customer_id=?", Long.class, customerId);
        long expiry = jdbc.queryForObject("SELECT MIN(id) FROM contract_expiry WHERE customer_id=?", Long.class, customerId);
        for (var source : Map.of("BUSINESS_SERVICE", service, "CONTRACT_EXPIRY", expiry).entrySet()) {
            var payload = Map.of("title", "source-" + source.getKey(), "customerId", customerId, "deadline", LocalDate.now().toString(), "sourceType", source.getKey(), "sourceId", source.getValue());
            create(payload).andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(false));
            create(payload).andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(true));
        }
        Long outside = jdbc.queryForObject("SELECT MIN(id) FROM customer c WHERE c.customer_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM user_customer_scope s JOIN app_user u ON u.id=s.user_id WHERE s.customer_id=c.id AND u.username=?)", Long.class, manager);
        create(Map.of("title", "denied", "customerId", outside, "deadline", LocalDate.now().toString())).andExpect(status().isForbidden());
        long foreignBill = jdbc.queryForObject("SELECT MIN(id) FROM arrears_bill WHERE customer_id<>?", Long.class, customerId);
        create(Map.of("title", "wrong-source", "customerId", customerId, "deadline", LocalDate.now().toString(), "sourceType", "ARREARS_BILL", "sourceId", foreignBill)).andExpect(status().isBadRequest());
        long batch = jdbc.queryForObject("SELECT MIN(id) FROM import_batch", Long.class);
        String code = jdbc.queryForObject("SELECT customer_code FROM customer WHERE id=?", String.class, customerId);
        jdbc.update("INSERT INTO import_row(batch_id,source_row_number,customer_id,row_status,row_category) VALUES (?,999,?,'EXCLUDED','EXCLUDED')", batch, code);
        long row = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exception_record(source_row_id,exception_type,reason) VALUES (?,'TEST_ORPHAN','test')", row);
        long excluded = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        create(Map.of("title", "orphan", "customerId", customerId, "deadline", LocalDate.now().toString(), "sourceType", "EXCEPTION", "sourceId", excluded)).andExpect(status().isBadRequest());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentManualCreationReusesSameTask() throws Exception {
        String title = "manual-concurrent-" + UUID.randomUUID();
        var payload = Map.of("title", title, "customerId", customerId, "deadline", LocalDate.now().toString());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Long> operation = () -> {
                gate.await();
                return mapper.readTree(create(payload).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            gate.countDown();
            assertEquals(first.get(30, java.util.concurrent.TimeUnit.SECONDS), second.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE title=?", Integer.class, title));
        } finally {
            executor.shutdown();
            executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            jdbc.update("DELETE FROM operation_audit_log WHERE target_type='TASK' AND target_id IN (SELECT id FROM task WHERE title=?)", title);
            jdbc.update("DELETE FROM task WHERE title=?", title);
        }
    }

    @Test
    void sourceCreationReusesOpenTaskAndRejectsForeignOrOrphanSource() throws Exception {
        long bill = jdbc.queryForObject("SELECT MIN(id) FROM arrears_bill WHERE customer_id=?", Long.class, customerId);
        var payload = Map.of("title", "source-test", "customerId", customerId, "deadline", LocalDate.now().toString(), "sourceType", "ARREARS_BILL", "sourceId", bill);
        long id = mapper.readTree(create(payload).andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(false)).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        create(payload).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.reused").value(true));
        detail(id).andExpect(jsonPath("$.data.sourceType").value("ARREARS_BILL")).andExpect(jsonPath("$.data.sourceId").value(bill));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE idempotency_key=(SELECT idempotency_key FROM task WHERE id=?)", Integer.class, id));
        create(Map.of("title", "invalid", "customerId", customerId, "deadline", LocalDate.now().toString(), "sourceType", "ARREARS_BILL", "sourceId", Long.MAX_VALUE)).andExpect(status().isBadRequest());
        create(Map.of("title", "invalid", "customerId", customerId, "deadline", LocalDate.now().toString(), "sourceType", "EXCEPTION", "sourceId", Long.MAX_VALUE)).andExpect(status().isBadRequest());
        action(id, "complete", Map.of()).andExpect(status().isOk());
        create(payload).andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(false));
    }

    private ResultActions create(Map<?, ?> payload) throws Exception {
        return mvc.perform(post("/api/tasks").with(user(manager).roles("CUSTOMER_MANAGER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload)));
    }

    @Test
    void closeRequiresNormalizedReasonAndIsTerminal() throws Exception {
        long id = task(customerId, "待核实", LocalDate.now().minusDays(2), "中");
        action(id, "close", Map.of("reason", "  ")).andExpect(status().isBadRequest());
        action(id, "close", Map.of("reason", "x".repeat(1001))).andExpect(status().isBadRequest());
        action(id, "start", Map.of()).andExpect(status().isOk());
        action(id, "close", Map.of("reason", "  resolved  ")).andExpect(status().isOk());
        assertEquals("resolved", jdbc.queryForObject("SELECT close_reason FROM task WHERE id=?", String.class, id));
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM task WHERE id=?", java.sql.Timestamp.class, id));
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.overdue").value(false));
        rejectTerminalMutations(id);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE target_type='TASK' AND target_id=? AND operation_type='TASK_CLOSE'", Integer.class, id));
    }

    @Test
    void validatesFollowUpsAndCombinesPriorityAndOverdueFiltersWithoutDuplicateRows() throws Exception {
        long overdue = task(customerId, "待处理", LocalDate.now().minusDays(1), "高");
        long today = task(customerId, "待处理", LocalDate.now(), "高");
        task(customerId, "待处理", LocalDate.now().minusDays(1), "低");
        long completed = task(customerId, "已完成", LocalDate.now().minusDays(1), "高");
        action(overdue, "follow-ups", Map.of("note", " ", "nextAction", "\t")).andExpect(status().isBadRequest());
        action(overdue, "follow-ups", Map.of("note", "x".repeat(1001))).andExpect(status().isBadRequest());
        action(overdue, "follow-ups", Map.of("nextAction", "x".repeat(1001))).andExpect(status().isBadRequest());
        action(overdue, "follow-ups", Map.of("note", "x".repeat(1000))).andExpect(status().isOk());
        // Add a second customer grant in this rollback transaction to exercise the old admin join duplication.
        jdbc.update("INSERT INTO user_customer_scope(user_id,customer_id) SELECT id,? FROM app_user WHERE role='ADMIN' AND NOT EXISTS (SELECT 1 FROM user_customer_scope WHERE customer_id=? AND user_id=app_user.id) LIMIT 1", customerId, customerId);
        for (String username : new String[]{manager, "admin"}) {
            String role = username.equals(manager) ? "CUSTOMER_MANAGER" : "ADMIN";
            JsonNode rows = mapper.readTree(mvc.perform(get("/api/tasks").with(user(username).roles(role))
                            .param("owner", owner).param("priority", "高").param("overdue", "true"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            assertEquals(1, rows.size());
            assertEquals(overdue, rows.get(0).path("id").asLong());
            assertTrue(rows.get(0).path("overdue").asBoolean());
        }
        JsonNode rows = mapper.readTree(mvc.perform(get("/api/tasks").with(user(manager).roles("CUSTOMER_MANAGER"))
                        .param("owner", owner).param("priority", "高").param("overdue", "false"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(2, rows.size());
        assertTrue(rows.toString().contains(Long.toString(today)));
        assertTrue(rows.toString().contains(Long.toString(completed)));
    }

    @Test
    void scopeAndFormalCustomerAreEnforcedForEveryEndpoint() throws Exception {
        Long outside = jdbc.queryForObject("""
                SELECT c.id FROM customer c WHERE c.record_status='ACTIVE'
                AND c.customer_code IS NOT NULL AND c.customer_code<>''
                AND NOT EXISTS (SELECT 1 FROM user_customer_scope s JOIN app_user u ON u.id=s.user_id
                                WHERE s.customer_id=c.id AND u.username=?)
                ORDER BY c.id LIMIT 1
                """, Long.class, manager);
        long id = task(outside, "待处理", LocalDate.now(), "中");
        rejectScope(id, manager, "CUSTOMER_MANAGER");
        long orphan = task(null, "待处理", LocalDate.now(), "中");
        rejectScope(orphan, "admin", "ADMIN");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE task_id IN (?,?)", Integer.class, id, orphan));
        assertEquals("待处理", jdbc.queryForObject("SELECT task_status FROM task WHERE id=?", String.class, id));
    }

    private void rejectScope(long id, String username, String role) throws Exception {
        mvc.perform(get("/api/tasks/{id}", id).with(user(username).roles(role))).andExpect(status().isForbidden());
        for (String endpoint : new String[]{"start", "complete", "close", "follow-ups"}) {
            mvc.perform(post("/api/tasks/{id}/{endpoint}", id, endpoint).with(user(username).roles(role)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("note", "valid", "reason", "valid"))))
                    .andExpect(status().isForbidden());
        }
    }

    private void rejectTerminalMutations(long id) throws Exception {
        for (String endpoint : new String[]{"start", "complete", "close", "follow-ups"})
            action(id, endpoint, Map.of("reason", "valid", "note", "valid")).andExpect(status().isBadRequest());
    }

    private ResultActions detail(long id) throws Exception {
        return mvc.perform(get("/api/tasks/{id}", id).with(user(manager).roles("CUSTOMER_MANAGER")));
    }

    private ResultActions action(long id, String endpoint, Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/tasks/{id}/{endpoint}", id, endpoint).with(user(manager).roles("CUSTOMER_MANAGER"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private long task(Long customer, String state, LocalDate deadline, String priority) {
        String title = "lifecycle-" + UUID.randomUUID();
        jdbc.update("INSERT INTO task(customer_id,title,owner_name,deadline,task_status,priority,task_type) VALUES (?,?,?,?,?,?,'MANUAL')",
                customer, title, owner, deadline, state, priority);
        return jdbc.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title);
    }
}
