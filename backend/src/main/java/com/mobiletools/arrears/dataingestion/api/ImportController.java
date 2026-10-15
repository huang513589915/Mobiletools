package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import com.mobiletools.arrears.risk.api.RiskRuleController;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private static final Map<String, List<String>> CONTRACT_HEADERS = Map.of(
            "客户基础信息", List.of("客户编号", "客户名称", "客户类型", "责任客户经理", "联系电话", "客户地址", "接入专线总数", "最近更新日期", "数据来源", "备注"),
            "欠费账单明细", List.of("账单编号", "客户编号", "账期", "出账日期", "应缴金额(元)", "已缴金额(元)", "欠费金额(元)", "缴费截止日", "账单状态", "导入批次号"),
            "专线合同台账", List.of("业务编号", "客户编号", "业务类型", "业务带宽", "合同签订日", "服务开始日", "服务到期日", "年合同额(元)", "续签状态", "最近跟进日", "备注"),
            "跟进催缴记录", List.of("记录编号", "客户编号", "关联业务编号", "任务类型", "任务标题", "责任人", "计划完成日", "任务状态", "处理结果", "记录时间"),
            "预警规则配置", List.of("规则编码", "规则名称", "阈值参数", "触发等级", "说明")
    );
    private static final Map<String, Set<String>> REQUIRED_FIELDS = Map.of(
            "客户基础信息", Set.of("客户编号", "客户名称", "责任客户经理", "最近更新日期"),
            "欠费账单明细", Set.of("账单编号", "客户编号", "账期", "出账日期", "应缴金额(元)", "已缴金额(元)", "缴费截止日", "导入批次号"),
            "专线合同台账", Set.of("业务编号", "客户编号", "业务类型", "合同签订日", "服务开始日", "服务到期日"),
            "跟进催缴记录", Set.of("记录编号", "客户编号", "任务类型", "任务标题", "责任人", "计划完成日", "记录时间")
    );
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RiskRuleController riskRules;

    public ImportController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RiskRuleController riskRules) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.riskRules = riskRules;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ApiResponse<PreviewResponse> preview(@RequestPart("file") MultipartFile file, Authentication authentication) {
        requireAdmin(authentication);
        lockImports();
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("导入文件不能为空");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            String contentHash = sha256(file.getBytes());
            Integer alreadyImported = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM import_batch WHERE file_sha256=? AND import_status NOT IN ('CANCELLED','FAILED')",
                    Integer.class, contentHash);
            if (alreadyImported != null && alreadyImported > 0) {
                throw new IllegalArgumentException("相同内容的文件已经预检或正式导入，不允许重复创建批次");
            }
            Set<String> knownCustomers = knownCustomerCodes();
            knownCustomers.addAll(customerCodesInWorkbook(workbook, evaluator));
            List<RowPreview> rows = new ArrayList<>();
            List<String> workbookErrors = new ArrayList<>();
            if (workbook.getNumberOfSheets() != 1 && workbook.getNumberOfSheets() != 5) {
                workbookErrors.add("请上传一个业务工作表，或包含全部五类工作表的标准工作簿");
            }
            Set<String> contracts = new HashSet<>();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Row header = sheet.getRow(sheet.getFirstRowNum());
                String contract = resolveContract(sheet.getSheetName(), readCells(header, evaluator));
                List<String> expectedHeaders = CONTRACT_HEADERS.get(contract);
                if (expectedHeaders == null) {
                    workbookErrors.add("不支持的工作表：" + sheet.getSheetName());
                    continue;
                }
                if (!contracts.add(contract)) workbookErrors.add("工作表类型重复：" + contract);
                List<String> actualHeaders = readCells(header, evaluator);
                if (!normalizedHeaders(expectedHeaders).equals(actualHeaders)) {
                    workbookErrors.add(contract + "表头不匹配，期望 " + normalizedHeaders(expectedHeaders) + "，实际 " + actualHeaders);
                    continue;
                }
                for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null || isBlank(row, evaluator)) continue;
                    rows.add(validateRow(contract, rowIndex + 1, row, knownCustomers, evaluator));
                }
            }
            if (workbook.getNumberOfSheets() == 5 && !contracts.containsAll(CONTRACT_HEADERS.keySet())) {
                workbookErrors.add("标准工作簿缺少必需业务工作表");
            }
            classifyRows(rows);
            Set<String> acceptedCustomers = knownCustomerCodes();
            rows.stream().filter(r -> "客户基础信息".equals(r.sheetName()) && !Set.of("INVALID", "CONFLICT").contains(r.classification()))
                    .forEach(r -> acceptedCustomers.add(r.customerCode()));
            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                if (Set.of("欠费账单明细", "专线合同台账", "跟进催缴记录").contains(r.sheetName()) && !acceptedCustomers.contains(r.customerCode())) {
                    rows.set(i, new RowPreview(r.sheetName(), r.rowNumber(), r.businessKey(), r.customerCode(), r.customerName(), "EXCLUDED", "客户关联失败，正式客户台账不存在 " + r.customerCode(), "CUSTOMER_NOT_FOUND", r.values()));
                }
            }
            String username = authentication.getName();
            jdbcTemplate.update(
                    """
                    INSERT INTO import_batch(file_name, source_type, file_sha256, idempotency_key,
                        import_status, total_rows, valid_rows, invalid_rows, excluded_rows,
                        started_at, operator_user_id)
                    VALUES (?, 'EXCEL', ?, ?, 'PRECHECKED', ?, ?, ?, ?, NOW(),
                        (SELECT id FROM app_user WHERE username=?))
                    """,
                    file.getOriginalFilename(), contentHash, contentHash, rows.size(),
                    writableCount(rows), countBy(rows, "INVALID") + countBy(rows, "CONFLICT"), countBy(rows, "EXCLUDED"), username);
            Long batchId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            for (RowPreview row : rows) {
                Long rowId = insertImportRow(batchId, row, row.classification());
                if (Set.of("INVALID", "EXCLUDED", "CONFLICT").contains(row.classification())) {
                    jdbcTemplate.update(
                            "INSERT INTO exception_record(batch_id, source_row_id, exception_type, business_key, reason, status) VALUES (?, ?, ?, ?, ?, 'OPEN')",
                            batchId, rowId,
                            "EXCLUDED".equals(row.classification()) ? "CUSTOMER_ASSOCIATION_FAILED" : "IMPORT_VALIDATION_FAILED",
                            row.businessKey(), row.reason());
                }
            }
            for (String error : workbookErrors) {
                jdbcTemplate.update("INSERT INTO exception_record(batch_id,exception_type,reason,status) VALUES (?,'WORKBOOK_VALIDATION_FAILED',?,'OPEN')", batchId, error);
            }
            return ApiResponse.success(new PreviewResponse(
                    batchId, file.getOriginalFilename(), contentHash, rows.size(),
                    writableCount(rows),
                    rows.stream().filter(row -> "EXCLUDED".equals(row.classification())).count(),
                    countBy(rows, "INVALID") + countBy(rows, "CONFLICT"),
                    workbookErrors, rows));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Excel 文件解析失败：" + exception.getMessage(), exception);
        }
    }

    @PostMapping(value = "/precheck-readonly", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReadOnlyPrecheckResponse> precheckReadOnly(@RequestPart("file") MultipartFile file, Authentication authentication) {
        requireAdmin(authentication);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Excel 文件不能为空");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Set<String> knownCustomers = knownCustomerCodes();
            knownCustomers.addAll(customerCodesInWorkbook(workbook, evaluator));
            List<RowPreview> rows = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            if (workbook.getNumberOfSheets() != 1 && workbook.getNumberOfSheets() != 5) errors.add("请上传一个业务工作表，或包含全部五类工作表的标准工作簿");
            Set<String> contracts = new HashSet<>();
            for (int i=0;i<workbook.getNumberOfSheets();i++) {
                Sheet sheet=workbook.getSheetAt(i); Row header=sheet.getRow(sheet.getFirstRowNum());
                List<String> actual=readCells(header,evaluator); String contract=resolveContract(sheet.getSheetName(),actual);
                List<String> expected=CONTRACT_HEADERS.get(contract);
                if (expected==null) { errors.add("不支持的工作表："+sheet.getSheetName()); continue; }
                if (!contracts.add(contract)) errors.add("工作表类型重复："+contract);
                if (!normalizedHeaders(expected).equals(actual)) { errors.add(contract+"表头不匹配，期望 "+normalizedHeaders(expected)+"，实际 "+actual); continue; }
                for(int n=sheet.getFirstRowNum()+1;n<=sheet.getLastRowNum();n++) {
                    Row row=sheet.getRow(n); if(row==null||isBlank(row,evaluator)) continue;
                    rows.add(validateRow(contract,n+1,row,knownCustomers,evaluator));
                }
            }
            if(workbook.getNumberOfSheets()==5&&!contracts.containsAll(CONTRACT_HEADERS.keySet())) errors.add("标准工作簿缺少必需业务工作表");
            classifyRows(rows);
            Set<String> acceptedCustomers=knownCustomerCodes();
            rows.stream().filter(r->"客户基础信息".equals(r.sheetName())&&!Set.of("INVALID","CONFLICT").contains(r.classification())).forEach(r->acceptedCustomers.add(r.customerCode()));
            for(int i=0;i<rows.size();i++) { RowPreview r=rows.get(i); if(Set.of("欠费账单明细","专线合同台账","跟进催缴记录").contains(r.sheetName())&&!acceptedCustomers.contains(r.customerCode())) rows.set(i,new RowPreview(r.sheetName(),r.rowNumber(),r.businessKey(),r.customerCode(),r.customerName(),"EXCLUDED","客户关联失败，正式客户台账不存在 "+r.customerCode(),"CUSTOMER_NOT_FOUND",r.values())); }
            String hash=sha256(file.getBytes());
            return ApiResponse.success(new ReadOnlyPrecheckResponse(file.getOriginalFilename(),hash,rows.size(),writableCount(rows),countBy(rows,"EXCLUDED"),countBy(rows,"INVALID")+countBy(rows,"CONFLICT"),errors,rows));
        } catch(IOException ex) { throw new IllegalArgumentException("Excel 文件解析失败："+ex.getMessage(),ex); }
    }

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ApiResponse<Map<String, Object>> confirm(@RequestBody ConfirmRequest request, Authentication authentication) {
        requireAdmin(authentication);
        lockImports();
        if (request == null || request.batchId() == null) throw new IllegalArgumentException("必须提供预检批次编号");
        String username = authentication.getName();
        var matches = jdbcTemplate.queryForList("SELECT id,file_name,file_sha256,import_status FROM import_batch WHERE id=? FOR UPDATE", request.batchId());
        if (matches.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        Map<String, Object> batch = matches.get(0);
        if (!"PRECHECKED".equals(batch.get("import_status"))) {
            throw new IllegalArgumentException("批次不是待确认状态，不能重复确认");
        }
        Long batchId = request.batchId();
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exception_record WHERE batch_id=? AND exception_type='WORKBOOK_VALIDATION_FAILED'", Integer.class, batchId) > 0) {
            throw new IllegalArgumentException("工作簿或表头校验失败，不能确认，请修改文件重新预检");
        }
        jdbcTemplate.update("UPDATE import_batch SET import_status='RUNNING', started_at=NOW() WHERE id=?", batchId);

        int inserted = 0;
        int updated = 0;
        int duplicate = 0;
        List<Map<String, Object>> storedRows = jdbcTemplate.queryForList(
                "SELECT id, sheet_name, source_row_number, business_key, customer_id, customer_name, row_category, error_message, error_code, raw_payload FROM import_row WHERE batch_id=? ORDER BY FIELD(sheet_name,'客户基础信息','专线合同台账','欠费账单明细','跟进催缴记录','预警规则配置'),id", batchId);
        var transaction = TransactionAspectSupport.currentTransactionStatus();
        Object checkpoint = transaction.createSavepoint();
        Long failingRow = null;
        try {
        for (Map<String, Object> stored : storedRows) {
            failingRow = ((Number) stored.get("id")).longValue();
            String classification = String.valueOf(stored.get("row_category"));
            String rowStatus = classification;
            if (Set.of("VALID", "INSERT", "UPDATE").contains(classification)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> values = objectMapper.readValue(String.valueOf(stored.get("raw_payload")), Map.class);
                    RowPreview row = new RowPreview(String.valueOf(stored.get("sheet_name")), ((Number) stored.get("source_row_number")).intValue(),
                            (String) stored.get("business_key"), (String) stored.get("customer_id"), (String) stored.get("customer_name"),
                            classification, (String) stored.get("error_message"), (String) stored.get("error_code"), values);
                    WriteResult result = writeFormalRow(row, batchId, authentication);
                    rowStatus = result.status();
                    inserted += result.inserted();
                    updated += result.updated();
                    duplicate += result.duplicate();
                } catch (JsonProcessingException exception) {
                    throw new IllegalArgumentException("导入行原始数据无法读取", exception);
                }
            }
            if ("DUPLICATE".equals(classification)) duplicate++;
            jdbcTemplate.update("UPDATE import_row SET row_status=? WHERE id=?", rowStatus, stored.get("id"));
        }
        jdbcTemplate.update("""
                UPDATE import_batch
                SET import_status='COMPLETED', inserted_rows=?, updated_rows=?, duplicate_rows=?,
                    finished_at=NOW()
                WHERE id=?
                """, inserted, updated, duplicate, batchId);
        jdbcTemplate.update("INSERT INTO operation_audit_log(operator_user_id, operation_type, target_type, target_id, result, detail) VALUES ((SELECT id FROM app_user WHERE username=?), 'EXCEL_IMPORT_CONFIRM', 'IMPORT_BATCH', ?, 'SUCCESS', ?)",
                username, batchId, "正式业务记录已写入；无效和孤立行保留为异常明细");
        return ApiResponse.success(Map.of(
                "batchId", batchId,
                "status", "COMPLETED",
                "insertedRows", inserted,
                "updatedRows", updated,
                "duplicateRows", duplicate,
                "message", "导入确认完成，正式业务台账已更新"));
        } catch (RuntimeException exception) {
            transaction.rollbackToSavepoint(checkpoint);
            String reason = exception instanceof IllegalArgumentException ? exception.getMessage() : "数据库写入失败，请核查字段和约束";
            reason = reason == null ? "导入失败" : reason.substring(0, Math.min(reason.length(), 450));
            jdbcTemplate.update("UPDATE import_batch SET import_status='FAILED',failed_rows=1,inserted_rows=0,updated_rows=0,duplicate_rows=0,finished_at=NOW() WHERE id=?", batchId);
            jdbcTemplate.update("UPDATE import_row SET row_status='FAILED',error_code='WRITE_FAILED',error_message=? WHERE id=?", reason, failingRow);
            jdbcTemplate.update("INSERT INTO exception_record(batch_id,source_row_id,exception_type,reason,status) VALUES (?,?,'IMPORT_WRITE_FAILED',?,'OPEN')", batchId, failingRow, reason);
            jdbcTemplate.update("INSERT INTO operation_audit_log(operator_user_id,operation_type,target_type,target_id,result,detail) VALUES ((SELECT id FROM app_user WHERE username=?),'EXCEL_IMPORT_CONFIRM','IMPORT_BATCH',?,'FAILED',?)", username, batchId, "整批正式写入已回滚；" + reason);
            return ApiResponse.success(Map.of("batchId", batchId, "status", "FAILED", "insertedRows", 0, "updatedRows", 0, "duplicateRows", 0, "message", "整批正式写入已回滚：" + reason));
        } finally {
            transaction.releaseSavepoint(checkpoint);
        }
    }

    @PostMapping("/{batchId}/cancel")
    @Transactional
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long batchId, Authentication authentication) {
        requireAdmin(authentication);
        int updated = jdbcTemplate.update(
                "UPDATE import_batch SET import_status='CANCELLED', finished_at=NOW() WHERE id=? AND import_status IN ('PRECHECKED','FAILED')",
                batchId);
        if (updated == 0) throw new IllegalArgumentException("批次不存在或已经确认，不能取消");
        jdbcTemplate.update("INSERT INTO operation_audit_log(operator_user_id, operation_type, target_type, target_id, result, detail) VALUES ((SELECT id FROM app_user WHERE username=?), 'EXCEL_IMPORT_CANCEL', 'IMPORT_BATCH', ?, 'SUCCESS', ?)",
                authentication.getName(), batchId, "预检批次已取消，正式业务台账未修改");
        return ApiResponse.success(Map.of("batchId", batchId, "status", "CANCELLED"));
    }

    private Long insertImportRow(Long batchId, RowPreview row, String rowStatus) {
        String payload;
        try {
            payload = row.values() == null ? null : objectMapper.writeValueAsString(row.values());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("导入行原始数据无法保存", exception);
        }
        jdbcTemplate.update(
                """
                INSERT INTO import_row(batch_id, sheet_name, source_row_number, business_key,
                    customer_id, customer_name, row_status, row_category, error_message, error_code, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batchId, row.sheetName(), row.rowNumber(), row.businessKey(),
                row.customerCode(), row.customerName(), rowStatus,
                row.classification(), row.reason(), row.errorCode(), payload);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private WriteResult writeFormalRow(RowPreview row, Long batchId, Authentication authentication) {
        Map<String, String> values = row.values() == null ? Map.of() : row.values();
        var existing = existingValues(row);
        if (!existing.isEmpty() && matches(row, existing.get(0))) return new WriteResult("DUPLICATE", 0, 0, 1);
        return switch (row.sheetName()) {
            case "客户基础信息" -> writeCustomer(values, batchId);
            case "专线合同台账" -> writeService(values, batchId);
            case "欠费账单明细" -> writeBill(values, batchId);
            case "跟进催缴记录" -> writeTask(values, batchId, authentication.getName());
            case "预警规则配置" -> writeRule(values, authentication);
            default -> throw new IllegalArgumentException("不支持的业务工作表：" + row.sheetName());
        };
    }

    private WriteResult writeCustomer(Map<String, String> values, Long batchId) {
        String code = required(values, "客户编号");
        Long id = findId("SELECT id FROM customer WHERE customer_code=?", code);
        if (id == null) {
            jdbcTemplate.update("""
                    INSERT INTO customer(customer_code, customer_name, manager_name, business_type, risk_status,
                        due_summary, source_batch_id, contact_phone, customer_address, service_count,
                        source_updated_date, data_source, remarks)
                    VALUES (?, ?, ?, ?, '待核查', '暂无到期信息', ?, ?, ?, ?, ?, ?, ?)
                    """, code, required(values, "客户名称"), required(values, "责任客户经理"),
                    blankOr(values.get("客户类型"), "未分类"), batchId, blankToNull(values.get("联系电话")),
                    blankToNull(values.get("客户地址")), integer(values.get("接入专线总数")),
                    parseDate(values.get("最近更新日期")), blankToNull(values.get("数据来源")), blankToNull(values.get("备注")));
            id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            assignManager(id, values.get("责任客户经理"));
            return new WriteResult("INSERTED", 1, 0, 0);
        }
        jdbcTemplate.update("""
                UPDATE customer SET customer_name=COALESCE(NULLIF(?, ''), customer_name),
                    manager_name=COALESCE(NULLIF(?, ''), manager_name),
                    business_type=COALESCE(NULLIF(?, ''), business_type),
                    source_batch_id=?, contact_phone=COALESCE(NULLIF(?, ''), contact_phone),
                    customer_address=COALESCE(NULLIF(?, ''), customer_address),
                    service_count=COALESCE(?, service_count), source_updated_date=COALESCE(?, source_updated_date),
                    data_source=COALESCE(NULLIF(?, ''), data_source), remarks=COALESCE(NULLIF(?, ''), remarks)
                WHERE id=?
                """, values.getOrDefault("客户名称", ""), values.getOrDefault("责任客户经理", ""),
                values.getOrDefault("客户类型", ""), batchId, values.getOrDefault("联系电话", ""),
                values.getOrDefault("客户地址", ""), integer(values.get("接入专线总数")),
                parseDate(values.get("最近更新日期")), values.getOrDefault("数据来源", ""),
                values.getOrDefault("备注", ""), id);
        assignManager(id, values.get("责任客户经理"));
        return new WriteResult("UPDATED", 0, 1, 0);
    }

    private void assignManager(Long customer, String name) {
        var users = jdbcTemplate.queryForList("SELECT id FROM app_user WHERE real_name=? AND role='CUSTOMER_MANAGER' AND enabled=1", Long.class, name);
        Long manager = users.size() == 1 ? users.get(0) : null;
        jdbcTemplate.update("UPDATE customer SET manager_user_id=? WHERE id=?", manager, customer);
        if (manager != null) jdbcTemplate.update("INSERT IGNORE INTO user_customer_scope(user_id,customer_id) VALUES (?,?)", manager, customer);
    }

    private WriteResult writeRule(Map<String, String> values, Authentication auth) {
        String code = required(values, "规则编码");
        String operator = switch (code) { case "R001" -> "LT"; case "R003" -> "LTE"; default -> "GT"; };
        riskRules.update(auth, new RiskRuleController.RuleUpdate(code, ruleThreshold(code, values.get("阈值参数")), operator, true));
        return new WriteResult("UPDATED", 0, 1, 0);
    }

    private void lockImports() {
        // Serialize import matching before any snapshot reads, including concurrent batches.
        jdbcTemplate.queryForList("SELECT id FROM app_user WHERE role='ADMIN' ORDER BY id FOR UPDATE", Long.class);
    }

    private long writableCount(List<RowPreview> rows) {
        return rows.stream().filter(r -> !Set.of("INVALID", "EXCLUDED", "CONFLICT").contains(r.classification())).count();
    }

    private void classifyRows(List<RowPreview> rows) {
        Map<String, Map<String, String>> seen = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            RowPreview row = rows.get(i);
            if (!"VALID".equals(row.classification())) continue;
            Map<String, String> v = row.values();
            String key = row.sheetName() + ":" + row.customerCode() + ":" + row.businessKey()
                    + ("欠费账单明细".equals(row.sheetName()) ? ":" + v.get("账期") : "")
                    + ("专线合同台账".equals(row.sheetName()) ? ":" + v.get("服务到期日") : "");
            String category;
            String reason = null;
            var previous = seen.putIfAbsent(key, v);
            if (previous != null) {
                category = previous.equals(v) ? "DUPLICATE" : "CONFLICT";
                if ("CONFLICT".equals(category)) {
                    reason = "同批次同一业务标识存在不同内容，请核查";
                    for (int j = 0; j < i; j++) {
                        var earlier = rows.get(j);
                        if (earlier.sheetName().equals(row.sheetName()) && earlier.customerCode().equals(row.customerCode())
                                && earlier.businessKey().equals(row.businessKey()) && earlier.values().equals(previous)) {
                            rows.set(j, new RowPreview(earlier.sheetName(), earlier.rowNumber(), earlier.businessKey(), earlier.customerCode(), earlier.customerName(), "CONFLICT", reason, "BUSINESS_CONFLICT", earlier.values()));
                        }
                    }
                }
            } else {
                var existing = existingValues(row);
                category = existing.isEmpty() ? "INSERT" : matches(row, existing.get(0)) ? "DUPLICATE" : "UPDATE";
                if ("跟进催缴记录".equals(row.sheetName()) && "UPDATE".equals(category)) {
                    category = "CONFLICT";
                    reason = "已存在跟进记录，不覆盖人工处理状态和历史";
                }
            }
            rows.set(i, new RowPreview(row.sheetName(), row.rowNumber(), row.businessKey(), row.customerCode(), row.customerName(), category, reason, reason == null ? null : "BUSINESS_CONFLICT", v));
        }
    }

    private List<Map<String, Object>> existingValues(RowPreview r) {
        var v = r.values();
        return switch (r.sheetName()) {
            case "客户基础信息" -> jdbcTemplate.queryForList("SELECT customer_name AS `客户名称`,manager_name AS `责任客户经理`,business_type AS `客户类型`,contact_phone AS `联系电话`,customer_address AS `客户地址`,service_count AS `接入专线总数`,source_updated_date AS `最近更新日期`,data_source AS `数据来源`,remarks AS `备注` FROM customer WHERE customer_code=?", r.businessKey());
            case "欠费账单明细" -> jdbcTemplate.queryForList("SELECT b.due_amount AS `应缴金额(元)`,b.paid_amount AS `已缴金额(元)`,b.arrears_amount AS `欠费金额(元)`,b.bill_date AS `出账日期`,b.due_date AS `缴费截止日`,b.bill_status AS `账单状态`,b.external_batch_code AS `导入批次号` FROM arrears_bill b JOIN customer c ON c.id=b.customer_id WHERE c.customer_code=? AND b.bill_code=? AND b.billing_period=?", r.customerCode(), r.businessKey(), v.get("账期"));
            case "专线合同台账" -> jdbcTemplate.queryForList("SELECT s.service_type AS `业务类型`,s.bandwidth AS `业务带宽`,s.contract_signed_date AS `合同签订日`,s.service_start_date AS `服务开始日`,s.annual_amount AS `年合同额(元)`,s.last_follow_up_date AS `最近跟进日`,s.remarks AS `备注`,e.renewal_status AS `续签状态` FROM business_service s JOIN customer c ON c.id=s.customer_id JOIN contract_expiry e ON e.service_id=s.id WHERE c.customer_code=? AND s.service_code=? AND e.expiry_date=?", r.customerCode(), r.businessKey(), v.get("服务到期日"));
            case "跟进催缴记录" -> jdbcTemplate.queryForList("SELECT t.task_type AS `任务类型`,t.title AS `任务标题`,t.owner_name AS `责任人`,t.deadline AS `计划完成日`,t.task_status AS `任务状态`,t.processing_result AS `处理结果` FROM task t JOIN customer c ON c.id=t.customer_id WHERE c.customer_code=? AND t.source_record_code=?", r.customerCode(), r.businessKey());
            case "预警规则配置" -> jdbcTemplate.queryForList("SELECT threshold_value AS `阈值参数` FROM risk_rule WHERE rule_code=? AND is_enabled=1 AND effective_to IS NULL ORDER BY version DESC LIMIT 1", r.businessKey());
            default -> List.of();
        };
    }

    private boolean matches(Map<String, String> values, Map<String, Object> existing) { return matches(null, values, existing); }

    private boolean matches(RowPreview row, Map<String, Object> existing) { return matches(row, row.values(), existing); }

    private boolean matches(RowPreview row, Map<String, String> values, Map<String, Object> existing) {
        for (var entry : existing.entrySet()) {
            String incoming = values.get(entry.getKey());
            if (blank(incoming)) continue;
            Object current = entry.getValue();
            if (current == null) return false;
            if (current instanceof Number) {
                try {
                    BigDecimal normalized = row != null && "预警规则配置".equals(row.sheetName()) && "阈值参数".equals(entry.getKey())
                            ? ruleThreshold(row.businessKey(), incoming) : new BigDecimal(incoming);
                    if (normalized.compareTo(new BigDecimal(current.toString())) != 0) return false;
                }
                catch (NumberFormatException ex) { return false; }
                catch (IllegalArgumentException ex) { return false; }
            } else if (!incoming.equals(current.toString())) return false;
        }
        return true;
    }

    private WriteResult writeService(Map<String, String> values, Long batchId) {
        Long customerId = customerId(required(values, "客户编号"));
        String code = required(values, "业务编号");
        Long serviceId = findId("SELECT id FROM business_service WHERE customer_id=? AND service_code=?", customerId, code);
        boolean inserted = serviceId == null;
        if (serviceId == null) {
            jdbcTemplate.update("""
                    INSERT INTO business_service(customer_id, service_code, service_type, bandwidth, contract_signed_date,
                        service_start_date, annual_amount, last_follow_up_date, remarks, source_batch_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, customerId, code, required(values, "业务类型"), blankToNull(values.get("业务带宽")),
                    parseDate(values.get("合同签订日")), parseDate(values.get("服务开始日")), money(values.get("年合同额(元)")),
                    parseDate(values.get("最近跟进日")), blankToNull(values.get("备注")), batchId);
            serviceId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            jdbcTemplate.update("UPDATE business_service SET service_type=COALESCE(NULLIF(?, ''),service_type), bandwidth=COALESCE(NULLIF(?, ''),bandwidth), contract_signed_date=COALESCE(?,contract_signed_date), service_start_date=COALESCE(?,service_start_date), annual_amount=COALESCE(?,annual_amount), last_follow_up_date=COALESCE(?,last_follow_up_date), remarks=COALESCE(NULLIF(?, ''),remarks), source_batch_id=? WHERE id=?",
                    values.getOrDefault("业务类型", ""), values.getOrDefault("业务带宽", ""), parseDate(values.get("合同签订日")),
                    parseDate(values.get("服务开始日")), money(values.get("年合同额(元)")), parseDate(values.get("最近跟进日")),
                    values.getOrDefault("备注", ""), batchId, serviceId);
        }
        LocalDate expiryDate = parseDate(values.get("服务到期日"));
        Long expiryId = findId("SELECT id FROM contract_expiry WHERE service_id=? AND expiry_date=?", serviceId, expiryDate);
        if (expiryId == null) {
            jdbcTemplate.update("INSERT INTO contract_expiry(customer_id, service_id, expiry_date, renewal_status, source_batch_id) VALUES (?, ?, ?, ?, ?)", customerId, serviceId, expiryDate, blankOr(values.get("续签状态"), "待续签"), batchId);
        } else {
            jdbcTemplate.update("UPDATE contract_expiry SET renewal_status=COALESCE(NULLIF(?, ''),renewal_status), source_batch_id=? WHERE id=?", values.getOrDefault("续签状态", ""), batchId, expiryId);
        }
        return new WriteResult(inserted ? "INSERTED" : "UPDATED", inserted ? 1 : 0, inserted ? 0 : 1, 0);
    }

    private WriteResult writeBill(Map<String, String> values, Long batchId) {
        Long customerId = customerId(required(values, "客户编号"));
        String code = required(values, "账单编号");
        String period = required(values, "账期");
        BigDecimal due = requiredMoney(values.get("应缴金额(元)")), paid = requiredMoney(values.get("已缴金额(元)"));
        BigDecimal arrears = due.subtract(paid);
        if (arrears.signum() < 0) throw new IllegalArgumentException("已缴金额不能大于应缴金额");
        Long id = findId("SELECT id FROM arrears_bill WHERE customer_id=? AND bill_code=? AND billing_period=?", customerId, code, period);
        if (id == null) {
            jdbcTemplate.update("INSERT INTO arrears_bill(customer_id,bill_code,billing_period,due_amount,paid_amount,arrears_amount,bill_date,due_date,source_batch_id,bill_status,external_batch_code) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    customerId, code, period, due, paid, arrears, parseDate(values.get("出账日期")), parseDate(values.get("缴费截止日")), batchId, blankToNull(values.get("账单状态")), blankToNull(values.get("导入批次号")));
            return new WriteResult("INSERTED", 1, 0, 0);
        }
        jdbcTemplate.update("UPDATE arrears_bill SET due_amount=?,paid_amount=?,arrears_amount=?,bill_date=COALESCE(?,bill_date),due_date=COALESCE(?,due_date),source_batch_id=?,bill_status=COALESCE(NULLIF(?,''),bill_status),external_batch_code=COALESCE(NULLIF(?,''),external_batch_code) WHERE id=?",
                due, paid, arrears, parseDate(values.get("出账日期")), parseDate(values.get("缴费截止日")), batchId,
                values.getOrDefault("账单状态", ""), values.getOrDefault("导入批次号", ""), id);
        return new WriteResult("UPDATED", 0, 1, 0);
    }

    private WriteResult writeTask(Map<String, String> values, Long batchId, String username) {
        Long customerId = customerId(required(values, "客户编号"));
        String code = required(values, "记录编号");
        Long existing = findId("SELECT id FROM task WHERE customer_id=? AND source_record_code=?", customerId, code);
        if (existing != null) return new WriteResult("DUPLICATE", 0, 0, 1);
        Long serviceId = null;
        if (!blank(values.get("关联业务编号"))) {
            serviceId = findId("SELECT id FROM business_service WHERE customer_id=? AND service_code=?", customerId, values.get("关联业务编号"));
            if (serviceId == null) throw new IllegalArgumentException("关联业务不存在或不属于该客户");
        }
        jdbcTemplate.update("INSERT INTO task(customer_id,service_id,source_type,source_record_code,source_batch_id,task_type,title,owner_name,deadline,task_status,priority,created_by,processing_result,source_recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                customerId, serviceId, "EXCEL", code, batchId, required(values, "任务类型"), required(values, "任务标题"), required(values, "责任人"),
                parseDate(values.get("计划完成日")), blankOr(values.get("任务状态"), "待处理"), "普通", findId("SELECT id FROM app_user WHERE username=?", username),
                blankToNull(values.get("处理结果")), parseDateTime(values.get("记录时间")));
        Long taskId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO follow_up(task_id, note, created_by, created_by_user_id, source_record_code, source_batch_id) VALUES (?, ?, ?, (SELECT id FROM app_user WHERE username=?), ?, ?)",
                taskId, blankOr(values.get("处理结果"), "导入跟进记录"), username, username, code, batchId);
        return new WriteResult("INSERTED", 1, 0, 0);
    }
    @GetMapping
    public ApiResponse<List<ImportBatchResponse>> list(Authentication authentication) {
        requireAdmin(authentication);
        return ApiResponse.success(jdbcTemplate.query(
                """
                SELECT id, file_name, source_type, import_status, total_rows, valid_rows,
                    invalid_rows, excluded_rows, inserted_rows, updated_rows, duplicate_rows, failed_rows, created_at
                FROM import_batch ORDER BY id DESC
                """,
                (rs, rowNum) -> new ImportBatchResponse(
                        rs.getLong("id"), rs.getString("file_name"), rs.getString("source_type"),
                        rs.getString("import_status"), rs.getInt("total_rows"), rs.getInt("valid_rows"),
                        rs.getInt("invalid_rows"), rs.getInt("excluded_rows"), rs.getInt("inserted_rows"), rs.getInt("updated_rows"), rs.getInt("duplicate_rows"), rs.getInt("failed_rows"),
                        rs.getTimestamp("created_at").toLocalDateTime())));
    }

    @GetMapping("/{batchId}/rows")
    public ApiResponse<List<ImportRowResponse>> rows(@PathVariable long batchId, @RequestParam(required=false) String sheetName,
            @RequestParam(required=false) Integer sourceRowNumber, @RequestParam(required=false) String businessKey, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        String scope = admin ? "" : " AND EXISTS (SELECT 1 FROM customer c JOIN user_customer_scope s ON s.customer_id=c.id JOIN app_user u ON u.id=s.user_id WHERE c.customer_code=ir.customer_id AND c.record_status='ACTIVE' AND u.username=?)";
        if(sourceRowNumber!=null&&sourceRowNumber<1) throw new IllegalArgumentException("原始行号必须为正数");
        var args = new ArrayList<Object>(); args.add(batchId); if(!admin) args.add(authentication.getName());
        if(sheetName!=null&&!sheetName.isBlank()) args.add(sheetName);
        if(sourceRowNumber!=null) args.add(sourceRowNumber);
        if(businessKey!=null&&!businessKey.isBlank()) args.add(businessKey);
        String filters=""+(sheetName!=null&&!sheetName.isBlank()?" AND ir.sheet_name=?":"")+(sourceRowNumber!=null?" AND ir.source_row_number=?":"")+(businessKey!=null&&!businessKey.isBlank()?" AND ir.business_key=?":"");
        return ApiResponse.success(jdbcTemplate.query("""
                SELECT ir.id,ir.batch_id,ir.sheet_name,ir.source_row_number,ir.business_key,ir.customer_id,
                       ir.row_status,ir.row_category,ir.error_message,ir.error_code,ir.raw_payload,ir.created_at
                FROM import_row ir WHERE ir.batch_id=?
                """ + scope + filters + " ORDER BY ir.source_row_number,ir.id LIMIT 5000", (rs, n) -> {
                    Map<String,String> values = Map.of();
                    try { if (rs.getString("raw_payload") != null) values = objectMapper.readValue(rs.getString("raw_payload"), new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {}); }
                    catch (JsonProcessingException ex) { throw new IllegalArgumentException("导入行内容无法读取", ex); }
                    return new ImportRowResponse(rs.getLong("id"),rs.getLong("batch_id"), rs.getString("sheet_name"),
                rs.getInt("source_row_number"), rs.getString("business_key"), rs.getString("customer_id"),
                rs.getString("row_status"), rs.getString("row_category"), rs.getString("error_message"),
                rs.getString("error_code"),values,rs.getTimestamp("created_at").toLocalDateTime());
                }, args.toArray()));
    }

    @GetMapping("/{batchId}/preview")
    public ApiResponse<PreviewResponse> savedPreview(@PathVariable long batchId, Authentication authentication) {
        requireAdmin(authentication);
        var found = jdbcTemplate.queryForList("SELECT file_name,file_sha256,import_status FROM import_batch WHERE id=?", batchId);
        if (found.isEmpty() || !"PRECHECKED".equals(found.get(0).get("import_status"))) throw new IllegalArgumentException("批次不存在或不在待确认状态");
        var rows = jdbcTemplate.query("SELECT sheet_name,source_row_number,business_key,customer_id,customer_name,row_category,error_message,error_code,raw_payload FROM import_row WHERE batch_id=? ORDER BY id", (rs,n) -> {
            try {
                Map<String,String> values = objectMapper.readValue(rs.getString("raw_payload"), new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {});
                return new RowPreview(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),values);
            } catch (JsonProcessingException ex) { throw new IllegalArgumentException("预检数据无法读取", ex); }
        }, batchId);
        var errors = jdbcTemplate.queryForList("SELECT reason FROM exception_record WHERE batch_id=? AND exception_type='WORKBOOK_VALIDATION_FAILED'", String.class, batchId);
        return ApiResponse.success(new PreviewResponse(batchId, (String)found.get(0).get("file_name"), (String)found.get(0).get("file_sha256"), rows.size(), writableCount(rows), countBy(rows,"EXCLUDED"), countBy(rows,"INVALID")+countBy(rows,"CONFLICT"), errors, rows));
    }

    @GetMapping("/{batchId}/exceptions")
    public ApiResponse<List<ExceptionResponse>> exceptions(@PathVariable long batchId, Authentication authentication) {
        return allExceptions(null,batchId,null,null,null,null,authentication);
    }

    @GetMapping("/exceptions")
    public ApiResponse<List<ExceptionResponse>> allExceptions(@RequestParam(required=false) String status,
            @RequestParam(required=false) Long batchId, @RequestParam(required=false) String sheetName,
            @RequestParam(required=false) Integer sourceRowNumber, @RequestParam(required=false) Long exceptionId,
            @RequestParam(required=false) String q, Authentication authentication) {
        if (status != null && !Set.of("OPEN","RESOLVED","IGNORED").contains(status)) throw new IllegalArgumentException("异常状态无效");
        if ((batchId != null && batchId <= 0) || (sourceRowNumber != null && sourceRowNumber <= 0) || (exceptionId != null && exceptionId <= 0)) throw new IllegalArgumentException("批次、异常和原始行号必须为正数");
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        String scope = admin ? "" : " JOIN customer scope_customer ON scope_customer.id=COALESCE((SELECT c.id FROM customer c WHERE c.customer_code=ir.customer_id AND c.record_status='ACTIVE'),CASE WHEN er.exception_type LIKE 'RISK_%' AND er.business_key LIKE '%:CUSTOMER:%' THEN CAST(SUBSTRING_INDEX(er.business_key,':CUSTOMER:',-1) AS UNSIGNED) END) JOIN user_customer_scope s ON s.customer_id=scope_customer.id AND s.user_id=(SELECT id FROM app_user WHERE username=?)";
        var args = new ArrayList<Object>();
        if (!admin) args.add(authentication.getName());
        String filters = " WHERE 1=1";
        if (status != null) { filters += " AND er.status=?"; args.add(status); }
        if (batchId != null) { filters += " AND er.batch_id=?"; args.add(batchId); }
        if (sourceRowNumber != null) { filters += " AND ir.source_row_number=?"; args.add(sourceRowNumber); }
        if (exceptionId != null) { filters += " AND er.id=?"; args.add(exceptionId); }
        if (sheetName != null && !sheetName.isBlank()) { filters += " AND ir.sheet_name=?"; args.add(sheetName); }
        if (q != null && !q.isBlank()) { filters += " AND (er.business_key LIKE ? OR er.reason LIKE ? OR ir.customer_id LIKE ?)"; String pattern="%"+q.trim()+"%"; args.add(pattern); args.add(pattern); args.add(pattern); }
        return ApiResponse.success(jdbcTemplate.query("""
                SELECT DISTINCT er.id, er.source_row_id, er.batch_id, ir.sheet_name, ir.source_row_number,
                       ir.customer_id AS customer_code, er.exception_type, er.business_key, er.reason, er.status,
                       er.resolution, er.handled_at, er.created_at
                FROM exception_record er LEFT JOIN import_row ir ON ir.id=er.source_row_id
                """ + scope + filters + " ORDER BY er.created_at DESC,er.id DESC LIMIT 1000", (rs, n) -> new ExceptionResponse(rs.getLong("id"), rs.getObject("source_row_id", Long.class),
                rs.getObject("batch_id", Long.class), rs.getString("sheet_name"), (Integer)rs.getObject("source_row_number"), rs.getString("customer_code"),
                rs.getString("exception_type"), rs.getString("business_key"), rs.getString("reason"),
                rs.getString("status"), rs.getString("resolution"), rs.getTimestamp("handled_at"),
                rs.getTimestamp("created_at").toLocalDateTime()), args.toArray()));
    }

    private void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("管理员权限 required");
        }
    }

    private RowPreview validateRow(String sheetName, int rowNumber, Row row, Set<String> knownCustomers,
                                   FormulaEvaluator evaluator) {
        String businessKey = firstNonBlank(cell(row, 0, evaluator), cell(row, 1, evaluator));
        String customerCode = customerCode(sheetName, row, evaluator);
        String customerName = "客户基础信息".equals(sheetName) ? cell(row, 1, evaluator) : "";
        if (businessKey.isBlank()) {
            return new RowPreview(sheetName, rowNumber, null, customerCode, customerName,
                    "INVALID", "必填业务标识为空", "REQUIRED_FIELD", rowValues(sheetName, row, evaluator));
        }
        if (Set.of("欠费账单明细", "专线合同台账", "跟进催缴记录").contains(sheetName)
                && !knownCustomers.contains(customerCode)) {
            return new RowPreview(sheetName, rowNumber, businessKey, customerCode, customerName,
                    "EXCLUDED", "客户关联失败，正式客户台账不存在 " + customerCode, "CUSTOMER_NOT_FOUND", rowValues(sheetName, row, evaluator));
        }
        String formatError = validateTypes(sheetName, row, evaluator);
        if (formatError != null) {
            return new RowPreview(sheetName, rowNumber, businessKey, customerCode, customerName,
                    "INVALID", formatError, "INVALID_FORMAT", rowValues(sheetName, row, evaluator));
        }
        return new RowPreview(sheetName, rowNumber, businessKey, customerCode, customerName,
                "VALID", null, null, rowValues(sheetName, row, evaluator));
    }

    private String validateTypes(String sheetName, Row row, FormulaEvaluator evaluator) {
        Set<String> required = REQUIRED_FIELDS.get(sheetName);
        if (required != null) {
            List<String> headers = CONTRACT_HEADERS.get(sheetName);
            for (int index = 0; index < headers.size(); index++) {
                if (required.contains(headers.get(index)) && cell(row, index, evaluator).isBlank()) {
                    return "必填字段为空：" + headers.get(index);
                }
            }
        }
        if ("欠费账单明细".equals(sheetName)) {
            BigDecimal due = decimal(row, 4, evaluator);
            BigDecimal paid = decimal(row, 5, evaluator);
            BigDecimal arrears = decimal(row, 6, evaluator);
            if (due == null || paid == null) return "应缴金额和已缴金额必须为数字";
            try {
                money(cell(row, 4, evaluator)); money(cell(row, 5, evaluator)); money(cell(row, 6, evaluator));
                java.time.YearMonth.parse(cell(row, 2, evaluator));
            } catch (RuntimeException ex) { return "账期或金额格式无效：" + ex.getMessage(); }
            if (due.signum() < 0 || paid.signum() < 0 || paid.compareTo(due) > 0) {
                return "金额必须非负且已缴金额不能大于应缴金额";
            }
            if (arrears != null && arrears.compareTo(due.subtract(paid)) != 0) {
                return "欠费金额必须等于应缴金额减已缴金额";
            }
            if (date(row, 3, evaluator) == null || date(row, 7, evaluator) == null) {
                return "出账日期和缴费截止日格式无效";
            }
        }
        if ("专线合同台账".equals(sheetName)
                && (date(row, 4, evaluator) == null || date(row, 5, evaluator) == null
                || date(row, 6, evaluator) == null)) {
            return "合同签订日、服务开始日和服务到期日格式无效";
        }
        if ("跟进催缴记录".equals(sheetName)
                && date(row, 6, evaluator) == null) {
            return "计划完成日和记录时间格式无效";
        }
        if ("跟进催缴记录".equals(sheetName)) {
            try { parseDateTime(cell(row, 9, evaluator)); } catch (RuntimeException ex) { return "记录时间格式无效"; }
            String status = cell(row, 7, evaluator);
            if (!status.isBlank() && !Set.of("待处理", "待核实", "进行中", "处理中", "已完成", "已关闭").contains(status)) return "任务状态无效";
        }
        if ("专线合同台账".equals(sheetName)) {
            if (date(row, 5, evaluator).isAfter(date(row, 6, evaluator))) return "服务开始日不能晚于到期日";
            if (!cell(row, 9, evaluator).isBlank() && date(row, 9, evaluator) == null) return "最近跟进日格式无效";
        }
        if ("客户基础信息".equals(sheetName) && date(row, 7, evaluator) == null) {
            return "最近更新日期格式无效";
        }
        if ("客户基础信息".equals(sheetName) && !cell(row, 6, evaluator).isBlank()) {
            try { if (Integer.parseInt(cell(row, 6, evaluator)) < 0) return "接入专线总数不能为负"; } catch (NumberFormatException ex) { return "接入专线总数必须为整数"; }
        }
        if ("专线合同台账".equals(sheetName) && !cell(row, 7, evaluator).isBlank()) {
            try { money(cell(row, 7, evaluator)); } catch (IllegalArgumentException ex) { return ex.getMessage(); }
        }
        if ("预警规则配置".equals(sheetName)) {
            if (!Set.of("R001", "R002", "R003", "R004").contains(cell(row, 0, evaluator))) return "未知规则编码";
            try {
                ruleThreshold(cell(row, 0, evaluator), cell(row, 2, evaluator));
            } catch (RuntimeException ex) { return ex.getMessage(); }
        }
        return null;
    }

    private Set<String> knownCustomerCodes() {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT customer_code FROM customer WHERE customer_code IS NOT NULL AND customer_code <> '' AND record_status='ACTIVE'",
                (rs, rowNum) -> rs.getString(1)));
    }

    private static Set<String> customerCodesInWorkbook(Workbook workbook, FormulaEvaluator evaluator) {
        Set<String> customerCodes = new HashSet<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            String contract = resolveContract(sheet.getSheetName(),
                    readCells(sheet.getRow(sheet.getFirstRowNum()), evaluator));
            if (!"客户基础信息".equals(contract)) continue;
            if (!CONTRACT_HEADERS.get(contract).equals(readCells(sheet.getRow(sheet.getFirstRowNum()), evaluator))) continue;
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row != null && !isBlank(row, evaluator) && !cell(row, 0, evaluator).isBlank()) {
                    if (validateCustomerCandidate(row, evaluator)) customerCodes.add(cell(row, 0, evaluator));
                }
            }
        }
        return customerCodes;
    }

    private static boolean validateCustomerCandidate(Row row, FormulaEvaluator evaluator) {
        if (cell(row, 1, evaluator).isBlank() || cell(row, 3, evaluator).isBlank() || date(row, 7, evaluator) == null) return false;
        try { return cell(row, 6, evaluator).isBlank() || Integer.parseInt(cell(row, 6, evaluator)) >= 0; }
        catch (NumberFormatException ex) { return false; }
    }

    private static int countBy(List<RowPreview> rows, String classification) {
        return rows == null ? 0 : (int) rows.stream()
                .filter(row -> classification.equals(row.classification())).count();
    }

    private static List<String> readCells(Row row, FormulaEvaluator evaluator) {
        if (row == null) return List.of();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) values.add(cell(row, i, evaluator));
        return values;
    }

    private static String customerCode(String sheetName, Row row, FormulaEvaluator evaluator) {
        return "客户基础信息".equals(sheetName) ? cell(row, 0, evaluator) : "预警规则配置".equals(sheetName) ? "" : cell(row, 1, evaluator);
    }

    private static Map<String, String> rowValues(String sheetName, Row row, FormulaEvaluator evaluator) {
        List<String> headers = CONTRACT_HEADERS.getOrDefault(sheetName, List.of());
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) result.put(headers.get(i), cell(row, i, evaluator));
        return result;
    }

    private static String resolveContract(String actualSheetName, List<String> headers) {
        if (CONTRACT_HEADERS.containsKey(actualSheetName)) return actualSheetName;
        return CONTRACT_HEADERS.entrySet().stream()
                .filter(entry -> normalizedHeaders(entry.getValue()).equals(headers))
                .map(Map.Entry::getKey).findFirst().orElse(actualSheetName);
    }

    private static List<String> normalizedHeaders(List<String> headers) {
        return headers;
    }

    private static boolean isBlank(Row row, FormulaEvaluator evaluator) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!cell(row, i, evaluator).isBlank()) return false;
        }
        return true;
    }

    private static String cell(Row row, int index, FormulaEvaluator evaluator) {
        if (row == null || row.getCell(index) == null) return "";
        Cell source = row.getCell(index);
        if (source.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(source)) {
            LocalDateTime value = source.getLocalDateTimeCellValue();
            return value.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? value.toLocalDate().toString() : value.toString();
        }
        try { return new DataFormatter(Locale.CHINA).formatCellValue(row.getCell(index), evaluator).trim(); }
        catch (RuntimeException ex) { return "#FORMULA_ERROR"; }
    }

    private static BigDecimal decimal(Row row, int index, FormulaEvaluator evaluator) {
        String value = cell(row, index, evaluator).replace(",", "");
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LocalDate date(Row row, int index, FormulaEvaluator evaluator) {
        Cell source = row.getCell(index);
        if (source == null) return null;
        if (source.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(source)) {
            return source.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        try {
            return LocalDate.parse(cell(row, index, evaluator));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private Long customerId(String code) {
        Long id = findId("SELECT id FROM customer WHERE customer_code=? AND record_status='ACTIVE'", code);
        if (id == null) throw new IllegalArgumentException("客户不存在：" + code);
        return id;
    }

    private Long findId(String sql, Object... args) {
        List<Long> ids = jdbcTemplate.query(sql, (rs, row) -> rs.getLong(1), args);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (blank(value)) throw new IllegalArgumentException("必填字段为空：" + key);
        return value.trim();
    }

    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static String blankOr(String value, String fallback) { return blank(value) ? fallback : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static Integer integer(String value) {
        if (blank(value)) return null;
        try { return Integer.valueOf(value.trim()); } catch (NumberFormatException ex) { throw new IllegalArgumentException("整数格式无效：" + value); }
    }

    private static BigDecimal requiredMoney(String value) {
        if (blank(value)) throw new IllegalArgumentException("金额不能为空");
        return money(value);
    }

    private static BigDecimal ruleThreshold(String code, String expression) {
        if (!Set.of("R001", "R002", "R003", "R004").contains(code)) throw new IllegalArgumentException("未知规则编码");
        String normalized = expression == null ? "" : expression.replace(",", "").replace(" ", "").replace("　", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)(?![\\d.])").matcher(normalized);
        if (!matcher.find()) throw new IllegalArgumentException("阈值表达式中缺少数值");
        BigDecimal threshold = new BigDecimal(matcher.group(1));
        if (threshold.signum() < 0 || threshold.compareTo(new BigDecimal("9999999999999999.99")) > 0 || threshold.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("阈值必须为非负 DECIMAL(18,2) 数值");
        if (Set.of("R003", "R004").contains(code) && threshold.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("天数阈值必须为整数");
        boolean operatorMatches = switch (code) {
            case "R001" -> normalized.contains("<") && !normalized.contains("≤");
            case "R002", "R004" -> normalized.contains(">");
            case "R003" -> normalized.contains("≤") || normalized.contains("<=");
            default -> false;
        };
        if (!operatorMatches) throw new IllegalArgumentException(code + " 比较运算符与已确认规则不一致");
        return threshold;
    }

    private static BigDecimal money(String value) {
        if (blank(value)) return null;
        try {
            BigDecimal result = new BigDecimal(value.replace(",", "").trim());
            if (result.signum() < 0) throw new IllegalArgumentException("金额不能为负数");
            if (result.stripTrailingZeros().scale() > 2 || result.compareTo(new BigDecimal("9999999999999999.99")) > 0) throw new IllegalArgumentException("金额必须在 DECIMAL(18,2) 范围内且最多两位小数");
            return result;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("金额格式无效：" + value); }
    }

    private static LocalDate parseDate(String value) {
        if (blank(value)) return null;
        try { return LocalDate.parse(value.trim()); } catch (RuntimeException ex) { throw new IllegalArgumentException("日期格式无效：" + value); }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (blank(value)) return null;
        try { return LocalDateTime.parse(value.trim().replace(' ', 'T')); } catch (RuntimeException ex) { return parseDate(value).atStartOfDay(); }
    }

    private record WriteResult(String status, int inserted, int updated, int duplicate) {}

    private static String sha256(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record PreviewResponse(Long batchId, String fileName, String fileSha256, long totalRows, long validRows, long excludedRows,
                                  long invalidRows, List<String> workbookErrors, List<RowPreview> rows) {}

    public record ReadOnlyPrecheckResponse(String fileName,String fileSha256,long totalRows,long validRows,long excludedRows,long invalidRows,List<String> workbookErrors,List<RowPreview> rows) {}

    public record RowPreview(String sheetName, int rowNumber, String businessKey, String customerCode,
                             String customerName, String classification, String reason, String errorCode,
                             Map<String, String> values) {}

    public record ConfirmRequest(Long batchId) {}

    public record ImportBatchResponse(Long id, String fileName, String sourceType, String status,
                                      int totalRows, int validRows, int invalidRows, int excludedRows,
                                      int insertedRows, int updatedRows, int duplicateRows, int failedRows,
                                      LocalDateTime createdAt) {}

    public record ImportRowResponse(Long id, Long batchId, String sheetName, int sourceRowNumber, String businessKey,
                                    String customerCode, String status, String category, String errorMessage,
                                    String errorCode, Map<String,String> values, LocalDateTime createdAt) {}

    public record ExceptionResponse(Long id, Long sourceRowId, Long batchId, String sheetName, Integer sourceRowNumber, String customerCode,
                                    String type, String businessKey, String reason,
                                    String status, String resolution, java.sql.Timestamp handledAt,
                                    LocalDateTime createdAt) {}
}
