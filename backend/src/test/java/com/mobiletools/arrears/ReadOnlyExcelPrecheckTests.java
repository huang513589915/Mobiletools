package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
class ReadOnlyExcelPrecheckTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void latestExcelDirectoryCanBePrecheckedRepeatedlyWithoutWrites() throws Exception {
        String directory=System.getProperty("precheck.excel.dir");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null&&!directory.isBlank(),"Set -Dprecheck.excel.dir to run the latest real-file precheck");
        List<Path> files;
        try(var stream=Files.list(Path.of(directory))) { files=stream.filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")).sorted().toList(); }
        assertFalse(files.isEmpty(),"No .xlsx files found in the supplied directory");
        Map<String,Integer> before=snapshot();
        List<String> diagnostics=new ArrayList<>();
        for(Path file:files) {
            byte[] bytes=Files.readAllBytes(file);
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var response=mvc.perform(multipart("/api/imports/precheck-readonly").file(new MockMultipartFile("file",file.getFileName().toString(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data=json.readTree(response).path("data");
            assertEquals(digest,data.path("fileSha256").asText());
            long rows=data.path("totalRows").asLong(); long insert=0,update=0,duplicate=0,excluded=0,invalid=0;
            for(var row:data.path("rows")) switch(row.path("classification").asText()) { case "INSERT" -> insert++; case "UPDATE" -> update++; case "DUPLICATE" -> duplicate++; case "EXCLUDED" -> excluded++; case "INVALID","CONFLICT" -> invalid++; default -> {} }
            System.out.printf("READ_ONLY_PRECHECK file=%s sha256=%s total=%d insert=%d update=%d duplicate=%d excluded=%d invalid=%d workbookErrors=%d%n",file.getFileName(),digest,rows,insert,update,duplicate,excluded,invalid,data.path("workbookErrors").size());
            for(var row:data.path("rows")) if(!row.path("classification").asText().equals("INSERT")&&!row.path("classification").asText().equals("UPDATE")&&!row.path("classification").asText().equals("DUPLICATE")) System.out.printf("ROW sheet=%s row=%d key=%s category=%s reason=%s%n",row.path("sheetName").asText(),row.path("rowNumber").asInt(),row.path("businessKey").asText(),row.path("classification").asText(),row.path("reason").asText());
            for(var row:data.path("rows")) if("预警规则配置".equals(row.path("sheetName").asText())) diagnostics.add(json.writeValueAsString(row.path("values")));
            assertEquals(rows,insert+update+duplicate+excluded+invalid);
        }
        assertEquals(before,snapshot(),"Read-only precheck must not alter batch or formal data counts");
        for(Path file:files) {
            byte[] bytes=Files.readAllBytes(file);
            mvc.perform(multipart("/api/imports/precheck-readonly").file(new MockMultipartFile("file",file.getFileName().toString(),"application/octet-stream",bytes)).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        }
        assertEquals(before,snapshot(),"Repeated read-only precheck must remain write-free");
        Files.writeString(Path.of("target","readonly-precheck-rule-values.jsonl"),String.join(System.lineSeparator(),diagnostics),java.nio.charset.StandardCharsets.UTF_8);
    }
    private Map<String,Integer> snapshot() {
        Map<String,Integer> result=new LinkedHashMap<>();
        for(String table:List.of("import_batch","import_row","exception_record","customer","business_service","arrears_bill","contract_expiry","task","follow_up","risk_result")) result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        return result;
    }
}
