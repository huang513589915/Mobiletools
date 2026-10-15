package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import com.mobiletools.arrears.system.security.BootstrapAdminInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@Transactional
class AdminIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder passwords;
    @SpyBean private AdminAccess admin;
    // Application runners execute before test transactions; suppress bootstrap writes entirely.
    @MockBean private BootstrapAdminInitializer bootstrap;

    @Test
    void anonymousAndManagerRequestsAreRejectedAndDenialsAudited() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/risk-rules")).andExpect(status().isUnauthorized());
        long manager = createManager();
        String name = username(manager);
        int ruleCount = count("risk_rule");
        for (String path : List.of("/api/users", "/api/users/" + manager + "/scope", "/api/audit-logs", "/api/risk-rules")) {
            mvc.perform(get(path).with(user(name).roles("CUSTOMER_MANAGER"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/users").with(user(name).roles("CUSTOMER_MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
        mvc.perform(patch("/api/users/{id}", manager).with(user(name).roles("CUSTOMER_MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")) .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/{id}/scope", manager).with(user(name).roles("CUSTOMER_MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"customerIds\":[]}")) .andExpect(status().isForbidden());
        mvc.perform(post("/api/risk-rules").with(user(name).roles("CUSTOMER_MANAGER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(rule("R001", "LT", "600", true))) .andExpect(status().isForbidden());
        assertEquals(ruleCount, count("risk_rule"));
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE operator_user_id=? AND result='DENIED'", Integer.class, manager));
        mvc.perform(post("/api/users").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
    }

    @Test
    void createListEnableDisableUseBcryptAndExcludeSecrets() throws Exception {
        long id = createManager();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, id);
        assertTrue(hash.startsWith("$2"));
        assertTrue(passwords.matches("test-password-123", hash));
        MvcResult list = mvc.perform(asAdmin(get("/api/users"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + id + ")].role").value("CUSTOMER_MANAGER")).andReturn();
        assertFalse(list.getResponse().getContentAsString().contains("password"));
        for (boolean enabled : List.of(false, true)) {
            mvc.perform(asAdmin(patch("/api/users/{id}", id)).content("{\"enabled\":" + enabled + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(enabled));
            assertEquals(enabled, jdbc.queryForObject("SELECT enabled FROM app_user WHERE id=?", Boolean.class, id));
        }
        String details = jdbc.queryForObject("SELECT GROUP_CONCAT(detail) FROM operation_audit_log WHERE target_type='USER' AND target_id=?", String.class, id);
        assertFalse(details.contains("test-password-123"));
        assertFalse(details.contains(hash));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE target_type='USER' AND target_id=?", Integer.class, id));
    }

    @Test
    void invalidUsersAndDuplicateNamesDoNotCreateAccounts() throws Exception {
        long id = createManager();
        int before = count("app_user");
        mvc.perform(asAdmin(post("/api/users")).content(json.writeValueAsString(Map.of("username", username(id), "realName", "Duplicate", "role", "ADMIN", "password", "123456"))))
                .andExpect(status().isConflict());
        for (Map<String, String> body : List.of(
                Map.of("username", " ", "realName", "Test", "role", "ADMIN", "password", "123456"),
                Map.of("username", "invalid", "realName", " ", "role", "ADMIN", "password", "123456"),
                Map.of("username", "invalid", "realName", "Test", "role", "SUPERADMIN", "password", "123456"),
                Map.of("username", "invalid", "realName", "Test", "role", "ADMIN", "password", "short"),
                Map.of("username", "invalid", "realName", "Test", "role", "ADMIN", "password", "x".repeat(73)))) {
            mvc.perform(asAdmin(post("/api/users")).content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        }
        mvc.perform(asAdmin(patch("/api/users/{id}", id)).content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(asAdmin(patch("/api/users/{id}", Long.MAX_VALUE)).content("{\"enabled\":true}")) .andExpect(status().isNotFound());
        assertEquals(before, count("app_user"));
    }

    @Test
    void scopeReplacementImmediatelyChangesManagerQueriesAndCanBeCleared() throws Exception {
        long id = createManager();
        List<Long> customers = activeCustomers();
        assertTrue(customers.size() >= 2);
        mvc.perform(get("/api/customers").with(user(username(id)).roles("CUSTOMER_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        replaceScope(id, List.of(customers.get(0)));
        mvc.perform(asAdmin(get("/api/users/{id}/scope", id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(id)).andExpect(jsonPath("$.data.customerIds[0]").value(customers.get(0)));
        mvc.perform(get("/api/customers").with(user(username(id)).roles("CUSTOMER_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(customers.get(0)));
        replaceScope(id, List.of(customers.get(1)));
        assertEquals(List.of(customers.get(1)), scopeIds(id));
        assertEquals(adminId(), jdbc.queryForObject("SELECT created_by FROM user_customer_scope WHERE user_id=?", Long.class, id));
        replaceScope(id, List.of());
        assertTrue(scopeIds(id).isEmpty());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE operation_type='SCOPE_UPDATE' AND target_id=?", Integer.class, id));
    }

    @Test
    void invalidScopeLeavesExistingAssignmentsUntouched() throws Exception {
        long id = createManager();
        long customer = activeCustomers().get(0);
        replaceScope(id, List.of(customer));
        for (String body : List.of("{}", "{\"customerIds\":[null]}", "{\"customerIds\":[-1]}",
                "{\"customerIds\":[" + customer + "," + customer + "]}",
                "{\"customerIds\":[" + customer + ",9223372036854775807]}")) {
            mvc.perform(asAdmin(put("/api/users/{id}/scope", id)).content(body)).andExpect(status().isBadRequest());
            assertEquals(List.of(customer), scopeIds(id));
        }
        mvc.perform(asAdmin(put("/api/users/{id}/scope", adminId())).content("{\"customerIds\":[]}")) .andExpect(status().isBadRequest());
        mvc.perform(asAdmin(get("/api/users/{id}/scope", Long.MAX_VALUE))).andExpect(status().isNotFound());
    }

    @Test
    void disabledOrStaleAdminPrincipalCannotUseAdminEndpoints() throws Exception {
        long id = createAccount("ADMIN");
        String name = username(id);
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=?", id);
        mvc.perform(get("/api/users").with(user(name).roles("ADMIN"))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET enabled=TRUE,role='CUSTOMER_MANAGER' WHERE id=?", id);
        mvc.perform(get("/api/risk-rules").with(user(name).roles("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void ruleChangesPreserveHistoryAndHaveOneActiveVersion() throws Exception {
        for (String code : List.of("R001", "R002", "R003", "R004")) {
            String operator = code.equals("R001") ? "LT" : code.equals("R003") ? "LTE" : "GT";
            Map<String, Object> old = jdbc.queryForMap("SELECT * FROM risk_rule WHERE rule_code=? ORDER BY version DESC LIMIT 1", code);
            int oldVersion = ((Number) old.get("version")).intValue();
            MvcResult updated = mvc.perform(asAdmin(post("/api/risk-rules")).content(rule(code, operator, "0", true)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(oldVersion + 1))
                    .andExpect(jsonPath("$.data.operator").value(operator)).andExpect(jsonPath("$.data.threshold").value(0))
                    .andExpect(jsonPath("$.data.effectiveFrom").exists()).andReturn();
            long newId = data(updated).path("id").asLong();
            Map<String, Object> retired = jdbc.queryForMap("SELECT * FROM risk_rule WHERE id=?", old.get("id"));
            assertEquals(old.get("threshold_value"), retired.get("threshold_value"));
            assertEquals(old.get("operator"), retired.get("operator"));
            assertEquals(old.get("version"), retired.get("version"));
            assertEquals(old.get("effective_from"), retired.get("effective_from"));
            assertNotNull(retired.get("effective_to"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_rule WHERE rule_code=? AND is_enabled=TRUE", Integer.class, code));
            assertEquals(adminId(), jdbc.queryForObject("SELECT created_by FROM risk_rule WHERE id=?", Long.class, newId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE operation_type='RULE_UPDATE' AND target_id=?", Integer.class, newId));
            mvc.perform(asAdmin(post("/api/risk-rules")).content(rule(code, operator, "1", false)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(oldVersion + 2))
                    .andExpect(jsonPath("$.data.enabled").value(false));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_rule WHERE rule_code=? AND is_enabled=TRUE", Integer.class, code));
        }
        mvc.perform(asAdmin(get("/api/risk-rules"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.effectiveTo != null)]").isNotEmpty());
    }

    @Test
    void invalidRuleThresholdsAndOperatorsNeverCreateVersions() throws Exception {
        int before = count("risk_rule");
        for (String body : List.of("{}", rule("UNKNOWN", "LT", "500", true), rule("R001", "GT", "500", true),
                rule("R002", "GT", "-1", true), rule("R001", "LT", "1.001", true),
                rule("R003", "LTE", "1.5", true), rule("R004", "GT", "2147483648", true),
                rule("R001", "LT", "10000000000000000", true),
                "{\"code\":\"R001\",\"threshold\":500,\"enabled\":true}",
                "{\"code\":\"R001\",\"threshold\":500,\"operator\":\"LT\"}")) {
            mvc.perform(asAdmin(post("/api/risk-rules")).content(body)).andExpect(status().isBadRequest());
            assertEquals(before, count("risk_rule"));
        }
    }

    @Test
    void auditFiltersPaginationAndEmptyResultsAreConsistent() throws Exception {
        long id = createManager();
        for (int index = 0; index < 3; index++) {
            jdbc.update("INSERT INTO operation_audit_log(operator_user_id,operation_type,target_type,target_id,result,detail,created_at)"
                    + " VALUES (?,'ADMIN_TEST','USER',?,'SUCCESS',?,?)", adminId(), id, "record-" + index, "2026-01-02 03:04:05");
        }
        MvcResult first = mvc.perform(asAdmin(get("/api/audit-logs")).param("operationType", "ADMIN_TEST")
                .param("operatorUserId", adminId().toString()).param("targetType", "USER").param("targetId", Long.toString(id))
                .param("result", "SUCCESS").param("from", "2026-01-02T03:04:05").param("to", "2026-01-02T03:04:05")
                .param("pageSize", "2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items[0].operatorUsername").value("admin")).andReturn();
        MvcResult second = mvc.perform(asAdmin(get("/api/audit-logs")).param("operationType", "ADMIN_TEST")
                .param("targetId", Long.toString(id)).param("pageSize", "2").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andReturn();
        assertTrue(data(first).path("items").get(1).path("id").asLong() > data(second).path("items").get(0).path("id").asLong());
        mvc.perform(asAdmin(get("/api/audit-logs")).param("operationType", "ADMIN_TEST").param("targetId", Long.toString(id)).param("page", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(asAdmin(get("/api/audit-logs")).param("operationType", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        for (String size : List.of("0", "101")) {
            mvc.perform(asAdmin(get("/api/audit-logs")).param("pageSize", size)).andExpect(status().isBadRequest());
        }
        mvc.perform(asAdmin(get("/api/audit-logs")).param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(asAdmin(get("/api/audit-logs")).param("from", "2026-02-01T00:00:00").param("to", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditFailureRollsBackRuleVersionAndScopeWrites() throws Exception {
        List<Map<String, Object>> ruleBefore = jdbc.queryForList("SELECT * FROM risk_rule ORDER BY id");
        List<Map<String, Object>> scopeBefore = jdbc.queryForList("SELECT * FROM user_customer_scope ORDER BY id");
        long manager = jdbc.queryForObject("SELECT MIN(id) FROM app_user WHERE role='CUSTOMER_MANAGER'", Long.class);
        doThrow(new IllegalStateException("simulated audit failure")).when(admin).audit(anyLong(), eq("RULE_UPDATE"), anyString(), anyLong(), anyString(), anyString());
        assertThrows(Exception.class, () -> mvc.perform(asAdmin(post("/api/risk-rules")).content(rule("R001", "LT", "600", true))));
        doThrow(new IllegalStateException("simulated audit failure")).when(admin).audit(anyLong(), eq("SCOPE_UPDATE"), anyString(), anyLong(), anyString(), anyString());
        assertThrows(Exception.class, () -> mvc.perform(asAdmin(put("/api/users/{id}/scope", manager)).content("{\"customerIds\":[]}")));
        TestTransaction.end();
        assertEquals(ruleBefore, jdbc.queryForList("SELECT * FROM risk_rule ORDER BY id"));
        assertEquals(scopeBefore, jdbc.queryForList("SELECT * FROM user_customer_scope ORDER BY id"));
        TestTransaction.start();
    }

    private long createManager() throws Exception { return createAccount("CUSTOMER_MANAGER"); }

    private long createAccount(String role) throws Exception {
        String name = "admin-test-" + UUID.randomUUID();
        MvcResult result = mvc.perform(asAdmin(post("/api/users")).content(json.writeValueAsString(Map.of(
                "username", name, "realName", "Integration Test", "role", role, "password", "test-password-123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(name))
                .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn();
        return data(result).path("id").asLong();
    }

    private void replaceScope(long id, List<Long> customers) throws Exception {
        mvc.perform(asAdmin(put("/api/users/{id}/scope", id)).content(json.writeValueAsString(Map.of("customerIds", customers))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerIds.length()").value(customers.size()));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }

    private String rule(String code, String operator, String threshold, boolean enabled) throws Exception {
        return json.writeValueAsString(Map.of("code", code, "operator", operator, "threshold", new BigDecimal(threshold), "enabled", enabled));
    }

    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    private String username(long id) { return jdbc.queryForObject("SELECT username FROM app_user WHERE id=?", String.class, id); }
    private Long adminId() { return jdbc.queryForObject("SELECT id FROM app_user WHERE username='admin'", Long.class); }
    private List<Long> activeCustomers() { return jdbc.queryForList("SELECT id FROM customer WHERE record_status='ACTIVE' AND customer_code IS NOT NULL AND customer_code<>'' ORDER BY id", Long.class); }
    private List<Long> scopeIds(long id) { return jdbc.queryForList("SELECT customer_id FROM user_customer_scope WHERE user_id=? ORDER BY customer_id", Long.class, id); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
