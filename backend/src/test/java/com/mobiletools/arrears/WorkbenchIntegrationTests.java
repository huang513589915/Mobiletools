package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkbenchIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void summaryMatchesItemsAndScopeCannotBeExpandedByFilters() throws Exception {
        for (String name : new String[]{"admin", "huangling"}) {
            String role = name.equals("admin") ? "ADMIN" : "CUSTOMER_MANAGER";
            var items = mapper.readTree(mvc.perform(get("/api/workbench/items").with(user(name).roles(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            var summary = mapper.readTree(mvc.perform(get("/api/workbench/summary").with(user(name).roles(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            long pending=0,overdue=0;
            for (var item : items) {
                if ("TASK".equals(item.path("kind").asText())) { pending++; if(item.path("overdueDays").asLong()>0) overdue++; }
                if (!name.equals("admin")) assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_customer_scope s JOIN app_user u ON u.id=s.user_id WHERE u.username=? AND s.customer_id=?",Integer.class,name,item.path("customerId").asLong()));
            }
            assertEquals(pending,summary.path("pendingTasks").asLong());
            assertEquals(overdue,summary.path("overdueTasks").asLong());
        }
        var rows = mapper.readTree(mvc.perform(get("/api/workbench/items").param("owner","周敏").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(rows.isEmpty());
    }
    @Test
    void combinedFiltersAndInvalidDatesProduceClearResults() throws Exception {
        var rows = mapper.readTree(mvc.perform(get("/api/workbench/items").param("riskLevel","HIGH").param("owner","黄铃").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        for(var row:rows) { assertEquals("HIGH",row.path("riskLevel").asText()); assertEquals("黄铃",row.path("owner").asText()); }
        var outside = mapper.readTree(mvc.perform(get("/api/workbench/items").param("owner","不存在的负责人").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        assertTrue(outside.isEmpty());
        mvc.perform(get("/api/workbench/items").param("from","2026-12-01").param("to","2026-01-01").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workbench/items").param("riskLevel","INVALID").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        var empty = mapper.readTree(mvc.perform(get("/api/workbench/items").param("businessType","不存在的类型").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(empty.isEmpty());
    }

    @Test
    void customerLedgerAndBatchDetailEndpointsEnforceManagerScope() throws Exception {
        long customerId = jdbc.queryForObject("SELECT id FROM customer WHERE customer_code='C-10076'", Long.class);
        var managerRows = mapper.readTree(mvc.perform(get("/api/customers").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        assertTrue(java.util.stream.StreamSupport.stream(managerRows.spliterator(),false).noneMatch(row -> row.path("id").asLong()==customerId));
        var visibleRows = mapper.readTree(mvc.perform(get("/api/imports/1/rows").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(java.util.stream.StreamSupport.stream(visibleRows.spliterator(),false).allMatch(row -> !"C-10076".equals(row.path("customerCode").asText())));
        var filtered = mapper.readTree(mvc.perform(get("/api/imports/exceptions").param("batchId","4").param("sourceRowNumber","3").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(filtered.isEmpty());
        mvc.perform(get("/api/imports/4/exceptions").with(user("zhoumin").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk());
        var scopedExceptions = mapper.readTree(mvc.perform(get("/api/imports/4/exceptions").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        for (var row : scopedExceptions) assertNotEquals("C-10076",row.path("customerCode").asText());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void exceptionDetailsFilterByBatchSheetOriginalRowAndCustomerScope() throws Exception {
        long allowedRow=jdbc.queryForObject("SELECT id FROM import_row WHERE batch_id=4 AND customer_id='C-10281' ORDER BY source_row_number LIMIT 1",Long.class);
        long deniedRow=jdbc.queryForObject("SELECT id FROM import_row WHERE batch_id=4 AND customer_id='C-10076' ORDER BY source_row_number LIMIT 1",Long.class);
        jdbc.update("INSERT INTO exception_record(batch_id,source_row_id,exception_type,business_key,reason,status) VALUES (4,?,'IMPORT_VALIDATION_FAILED','E-ALLOWED','allowed row detail','OPEN')",allowedRow);
        long allowed=jdbc.queryForObject("SELECT id FROM exception_record WHERE business_key='E-ALLOWED'",Long.class);
        jdbc.update("INSERT INTO exception_record(batch_id,source_row_id,exception_type,business_key,reason,status) VALUES (4,?,'IMPORT_VALIDATION_FAILED','E-DENIED','private row detail','OPEN')",deniedRow);
        long denied=jdbc.queryForObject("SELECT id FROM exception_record WHERE business_key='E-DENIED'",Long.class);
        var visible=mapper.readTree(mvc.perform(get("/api/imports/exceptions").param("exceptionId",String.valueOf(allowed)).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        int allowedRowNumber=jdbc.queryForObject("SELECT source_row_number FROM import_row WHERE id=?",Integer.class,allowedRow);
        assertEquals(1,visible.size());assertEquals(4,visible.get(0).path("batchId").asLong());assertEquals(allowedRowNumber,visible.get(0).path("sourceRowNumber").asInt());assertEquals("C-10281",visible.get(0).path("customerCode").asText());
        String allowedKey=jdbc.queryForObject("SELECT business_key FROM import_row WHERE id=?",String.class,allowedRow);
        String allowedSheet=jdbc.queryForObject("SELECT sheet_name FROM import_row WHERE id=?",String.class,allowedRow);
        var exact=mapper.readTree(mvc.perform(get("/api/imports/4/rows").param("sheetName",allowedSheet).param("sourceRowNumber",String.valueOf(allowedRowNumber)).param("businessKey",allowedKey).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(1,exact.size());assertEquals(allowedRow,exact.get(0).path("id").asLong());assertEquals(allowedKey,exact.get(0).path("businessKey").asText());
        var exactException=mapper.readTree(mvc.perform(get("/api/imports/exceptions").param("batchId","4").param("sheetName",allowedSheet).param("sourceRowNumber",String.valueOf(allowedRowNumber)).param("q","E-ALLOWED").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(1,exactException.size());assertEquals(allowed,exactException.get(0).path("id").asLong());
        var hidden=mapper.readTree(mvc.perform(get("/api/imports/exceptions").param("exceptionId",String.valueOf(denied)).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(hidden.isEmpty());
        var row=mapper.readTree(mvc.perform(get("/api/imports/4/rows").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(java.util.stream.StreamSupport.stream(row.spliterator(),false).allMatch(item -> !"C-10076".equals(item.path("customerCode").asText())));
        var navigation=mapper.readTree(mvc.perform(get("/api/workbench/items").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(java.util.stream.StreamSupport.stream(navigation.spliterator(),false).noneMatch(item -> item.path("kind").asText().equals("EXCEPTION") && item.path("id").asLong()==denied));
        jdbc.update("INSERT INTO exception_record(exception_type,business_key,reason,status) VALUES ('IMPORT_VALIDATION_FAILED','E-ORPHAN','orphan row must remain admin-only','OPEN')");
        long orphan=jdbc.queryForObject("SELECT id FROM exception_record WHERE business_key='E-ORPHAN'",Long.class);
        var managerWorkbench=mapper.readTree(mvc.perform(get("/api/workbench/items").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(java.util.stream.StreamSupport.stream(managerWorkbench.spliterator(),false).noneMatch(item -> item.path("kind").asText().equals("EXCEPTION") && item.path("id").asLong()==orphan));
        var adminWorkbench=mapper.readTree(mvc.perform(get("/api/workbench/items").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(java.util.stream.StreamSupport.stream(adminWorkbench.spliterator(),false).anyMatch(item -> item.path("kind").asText().equals("EXCEPTION") && item.path("id").asLong()==orphan));
        assertTrue(mapper.readTree(mvc.perform(get("/api/imports/exceptions").param("exceptionId",String.valueOf(orphan)).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").isEmpty());
    }
}
