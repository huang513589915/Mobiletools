package com.mobiletools.arrears.risk.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/risk-recalculation")
public class RiskRecalculationController {
    private final JdbcTemplate jdbc;
    private final AdminAccess adminAccess;

    public RiskRecalculationController(JdbcTemplate jdbc, AdminAccess adminAccess) {
        this.jdbc = jdbc;
        this.adminAccess = adminAccess;
    }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String, Object>> recalculate(
            @RequestBody(required = false) RecalculationRequest request, Authentication authentication) {
        requireAdmin(authentication);
        List<Long> supplied = request == null || request.customerIds() == null ? List.of() : request.customerIds();
        if (supplied.size() > 20 || supplied.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("请选择不超过20个有效客户");
        }
        List<Long> ids = supplied.stream().distinct().sorted().collect(Collectors.toList());
        String scope = ids.isEmpty() ? "" : " AND c.id IN (" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
        String formal = "c.record_status='ACTIVE' AND c.customer_code IS NOT NULL AND c.customer_code<>''" + scope;
        if (!ids.isEmpty() && jdbc.queryForObject("SELECT COUNT(*) FROM customer c WHERE " + formal, Integer.class) != ids.size()) {
            throw new IllegalArgumentException("重算范围包含不存在或非正式客户");
        }
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, authentication.getName());
        // Stable rule locks serialize snapshot publication with other runs and rule updates.
        List<Rule> rules = jdbc.query("SELECT id,rule_code,threshold_value,operator,version,is_enabled FROM risk_rule ORDER BY rule_code,version FOR UPDATE",
                (rs, n) -> new Rule(rs.getLong("id"), rs.getString("rule_code"), rs.getBigDecimal("threshold_value"),
                        rs.getString("operator"), rs.getInt("version"), rs.getBoolean("is_enabled")));
        String versions = rules.stream().filter(Rule::enabled).map(r -> r.code() + ":" + r.version()).collect(Collectors.joining(","));
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO risk_recalculation_run(rule_version_from,rule_version_to,scope_type,scope_value,status,started_by) VALUES (?,?,?,?,'RUNNING',?)", new String[]{"id"});
            statement.setString(1, versions);
            statement.setString(2, versions);
            statement.setString(3, ids.isEmpty() ? "ALL" : "CUSTOMERS");
            statement.setString(4, ids.isEmpty() ? null : ids.toString());
            statement.setLong(5, userId);
            return statement;
        }, keys);
        long runId = keys.getKey().longValue();
        var transaction = TransactionAspectSupport.currentTransactionStatus();
        Object checkpoint = transaction.createSavepoint();
        try {
            int count = calculate(rules, formal, runId);
            generateTasks(runId, userId);
            jdbc.update("UPDATE risk_result r JOIN customer c ON c.id=r.customer_id SET r.status='CLEARED' WHERE r.status='ACTIVE' AND r.run_id<>? AND " + formal, runId);
            jdbc.update("UPDATE risk_recalculation_run SET status='COMPLETED',success_count=?,total_count=?,finished_at=NOW() WHERE id=?", count, count, runId);
            audit(userId, runId, "SUCCESS");
            transaction.releaseSavepoint(checkpoint);
            return ApiResponse.success(Map.of("runId", runId, "status", "COMPLETED", "resultCount", count));
        } catch (RuntimeException exception) {
            // Restore inserts and retirement together; retain the failed run outside the savepoint.
            transaction.rollbackToSavepoint(checkpoint);
            transaction.releaseSavepoint(checkpoint);
            jdbc.update("UPDATE risk_recalculation_run SET status='FAILED',failure_count=1,error_message=?,finished_at=NOW() WHERE id=?",
                    "风险计算失败：" + exception.getClass().getSimpleName(), runId);
            jdbc.update("INSERT INTO exception_record(exception_type,business_key,reason) VALUES ('RISK_CALCULATION_FAILED',?,?)",
                    "RISK_RUN:" + runId, "重算失败，旧风险快照已保留，可重试本次范围");
            audit(userId, runId, "FAILED");
            return ApiResponse.success(Map.of("runId", runId, "status", "FAILED", "resultCount", 0, "message", "风险重算失败，旧结果已保留，请查看运行记录后重试"));
        }
    }

    private int calculate(List<Rule> rules, String formal, long runId) {
        int count = 0;
        for (Rule rule : rules) {
            if (!rule.enabled()) continue;
            String expected = switch (rule.code()) { case "R001" -> "LT"; case "R002", "R004" -> "GT"; case "R003" -> "LTE"; default -> null; };
            if (expected == null || !expected.equals(rule.operator()) || rule.threshold() == null || rule.threshold().signum() < 0) {
                throw new IllegalArgumentException("规则配置无效");
            }
            String query = switch (rule.code()) {
                case "R001" -> "SELECT c.id,NULL,'CUSTOMER',c.id,c.balance,c.source_batch_id FROM customer c WHERE " + formal + " AND c.balance_known=1 AND c.balance>=0 AND c.balance<?";
                case "R002" -> "SELECT c.id,NULL,'CUSTOMER',c.id,SUM(b.arrears_amount),MAX(b.source_batch_id) FROM customer c JOIN arrears_bill b ON b.customer_id=c.id AND b.record_status='ACTIVE' WHERE " + formal + " GROUP BY c.id HAVING SUM(b.arrears_amount)>? AND SUM(b.due_amount<0 OR b.paid_amount<0 OR b.paid_amount>b.due_amount OR b.arrears_amount<>b.due_amount-b.paid_amount)=0";
                case "R003" -> "SELECT c.id,e.service_id,'CONTRACT_EXPIRY',e.id,DATEDIFF(e.expiry_date,CURDATE()),e.source_batch_id FROM contract_expiry e JOIN customer c ON c.id=e.customer_id LEFT JOIN business_service s ON s.id=e.service_id WHERE " + formal + " AND (e.service_id IS NULL OR (s.customer_id=c.id AND s.status='ACTIVE')) AND DATEDIFF(e.expiry_date,CURDATE())<=?";
                case "R004" -> "SELECT c.id,t.service_id,'TASK',t.id,DATEDIFF(CURDATE(),t.deadline),t.source_batch_id FROM task t JOIN customer c ON c.id=t.customer_id WHERE " + formal + " AND t.task_status NOT IN ('已完成','已关闭') AND DATEDIFF(CURDATE(),t.deadline)>?";
                default -> throw new IllegalArgumentException("未知规则");
            };
            var inputs = jdbc.query(query, (rs, n) -> new Input(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getLong(4), rs.getBigDecimal(5), (Long) rs.getObject(6)), rule.threshold());
            for (Input input : inputs) {
                count += jdbc.update("INSERT INTO risk_result(customer_id,service_id,rule_id,rule_version,source_record_type,source_record_id,observed_value,threshold_value,risk_level,status,calculated_at,run_id,source_batch_id) VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',NOW(),?,?)",
                        input.customerId(), input.serviceId(), rule.id(), rule.version(), input.type(), input.sourceId(), input.value(), rule.threshold(),
                        List.of("R002", "R004").contains(rule.code()) ? "HIGH" : "MEDIUM", runId, input.batchId());
            }
        }
        jdbc.update("INSERT INTO exception_record(exception_type,business_key,reason) SELECT 'RISK_INPUT_MISSING',CONCAT('RISK_RUN:',?,':CUSTOMER:',c.id),'客户余额缺失或无效，未生成余额风险结论' FROM customer c WHERE " + formal + " AND (c.balance_known=0 OR c.balance<0)", runId);
        jdbc.update("INSERT INTO exception_record(exception_type,business_key,reason) SELECT 'RISK_INPUT_INVALID',CONCAT('RISK_RUN:',?,':BILL:',b.id),'账单金额无效，未生成该客户欠费风险结论' FROM arrears_bill b JOIN customer c ON c.id=b.customer_id WHERE " + formal + " AND b.record_status='ACTIVE' AND (b.due_amount<0 OR b.paid_amount<0 OR b.paid_amount>b.due_amount OR b.arrears_amount<>b.due_amount-b.paid_amount)", runId);
        return count;
    }

    @GetMapping("/{id}/task")
    public ApiResponse<Map<String, Object>> linkedTask(@org.springframework.web.bind.annotation.PathVariable long id, Authentication auth) {
        String scope = isAdmin(auth) ? "" : " AND EXISTS (SELECT 1 FROM user_customer_scope s JOIN app_user u ON u.id=s.user_id WHERE s.customer_id=r.customer_id AND u.username=?)";
        var rows = jdbc.queryForList("SELECT r.customer_id,r.source_record_type,r.source_record_id,rr.rule_code FROM risk_result r JOIN risk_rule rr ON rr.id=r.rule_id WHERE r.id=?" + scope,
                isAdmin(auth) ? new Object[]{id} : new Object[]{id, auth.getName()});
        if (rows.isEmpty()) throw new AccessDeniedException("风险不存在或不在当前客户范围内");
        var risk = rows.get(0);
        if ("R004".equals(risk.get("rule_code"))) return ApiResponse.success(Map.of("taskId", risk.get("source_record_id")));
        String prefix = "RISK:" + risk.get("customer_id") + ":" + risk.get("source_record_type") + ":" + risk.get("source_record_id") + ":" + risk.get("rule_code") + ":";
        var tasks = jdbc.queryForList("SELECT id FROM task WHERE customer_id=? AND idempotency_key LIKE ? ORDER BY (task_status NOT IN ('已完成','已关闭')) DESC,id DESC LIMIT 1", risk.get("customer_id"), prefix + "%");
        return ApiResponse.success(tasks.isEmpty() ? Map.of("message", "暂无关联任务，请核查责任人") : Map.of("taskId", tasks.get(0).get("id")));
    }

    @GetMapping("/results")
    public ApiResponse<List<Map<String, Object>>> results(@RequestParam(defaultValue = "ACTIVE") String status, Authentication auth) {
        if (!List.of("ACTIVE", "CLEARED").contains(status)) throw new IllegalArgumentException("风险状态无效");
        boolean admin = isAdmin(auth);
        String scope = admin ? "" : " AND EXISTS (SELECT 1 FROM user_customer_scope s WHERE s.customer_id=r.customer_id AND s.user_id=(SELECT id FROM app_user WHERE username=?))";
        return ApiResponse.success(jdbc.queryForList("SELECT r.id,r.customer_id AS customerId,c.customer_code AS customerCode,c.customer_name AS customerName,rr.rule_code AS ruleCode,rr.rule_name AS ruleName,r.rule_version AS ruleVersion,r.source_record_type AS sourceType,r.source_record_id AS sourceId,r.observed_value AS observedValue,r.threshold_value AS threshold,r.risk_level AS riskLevel,r.status,r.calculated_at AS calculatedAt,r.run_id AS runId,r.source_batch_id AS sourceBatchId FROM risk_result r JOIN customer c ON c.id=r.customer_id JOIN risk_rule rr ON rr.id=r.rule_id WHERE r.status=?" + scope + " ORDER BY r.calculated_at DESC,r.id DESC LIMIT 500", admin ? new Object[]{status} : new Object[]{status, auth.getName()}));
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(Authentication auth) {
        requireAdmin(auth);
        return ApiResponse.success(jdbc.queryForList("SELECT id,status,scope_type AS scopeType,scope_value AS scopeValue,rule_version_to AS ruleVersions,total_count AS totalCount,success_count AS successCount,failure_count AS failureCount,error_message AS errorMessage,started_at AS startedAt,finished_at AS finishedAt FROM risk_recalculation_run ORDER BY id DESC LIMIT 100"));
    }

    private void audit(long userId, long runId, String result) {
        adminAccess.audit(userId, "RISK_RECALCULATION", "RISK_RUN", runId, result, "重算派生风险并复用或创建来源任务，保留人工状态与跟进历史");
    }

    private void generateTasks(long runId, long userId) {
        var risks = jdbc.queryForList("SELECT r.*,rr.rule_code,rr.rule_name,c.manager_user_id,u.real_name,u.enabled FROM risk_result r JOIN risk_rule rr ON rr.id=r.rule_id JOIN customer c ON c.id=r.customer_id LEFT JOIN app_user u ON u.id=c.manager_user_id WHERE r.run_id=? ORDER BY r.id", runId);
        for (var risk : risks) {
            String code = (String) risk.get("rule_code");
            if ("R004".equals(code)) continue;
            long customer = ((Number) risk.get("customer_id")).longValue();
            long source = ((Number) risk.get("source_record_id")).longValue();
            String type = (String) risk.get("source_record_type");
            // Serialize the idempotency check and insert for this customer across concurrent runs.
            jdbc.queryForObject("SELECT id FROM customer WHERE id=? FOR UPDATE", Long.class, customer);
            String prefix = "RISK:" + customer + ":" + type + ":" + source + ":" + code + ":";
            int open = jdbc.queryForList("SELECT id FROM task WHERE customer_id=? AND idempotency_key LIKE ? AND task_status NOT IN ('已完成','已关闭') FOR UPDATE", Long.class, customer, prefix + "%").size();
            int continuous = jdbc.queryForList("SELECT r.id FROM risk_result r JOIN risk_rule rr ON rr.id=r.rule_id WHERE r.customer_id=? AND r.source_record_type=? AND r.source_record_id=? AND rr.rule_code=? AND r.status='ACTIVE' AND r.run_id<>? FOR UPDATE", Long.class, customer, type, source, code, runId).size();
            int previousTask = jdbc.queryForList("SELECT id FROM task WHERE customer_id=? AND idempotency_key LIKE ? FOR UPDATE", Long.class, customer, prefix + "%").size();
            if (open > 0 || (continuous > 0 && previousTask > 0)) continue;
            Object enabled = risk.get("enabled");
            boolean active = Boolean.TRUE.equals(enabled) || (enabled instanceof Number n && n.intValue() == 1);
            if (!active || risk.get("real_name") == null) {
                jdbc.update("INSERT INTO exception_record(exception_type,business_key,reason) VALUES ('TASK_OWNER_MISSING',?,'客户责任人缺失或停用，未自动创建任务')", "RISK_RUN:" + runId + ":CUSTOMER:" + customer);
                continue;
            }
            jdbc.update("INSERT INTO task(customer_id,service_id,source_type,source_record_id,task_type,rule_id,idempotency_key,created_by,title,owner_name,deadline,task_status,priority,source_batch_id) VALUES (?,?,?,?,?,?,?,?,?,?,DATE_ADD(CURDATE(),INTERVAL 3 DAY),'待处理',?,?)",
                    customer, risk.get("service_id"), type, source, code, risk.get("rule_id"), prefix + risk.get("id"), userId,
                    risk.get("rule_name") + "跟进", risk.get("real_name"), "R002".equals(code) ? "高" : "中", risk.get("source_batch_id"));
        }
    }

    private boolean isAdmin(Authentication auth) { return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())); }
    private void requireAdmin(Authentication auth) { adminAccess.requireAdmin(auth, "RISK_RECALCULATION", "RISK_RUN", null); }
    public record RecalculationRequest(List<Long> customerIds) {}
    private record Rule(long id, String code, BigDecimal threshold, String operator, int version, boolean enabled) {}
    private record Input(long customerId, Long serviceId, String type, long sourceId, BigDecimal value, Long batchId) {}
}
