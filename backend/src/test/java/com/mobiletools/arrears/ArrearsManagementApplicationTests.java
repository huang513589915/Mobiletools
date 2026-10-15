package com.mobiletools.arrears;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@AutoConfigureMockMvc
@SpringBootTest
class ArrearsManagementApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void businessApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserReturnsRoleAndCsrfProtectsWrites() throws Exception {
        MvcResult login = login("huangling");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("huangling"))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER_MANAGER"));
        mockMvc.perform(post("/api/tasks").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"CSRF校验\",\"deadline\":\"2026-10-20\",\"customerId\":5}"))
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
        jdbcTemplate.update("INSERT INTO app_user(username,real_name,password_hash,role,enabled) SELECT ?, '测试停用账号',password_hash,'CUSTOMER_MANAGER',FALSE FROM app_user WHERE username='admin'", username);
        mockMvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        String detail = jdbcTemplate.queryForObject("SELECT a.detail FROM operation_audit_log a JOIN app_user u ON u.id=a.operator_user_id WHERE u.username=? AND a.operation_type='LOGIN' AND a.result='FAILED'", String.class, username);
        org.junit.jupiter.api.Assertions.assertFalse(detail.contains("123456"));
    }

    @Test
    @Transactional
    void disablingAccountInvalidatesExistingSession() throws Exception {
        MvcResult result = login("huangling");
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        jdbcTemplate.update("UPDATE app_user SET enabled=FALSE WHERE username='huangling'");
        mockMvc.perform(get("/api/tasks").session(session)).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    }

    @Test
    void adminCanLoginAndAccessBusinessApi() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        mockMvc.perform(get("/api/customers").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.customerCode == 'C-10281')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.customerCode == null)]").isEmpty());
        mockMvc.perform(get("/api/tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task t JOIN customer c ON c.id=t.customer_id WHERE c.customer_code IS NOT NULL AND c.customer_code<>'' AND c.record_status='ACTIVE'", Integer.class)));
    }

    @Test
    void managerCanOnlyReadAssignedCustomersAndTasks() throws Exception {
        MvcResult login = login("huangling");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/api/customers").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.customerCode == 'C-10076')]").isEmpty());
        mockMvc.perform(get("/api/tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task t JOIN customer c ON c.id=t.customer_id WHERE c.customer_code IS NOT NULL AND c.customer_code<>'' AND c.record_status='ACTIVE' AND EXISTS (SELECT 1 FROM user_customer_scope s JOIN app_user u ON u.id=s.user_id WHERE s.customer_id=c.id AND u.username='huangling')", Integer.class)))
                .andExpect(jsonPath("$.data[?(@.customerCode == 'C-10076')]").isEmpty());
    }

    @Test
    void managerCannotCreateTaskForOutOfScopeCustomer() throws Exception {
        MvcResult login = login("huangling");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customer WHERE customer_code='C-10076'", Long.class);
        mockMvc.perform(post("/api/tasks").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"越权校验\",\"deadline\":\"2026-10-20\",\"customerId\":" + customerId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void managerCanCreateTaskForAssignedCustomer() throws Exception {
        MvcResult login = login("huangling");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customer WHERE customer_code='C-10281'", Long.class);
        mockMvc.perform(post("/api/tasks").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"自动化事务测试\",\"deadline\":\"2026-10-20\",\"customerId\":" + customerId + "}"))
                .andExpect(status().isOk());
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void importPreviewStagesRowsAndConfirmationWritesBusinessDataExactlyOnce() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customerCode = "C-T" + suffix;
        String serviceCode = "S-T" + suffix;
        String billCode = "B-T" + suffix;
        String taskCode = "T-T" + suffix;
        MvcResult login = login("admin");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Long batchId = null;
        try {
            MockMultipartFile file = new MockMultipartFile("file", "import-test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(customerCode, serviceCode, billCode, taskCode));
            MvcResult preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalRows").value(4))
                    .andExpect(jsonPath("$.data.validRows").value(4))
                    .andReturn();
            batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview.getResponse().getContentAsString())
                    .path("data").path("batchId").asLong();
            org.junit.jupiter.api.Assertions.assertEquals(4, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM import_row WHERE batch_id=? AND JSON_LENGTH(raw_payload)>0", Integer.class, batchId));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE customer_code=?", Integer.class, customerCode));

            String confirm = "{\"batchId\":" + batchId + ",\"rows\":[{\"classification\":\"VALID\",\"sheetName\":\"customer\",\"values\":{}}]}";
            mockMvc.perform(post("/api/imports/confirm").with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON).content(confirm))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.insertedRows").value(4));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE customer_code=?", Integer.class, customerCode));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM business_service WHERE service_code=?", Integer.class, serviceCode));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM arrears_bill WHERE bill_code=?", Integer.class, billCode));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM contract_expiry WHERE service_id=(SELECT id FROM business_service WHERE service_code=?)", Integer.class, serviceCode));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task WHERE source_record_code=?", Integer.class, taskCode));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM follow_up WHERE source_record_code=?", Integer.class, taskCode));
            mockMvc.perform(post("/api/imports/confirm").with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":" + batchId + "}"))
                    .andExpect(status().isBadRequest());
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE customer_code=?", Integer.class, customerCode));
        } finally {
            cleanupImportTest(batchId, customerCode, serviceCode, billCode, taskCode);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedImportConfirmationRollsBackFormalRowsAndCancelKeepsLedgerUntouched() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customerCode = "C-T" + suffix;
        String serviceCode = "S-T" + suffix;
        String billCode = "B-T" + suffix;
        String taskCode = "T-T" + suffix;
        MvcResult login = login("admin");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Long batchId = null;
        try {
            MockMultipartFile file = new MockMultipartFile("file", "rollback-test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(customerCode, serviceCode, billCode, taskCode));
            MvcResult preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()).session(session))
                    .andExpect(status().isOk()).andReturn();
            batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview.getResponse().getContentAsString())
                    .path("data").path("batchId").asLong();
            jdbcTemplate.update("UPDATE import_row SET raw_payload=JSON_OBJECT('账单编号', 'bad') WHERE batch_id=? AND sheet_name='欠费账单明细'", batchId);
            mockMvc.perform(post("/api/imports/confirm").with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":" + batchId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FAILED"));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE customer_code=?", Integer.class, customerCode));
            org.junit.jupiter.api.Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject(
                    "SELECT import_status FROM import_batch WHERE id=?", String.class, batchId));

            mockMvc.perform(post("/api/imports/{batchId}/cancel", batchId).with(csrf()).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE customer_code=?", Integer.class, customerCode));
        } finally {
            cleanupImportTest(batchId, customerCode, serviceCode, billCode, taskCode);
        }
    }

    private byte[] workbook(String customerCode, String serviceCode, String billCode, String taskCode) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addSheet(workbook, "客户基础信息", new String[]{"客户编号", "客户名称", "客户类型", "责任客户经理", "联系电话", "客户地址", "接入专线总数", "最近更新日期", "数据来源", "备注"},
                    new String[]{customerCode, "导入测试客户", "测试客户", "黄铃", "", "", "1", "2026-10-09", "自动化测试", ""});
            addSheet(workbook, "专线合同台账", new String[]{"业务编号", "客户编号", "业务类型", "业务带宽", "合同签订日", "服务开始日", "服务到期日", "年合同额(元)", "续签状态", "最近跟进日", "备注"},
                    new String[]{serviceCode, customerCode, "互联网专线", "100M", "2025-01-01", "2025-01-01", "2026-12-01", "12000", "待续签", "", ""});
            addSheet(workbook, "欠费账单明细", new String[]{"账单编号", "客户编号", "账期", "出账日期", "应缴金额(元)", "已缴金额(元)", "欠费金额(元)", "缴费截止日", "账单状态", "导入批次号"},
                    new String[]{billCode, customerCode, "2026-09", "2026-09-05", "1000", "200", "800", "2026-09-25", "部分缴费", suffixBatch(taskCode)});
            addSheet(workbook, "跟进催缴记录", new String[]{"记录编号", "客户编号", "关联业务编号", "任务类型", "任务标题", "责任人", "计划完成日", "任务状态", "处理结果", "记录时间"},
                    new String[]{taskCode, customerCode, serviceCode, "欠费催缴", "确认账款", "黄铃", "2026-10-20", "待处理", "", "2026-10-09"});
            Sheet rules = workbook.createSheet("预警规则配置");
            Row ruleHeader = rules.createRow(0);
            String[] ruleHeaders = {"规则编码", "规则名称", "阈值参数", "触发等级", "说明"};
            for (int i = 0; i < ruleHeaders.length; i++) ruleHeader.createCell(i).setCellValue(ruleHeaders[i]);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static String suffixBatch(String taskCode) { return taskCode.substring(3); }

    private static void addSheet(XSSFWorkbook workbook, String name, String[] headers, String[] values) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        Row row = sheet.createRow(1);
        for (int index = 0; index < headers.length; index++) {
            header.createCell(index).setCellValue(headers[index]);
            row.createCell(index).setCellValue(values[index]);
        }
    }

    private void cleanupImportTest(Long batchId, String customerCode, String serviceCode, String billCode, String taskCode) {
        jdbcTemplate.update("DELETE FROM user_customer_scope WHERE customer_id IN (SELECT id FROM customer WHERE customer_code=?)", customerCode);
        if (batchId != null && batchId > 0) {
            jdbcTemplate.update("DELETE FROM follow_up WHERE source_batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM task WHERE source_batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM contract_expiry WHERE source_batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM arrears_bill WHERE source_batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM business_service WHERE source_batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM customer WHERE customer_code=?", customerCode);
            jdbcTemplate.update("DELETE FROM exception_record WHERE batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM import_row WHERE batch_id=?", batchId);
            jdbcTemplate.update("DELETE FROM operation_audit_log WHERE target_type='IMPORT_BATCH' AND target_id=?", batchId);
            jdbcTemplate.update("DELETE FROM import_batch WHERE id=?", batchId);
        }
        jdbcTemplate.update("DELETE FROM task WHERE source_record_code=?", taskCode);
        jdbcTemplate.update("DELETE FROM arrears_bill WHERE bill_code=?", billCode);
        jdbcTemplate.update("DELETE FROM contract_expiry WHERE service_id IN (SELECT id FROM business_service WHERE service_code=?)", serviceCode);
        jdbcTemplate.update("DELETE FROM business_service WHERE service_code=?", serviceCode);
        jdbcTemplate.update("DELETE FROM customer WHERE customer_code=?", customerCode);
    }

    private MvcResult login(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }
}
