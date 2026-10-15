package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ImportAcceptanceTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    final String code = "C-IMPORT-" + UUID.randomUUID();
    final List<Long> batches = Collections.synchronizedList(new ArrayList<>());
    final List<Long> runs = new ArrayList<>();
    static final String[] CUSTOMER = {"客户编号","客户名称","客户类型","责任客户经理","联系电话","客户地址","接入专线总数","最近更新日期","数据来源","备注"};
    static final String[] BILL = {"账单编号","客户编号","账期","出账日期","应缴金额(元)","已缴金额(元)","欠费金额(元)","缴费截止日","账单状态","导入批次号"};
    static final String[] SERVICE = {"业务编号","客户编号","业务类型","业务带宽","合同签订日","服务开始日","服务到期日","年合同额(元)","续签状态","最近跟进日","备注"};
    static final String[] FOLLOW = {"记录编号","客户编号","关联业务编号","任务类型","任务标题","责任人","计划完成日","任务状态","处理结果","记录时间"};

    String[] customer() { return new String[]{code,"独立验收客户","测试","黄铃","13800000000","测试地址","1","2026-10-09","验收样例","保留备注"}; }
    String[] bill(String id, String due, String paid, String arrears) { return new String[]{id,code,"2026-09","2026-09-05",due,paid,arrears,"2026-09-25","部分缴费",code}; }
    byte[] file(String sheet, String[] headers, String[]... rows) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            sheet(book, sheet, headers, rows); book.write(out); return out.toByteArray();
        }
    }
    void sheet(XSSFWorkbook book, String name, String[] headers, String[]... values) {
        var sheet = book.createSheet(name);
        var header = sheet.createRow(0);
        for (int i=0;i<headers.length;i++) header.createCell(i).setCellValue(headers[i]);
        for (int n=0;n<values.length;n++) {
            var row=sheet.createRow(n+1);
            for (int i=0;i<values[n].length;i++) row.createCell(i).setCellValue(values[n][i]);
        }
    }
    JsonNode preview(byte[] bytes) throws Exception {
        var result=mvc.perform(multipart("/api/imports/preview").file(new MockMultipartFile("file",code+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        var data=mapper.readTree(result.getResponse().getContentAsString()).path("data");
        batches.add(data.path("batchId").asLong()); return data;
    }
    JsonNode confirm(long batch) throws Exception {
        var result=mvc.perform(post("/api/imports/confirm").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":"+batch+"}"))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
    long seed() throws Exception { long batch=preview(file("客户基础信息",CUSTOMER,customer())).path("batchId").asLong(); assertEquals("COMPLETED",confirm(batch).path("status").asText()); return jdbc.queryForObject("SELECT id FROM customer WHERE customer_code=?",Long.class,code); }

    @Test void sameFileAndBusinessRowsNeverDuplicate() throws Exception {
        seed();
        byte[] bytes=file("欠费账单明细",BILL,bill("B-1","1200","200","1000"),bill("B-1","1200","200","1000"));
        var first=preview(bytes);
        assertEquals("DUPLICATE",first.path("rows").get(1).path("classification").asText());
        assertEquals(1,confirm(first.path("batchId").asLong()).path("insertedRows").asInt());
        mvc.perform(multipart("/api/imports/preview").file(new MockMultipartFile("file","again.xlsx","application/octet-stream",bytes)).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        var repeat=preview(file("欠费账单明细",BILL,bill("B-1","1200","200","1000")));
        assertEquals("DUPLICATE",repeat.path("rows").get(0).path("classification").asText());
        assertEquals(1,confirm(repeat.path("batchId").asLong()).path("duplicateRows").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM arrears_bill WHERE customer_id=(SELECT id FROM customer WHERE customer_code=?)",Integer.class,code));
    }

    @Test void blanksPreserveValuesAndMoneyIsCalculatedOrRejected() throws Exception {
        long customerId=seed();
        var update=customer(); update[1]="修改后的名称"; update[4]=""; update[5]=""; update[6]=""; update[9]="";
        assertEquals("COMPLETED",confirm(preview(file("客户基础信息",CUSTOMER,update)).path("batchId").asLong()).path("status").asText());
        assertEquals("13800000000",jdbc.queryForObject("SELECT contact_phone FROM customer WHERE id=?",String.class,customerId));
        assertEquals("保留备注",jdbc.queryForObject("SELECT remarks FROM customer WHERE id=?",String.class,customerId));
        var data=preview(file("欠费账单明细",BILL,bill("CALC","1000","200",""),bill("NEG","-1","0",""),bill("OVER","100","101",""),bill("TEXT","text","0",""),bill("MISMATCH","100","0","99"),bill("PRECISION","1.001","0",""),bill("OVERFLOW","10000000000000000","0","")));
        assertEquals(1,data.path("validRows").asInt()); assertEquals(6,data.path("invalidRows").asInt());
        confirm(data.path("batchId").asLong());
        assertEquals(0,new java.math.BigDecimal("800").compareTo(jdbc.queryForObject("SELECT arrears_amount FROM arrears_bill WHERE customer_id=?",java.math.BigDecimal.class,customerId)));
    }

    @Test void orphanRowsStayExcludedAndAreTraceable() throws Exception {
        var orphan=bill("ORPHAN","1200","0","1200"); orphan[1]="C-10765";
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            sheet(book,"欠费账单明细",BILL,orphan);
            sheet(book,"跟进催缴记录",FOLLOW,new String[]{code,"C-10765","","欠费催缴","孤立样例","黄铃","2026-10-20","待处理","","2026-10-09"});
            sheet(book,"客户基础信息",CUSTOMER,customer());
            sheet(book,"专线合同台账",SERVICE);
            sheet(book,"预警规则配置",new String[]{"规则编码","规则名称","阈值参数","触发等级","说明"});
            book.write(out);bytes=out.toByteArray();
        }
        var data=preview(bytes);long batch=data.path("batchId").asLong();
        assertEquals(2,data.path("excludedRows").asInt());confirm(batch);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM arrears_bill WHERE source_batch_id=?",Integer.class,batch));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE source_batch_id=?",Integer.class,batch));
        mvc.perform(get("/api/imports/{id}/rows",batch).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sourceRowNumber").value(2));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM exception_record WHERE batch_id=? AND exception_type='CUSTOMER_ASSOCIATION_FAILED'",Integer.class,batch));
    }

    @Test void cancelledAndHeaderInvalidBatchesNeverWrite() throws Exception {
        long batch=preview(file("客户基础信息",CUSTOMER,customer())).path("batchId").asLong();
        mvc.perform(post("/api/imports/{id}/cancel",batch).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/imports/confirm").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":"+batch+"}")).andExpect(status().isBadRequest());
        var bad=preview(file("客户基础信息",new String[]{"错误表头"},new String[]{code}));
        assertFalse(bad.path("workbookErrors").isEmpty());
        mvc.perform(post("/api/imports/confirm").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":"+bad.path("batchId").asLong()+"}")).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_code=?",Integer.class,code));
    }

    @Test void formulasAndInvalidCustomerDoNotEnterLedger() throws Exception {
        seed();
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            sheet(book,"欠费账单明细",BILL,bill("FORMULA","1000","200","800"),bill("BAD-FORMULA","1000","0",""));
            book.getSheetAt(0).getRow(1).getCell(6).setCellFormula("E2-F2");
            book.getSheetAt(0).getRow(2).getCell(4).setCellFormula("1/0");
            book.write(out);bytes=out.toByteArray();
        }
        var data=preview(bytes);assertEquals(1,data.path("invalidRows").asInt());assertEquals(1,data.path("validRows").asInt());
        confirm(data.path("batchId").asLong());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM arrears_bill WHERE customer_id=(SELECT id FROM customer WHERE customer_code=?)",Integer.class,code));
    }

    @Test void nativeExcelDatesAreStoredAsBusinessDates() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            sheet(book,"客户基础信息",CUSTOMER,customer());
            var date=book.getSheetAt(0).getRow(1).getCell(7);
            date.setCellValue(java.time.LocalDate.of(2026,10,9));
            var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("m/d/yy"));date.setCellStyle(style);
            book.write(out);bytes=out.toByteArray();
        }
        var data=preview(bytes);assertEquals(1,data.path("validRows").asInt());
        assertEquals("COMPLETED",confirm(data.path("batchId").asLong()).path("status").asText());
        assertEquals("2026-10-09",jdbc.queryForObject("SELECT source_updated_date FROM customer WHERE customer_code=?",java.sql.Date.class,code).toString());
    }

    @Test void concurrentPreviewsCreateSingleBatch() throws Exception {
        byte[] bytes=file("客户基础信息",CUSTOMER,customer());
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<Integer> action=() -> {
            gate.await();
            var response=mvc.perform(multipart("/api/imports/preview").file(new MockMultipartFile("file",code+".xlsx","application/octet-stream",bytes)).with(user("admin").roles("ADMIN")).with(csrf())).andReturn().getResponse();
            if(response.getStatus()==200)batches.add(mapper.readTree(response.getContentAsString()).path("data").path("batchId").asLong());
            return response.getStatus();
        };
        try {var a=pool.submit(action);var b=pool.submit(action);gate.countDown();var statuses=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));assertTrue(statuses.contains(200));assertTrue(statuses.contains(400));assertEquals(1,batches.size());}
        finally {pool.shutdown();assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));}
    }

    @Test void serviceAndExpiryUpdatesKeepOptionalValuesAndIdentities() throws Exception {
        long customerId=seed();
        String[] service={"S-ACCEPT",code,"互联网专线","100M","2025-01-01","2025-01-01","2026-12-01","12000","待续签","2026-10-09","保留服务备注"};
        confirm(preview(file("专线合同台账",SERVICE,service)).path("batchId").asLong());
        service[2]="数据专线";service[3]="";service[7]="";service[8]="";service[9]="";service[10]="";
        confirm(preview(file("专线合同台账",SERVICE,service)).path("batchId").asLong());
        assertEquals("100M",jdbc.queryForObject("SELECT bandwidth FROM business_service WHERE customer_id=?",String.class,customerId));
        assertEquals("待续签",jdbc.queryForObject("SELECT renewal_status FROM contract_expiry WHERE customer_id=?",String.class,customerId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_service WHERE customer_id=?",Integer.class,customerId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contract_expiry WHERE customer_id=?",Integer.class,customerId));
    }

    @Test void conflictingBusinessRowsAreBothBlocked() throws Exception {
        seed();
        var data=preview(file("欠费账单明细",BILL,bill("CONFLICT","1200","0","1200"),bill("CONFLICT","1300","0","1300")));
        assertEquals(2,data.path("invalidRows").asInt());
        assertEquals("CONFLICT",data.path("rows").get(0).path("classification").asText());
        confirm(data.path("batchId").asLong());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM arrears_bill WHERE customer_id=(SELECT id FROM customer WHERE customer_code=?)",Integer.class,code));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void ruleImportVersionsAndRepeatsWithoutChangingFormalRules() throws Exception {
        int before=jdbc.queryForObject("SELECT MAX(version) FROM risk_rule WHERE rule_code='R001'",Integer.class);
        var data=preview(file("预警规则配置",new String[]{"规则编码","规则名称","阈值参数","触发等级","说明"},new String[]{"R001","余额预警","余额 < 499","中风险","事务内验收"}));
        confirm(data.path("batchId").asLong());
        assertEquals(before+1,jdbc.queryForObject("SELECT MAX(version) FROM risk_rule WHERE rule_code='R001'",Integer.class));
        var again=preview(file("预警规则配置",new String[]{"规则编码","规则名称","阈值参数","触发等级","说明"},new String[]{"R001","余额预警","余额 < 499","中风险","不同文件相同规则"}));
        assertEquals("DUPLICATE",again.path("rows").get(0).path("classification").asText());
        confirm(again.path("batchId").asLong());
        assertEquals(before+1,jdbc.queryForObject("SELECT MAX(version) FROM risk_rule WHERE rule_code='R001'",Integer.class));
    }

    @Test void lateFailureRollsBackAndRetainsExactErrorRow() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            sheet(book,"客户基础信息",CUSTOMER,customer());
            sheet(book,"欠费账单明细",BILL,bill("FAIL","1000","0","1000"));
            sheet(book,"专线合同台账",SERVICE);sheet(book,"跟进催缴记录",FOLLOW);
            sheet(book,"预警规则配置",new String[]{"规则编码","规则名称","阈值参数","触发等级","说明"});
            book.write(out);bytes=out.toByteArray();
        }
        long batch=preview(bytes).path("batchId").asLong();
        jdbc.update("UPDATE import_row SET raw_payload=JSON_OBJECT('账单编号','broken') WHERE batch_id=? AND sheet_name='欠费账单明细'",batch);
        assertEquals("FAILED",confirm(batch).path("status").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_code=?",Integer.class,code));
        assertEquals(1,jdbc.queryForObject("SELECT failed_rows FROM import_batch WHERE id=?",Integer.class,batch));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM import_row WHERE batch_id=? AND source_row_number=2 AND sheet_name='欠费账单明细' AND error_code='WRITE_FAILED'",Integer.class,batch));
        mvc.perform(get("/api/imports/{id}/exceptions",batch).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reason").isNotEmpty());
    }

    @Test void concurrentConfirmAndDifferentBatchesRemainIdempotent() throws Exception {
        seed();long first=preview(file("欠费账单明细",BILL,bill("CONCURRENT","1200","0","1200"))).path("batchId").asLong();
        var other=bill("CONCURRENT","1200","0","1200");other[8]="未结清";
        long second=preview(file("欠费账单明细",BILL,other)).path("batchId").asLong();
        var pool=Executors.newFixedThreadPool(3);var gate=new CountDownLatch(1);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(long batch:new long[]{first,first,second}) results.add(pool.submit(() -> {gate.await();return mvc.perform(post("/api/imports/confirm").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":"+batch+"}")).andReturn().getResponse().getStatus();}));
            gate.countDown();List<Integer> statuses=new ArrayList<>();for(var result:results)statuses.add(result.get(30,TimeUnit.SECONDS));
            assertEquals(2,Collections.frequency(statuses,200));assertEquals(1,Collections.frequency(statuses,400));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM arrears_bill WHERE customer_id=(SELECT id FROM customer WHERE customer_code=?)",Integer.class,code));
        } finally {pool.shutdown();assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));}
    }

    @Test void importRiskTaskFollowUpAndClosePreserveHistory() throws Exception {
        long customerId=seed();
        confirm(preview(file("欠费账单明细",BILL,bill("FLOW","1200","0","1200"))).path("batchId").asLong());
        for(int i=0;i<2;i++) {
            var result=mvc.perform(post("/api/risk-recalculation").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"customerIds\":["+customerId+"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED")).andReturn();
            runs.add(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("runId").asLong());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?",Integer.class,customerId));
        long task=jdbc.queryForObject("SELECT id FROM task WHERE customer_id=?",Long.class,customerId);
        mvc.perform(get("/api/tasks/{id}",task).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/{id}",task).with(user("zhoumin").roles("CUSTOMER_MANAGER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/{id}/follow-ups",task).with(user("huangling").roles("CUSTOMER_MANAGER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已联系客户\",\"nextAction\":\"等待缴费\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/{id}/close",task).with(user("huangling").roles("CUSTOMER_MANAGER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验收流程关闭\"}")).andExpect(status().isOk());
        var again=mvc.perform(post("/api/risk-recalculation").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"customerIds\":["+customerId+"]}")).andExpect(status().isOk()).andReturn();
        runs.add(mapper.readTree(again.getResponse().getContentAsString()).path("data").path("runId").asLong());
        assertEquals("已关闭",jdbc.queryForObject("SELECT task_status FROM task WHERE id=?",String.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE task_id=?",Integer.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE customer_id=?",Integer.class,customerId));
    }

    @Test void generateIndependentBrowserFixtures() throws Exception {
        var directory = java.nio.file.Path.of("target", "import-browser-fixtures");
        java.nio.file.Files.createDirectories(directory);
        try (var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            sheet(book,"跟进催缴记录",FOLLOW,new String[]{"T-"+code,code,"S-"+code,"欠费催缴","浏览器导入跟进任务","黄铃",java.time.LocalDate.now().plusDays(3).toString(),"待处理","","2026-10-09 10:30:00"});
            sheet(book,"欠费账单明细",BILL,bill("B-"+code,"1200","0","1200"),new String[]{"ORPHAN-"+code,"C-10765","2026-09","2026-09-05","1000","0","1000","2026-09-25","未结清",code},new String[]{"INVALID-"+code,code,"2026-09","2026-09-05","-1","0","-1","2026-09-25","未结清",code});
            sheet(book,"专线合同台账",SERVICE,new String[]{"S-"+code,code,"互联网专线","100M","2025-01-01","2025-01-01",java.time.LocalDate.now().plusDays(10).toString(),"12000","待续签","",""});
            sheet(book,"客户基础信息",CUSTOMER,customer());
            sheet(book,"预警规则配置",new String[]{"规则编码","规则名称","阈值参数","触发等级","说明"});
            book.write(out);java.nio.file.Files.write(directory.resolve("valid.xlsx"),out.toByteArray());
        }
        java.nio.file.Files.write(directory.resolve("bad-header.xlsx"),file("客户基础信息",new String[]{"错误表头"},new String[]{code}));
        java.nio.file.Files.writeString(directory.resolve("customer-code.txt"),code);
    }

    @Test void invalidCustomerInWorkbookCannotAuthorizeAssociatedBill() throws Exception {
        var invalid=customer();invalid[1]="";
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            sheet(book,"客户基础信息",CUSTOMER,invalid);
            sheet(book,"欠费账单明细",BILL,bill("INVALID-CUSTOMER","1000","0","1000"));
            sheet(book,"专线合同台账",SERVICE);sheet(book,"跟进催缴记录",FOLLOW);
            sheet(book,"预警规则配置",new String[]{"规则编码","规则名称","阈值参数","触发等级","说明"});
            book.write(out);bytes=out.toByteArray();
        }
        var data=preview(bytes);assertEquals(1,data.path("invalidRows").asInt());assertEquals(1,data.path("excludedRows").asInt());
        confirm(data.path("batchId").asLong());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_code=?",Integer.class,code));
    }

    @AfterEach void cleanupOwnedFixtures() {
        var customers=jdbc.queryForList("SELECT id FROM customer WHERE customer_code=?",Long.class,code);
        for(long customer:customers) {
            var tasks=jdbc.queryForList("SELECT id FROM task WHERE customer_id=?",Long.class,customer);
            for(long task:tasks) {jdbc.update("DELETE FROM operation_audit_log WHERE target_type='TASK' AND target_id=?",task);jdbc.update("DELETE FROM follow_up WHERE task_id=?",task);}
            jdbc.update("DELETE FROM task WHERE customer_id=?",customer);jdbc.update("DELETE FROM risk_result WHERE customer_id=?",customer);
            jdbc.update("DELETE FROM contract_expiry WHERE customer_id=?",customer);jdbc.update("DELETE FROM arrears_bill WHERE customer_id=?",customer);
            jdbc.update("DELETE FROM business_service WHERE customer_id=?",customer);jdbc.update("DELETE FROM user_customer_scope WHERE customer_id=?",customer);
            jdbc.update("DELETE FROM customer WHERE id=?",customer);
        }
        for(long run:runs) {jdbc.update("DELETE FROM exception_record WHERE business_key LIKE ?","RISK_RUN:"+run+":%");jdbc.update("DELETE FROM operation_audit_log WHERE target_type='RISK_RUN' AND target_id=?",run);jdbc.update("DELETE FROM risk_recalculation_run WHERE id=?",run);}
        for(long batch:batches) {jdbc.update("DELETE FROM exception_record WHERE batch_id=?",batch);jdbc.update("DELETE FROM import_row WHERE batch_id=?",batch);jdbc.update("DELETE FROM operation_audit_log WHERE target_type='IMPORT_BATCH' AND target_id=?",batch);jdbc.update("DELETE FROM import_batch WHERE id=?",batch);}
    }
}
