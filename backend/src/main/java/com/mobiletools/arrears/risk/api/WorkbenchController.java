package com.mobiletools.arrears.risk.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/workbench")
public class WorkbenchController {
    private final JdbcTemplate jdbc;
    public WorkbenchController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/items")
    public ApiResponse<List<Map<String, Object>>> items(@RequestParam(required=false) String riskLevel, @RequestParam(required=false) String owner,
            @RequestParam(required=false) String businessType, @RequestParam(required=false) String from,
            @RequestParam(required=false) String to, @RequestParam(required=false) String exceptionStatus, Authentication auth) {
        return ApiResponse.success(query(riskLevel, owner, businessType, from, to, exceptionStatus, auth));
    }
    @GetMapping("/summary")
    public ApiResponse<Summary> summary(@RequestParam(required=false) String riskLevel, @RequestParam(required=false) String owner,
            @RequestParam(required=false) String businessType, @RequestParam(required=false) String from,
            @RequestParam(required=false) String to, @RequestParam(required=false) String exceptionStatus, Authentication auth) {
        var rows = query(riskLevel, owner, businessType, from, to, exceptionStatus, auth);
        long high = rows.stream().filter(r -> "RISK".equals(r.get("kind")) && "HIGH".equals(r.get("riskLevel"))).map(r -> r.get("customerId")).distinct().count();
        long pending = rows.stream().filter(r -> "TASK".equals(r.get("kind"))).count();
        long overdue = rows.stream().filter(r -> "TASK".equals(r.get("kind")) && ((Number) r.get("overdueDays")).longValue() > 0).count();
        long expiry = rows.stream().filter(r -> "EXPIRY".equals(r.get("kind"))).count();
        long exceptions = rows.stream().filter(r -> "EXCEPTION".equals(r.get("kind")) && "OPEN".equals(r.get("status"))).count();
        return ApiResponse.success(new Summary(high,pending,expiry,exceptions,overdue,LocalDateTime.now()));
    }
    private List<Map<String,Object>> query(String risk, String owner, String business, String from, String to, String exception, Authentication auth) {
        if (risk != null && !List.of("HIGH","MEDIUM").contains(risk)) throw new IllegalArgumentException("风险级别无效");
        if (exception != null && !List.of("OPEN","RESOLVED","IGNORED").contains(exception)) throw new IllegalArgumentException("异常状态无效");
        LocalDate start = date(from), end = date(to);
        if (start != null && end != null && start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        String sql = """
                SELECT i.*,c.customer_code AS customerCode,c.customer_name AS customerName,
                       c.manager_name AS owner,c.business_type AS businessType
                FROM (
                  SELECT 'RISK' kind,r.id,r.customer_id AS customerId,rr.rule_name AS title,
                         r.risk_level AS riskLevel,r.status,DATE(r.calculated_at) AS eventDate,
                         r.source_batch_id AS batchId,NULL AS sourceRowNumber,0 AS overdueDays,NULL AS sheetName,NULL AS sourceCustomerCode,NULL AS sourceRowId
                  FROM risk_result r JOIN risk_rule rr ON rr.id=r.rule_id WHERE r.status='ACTIVE'
                  UNION ALL
                  SELECT 'TASK',t.id,t.customer_id,t.title,NULL,t.task_status,t.deadline,t.source_batch_id,NULL,
                         GREATEST(DATEDIFF(CURDATE(),t.deadline),0),NULL,NULL,NULL
                  FROM task t WHERE t.task_status NOT IN ('已完成','已关闭')
                  UNION ALL
                  SELECT 'EXPIRY',e.id,e.customer_id,CONCAT('业务到期 ',COALESCE(s.service_code,'')),NULL,e.renewal_status,e.expiry_date,e.source_batch_id,NULL,0,NULL,NULL,NULL
                  FROM contract_expiry e LEFT JOIN business_service s ON s.id=e.service_id
                  WHERE e.expiry_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(),INTERVAL 30 DAY)
                    AND (e.service_id IS NULL OR (s.customer_id=e.customer_id AND s.status='ACTIVE'))
                  UNION ALL
                  SELECT 'EXCEPTION',e.id,COALESCE(c2.id,CASE WHEN e.exception_type LIKE 'RISK_%' AND e.business_key LIKE '%:CUSTOMER:%' THEN CAST(SUBSTRING_INDEX(e.business_key,':CUSTOMER:',-1) AS UNSIGNED) END),e.reason,NULL,e.status,DATE(e.created_at),e.batch_id,ir.source_row_number,0,
                         ir.sheet_name,ir.customer_id,e.source_row_id
                  FROM exception_record e LEFT JOIN import_row ir ON ir.id=e.source_row_id
                  LEFT JOIN customer c2 ON c2.customer_code=ir.customer_id AND c2.record_status='ACTIVE'
                ) i LEFT JOIN customer c ON c.id=i.customerId
                WHERE (i.kind='EXCEPTION' AND i.customerId IS NULL OR
                       c.record_status='ACTIVE' AND c.customer_code IS NOT NULL AND c.customer_code<>'')
                """;
        var args = new ArrayList<Object>();
        if (!admin) {
            sql += " AND i.customerId IS NOT NULL AND EXISTS (SELECT 1 FROM user_customer_scope sc JOIN app_user u ON u.id=sc.user_id WHERE sc.customer_id=c.id AND u.username=?)";
            args.add(auth.getName());
        }
        if (risk != null) { sql += " AND i.riskLevel=?"; args.add(risk); }
        if (owner != null && !owner.isBlank()) { sql += " AND c.manager_name=?"; args.add(owner.trim()); }
        if (business != null && !business.isBlank()) { sql += " AND c.business_type=?"; args.add(business.trim()); }
        if (start != null) { sql += " AND i.eventDate>=?"; args.add(start); }
        if (end != null) { sql += " AND i.eventDate<=?"; args.add(end); }
        if (exception != null) { sql += " AND i.kind='EXCEPTION' AND i.status=?"; args.add(exception); }
        else { sql += " AND (i.kind<>'EXCEPTION' OR i.status='OPEN')"; }
        return jdbc.queryForList(sql + " ORDER BY i.eventDate,i.kind,i.id",args.toArray());
    }
    private LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("日期须为YYYY-MM-DD"); }
    }
    public record Summary(long highRiskCustomers,long pendingTasks,long expiringServices,long openExceptions,long overdueTasks,LocalDateTime calculatedAt) {}
}
