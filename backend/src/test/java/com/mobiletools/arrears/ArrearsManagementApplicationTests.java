package com.mobiletools.arrears;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest
class ArrearsManagementApplicationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void ledgerApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/arrears")).andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserReturnsRoleAndCsrfProtectsWrites() throws Exception {
        MvcResult login = login("huangling");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("huangling"))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER_MANAGER"));
        mockMvc.perform(patch("/api/arrears-work-orders/1/status").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"跟进中\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void disabledAccountCannotLoginAndFailureAuditContainsNoSecrets() throws Exception {
        String username = "disabled-test-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO app_user(username,real_name,password_hash,role,enabled) "
                + "SELECT ?, '测试停用账号',password_hash,'CUSTOMER_MANAGER',FALSE FROM app_user WHERE username='admin'", username);
        mockMvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        String detail = jdbcTemplate.queryForObject("SELECT a.detail FROM operation_audit_log a "
                + "JOIN app_user u ON u.id=a.operator_user_id WHERE u.username=? AND a.operation_type='LOGIN' AND a.result='FAILED'",
                String.class, username);
        assertFalse(detail.contains("123456"));
    }

    @Test
    @Transactional
    void disablingAccountInvalidatesExistingSession() throws Exception {
        MvcResult result = login("huangling");
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        jdbcTemplate.update("UPDATE app_user SET enabled=FALSE WHERE username='huangling'");
        mockMvc.perform(get("/api/arrears").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void adminCanLoginAndAccessLedgerApi() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        mockMvc.perform(get("/api/ledger-imports/batches").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void retiredModuleApisAreNoLongerRegistered() throws Exception {
        MockHttpSession session = (MockHttpSession) login("admin").getRequest().getSession(false);
        for (String path : new String[]{
                "/api/customers", "/api/imports", "/api/workbench", "/api/risk-rules",
                "/api/risk-recalculation", "/api/tasks"
        }) {
            mockMvc.perform(get(path).session(session)).andExpect(status().isNotFound());
        }
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MvcResult loginResult = login("admin");
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult login(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }
}
