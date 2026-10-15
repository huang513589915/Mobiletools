package com.mobiletools.arrears.customer.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final JdbcTemplate jdbcTemplate;

    public CustomerController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<CustomerResponse>> list(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        var customers = jdbcTemplate.query(
                """
                SELECT c.id, c.customer_code, c.customer_name, c.manager_name, c.business_type,
                       COALESCE((SELECT SUM(b.arrears_amount) FROM arrears_bill b
                                 WHERE b.customer_id=c.id AND b.record_status='ACTIVE'), 0) AS balance,
                       CASE WHEN COALESCE((SELECT SUM(b.arrears_amount) FROM arrears_bill b
                                           WHERE b.customer_id=c.id AND b.record_status='ACTIVE'), 0) > 1000 THEN '高风险'
                            WHEN c.balance_known = 0 THEN '待核查' ELSE c.risk_status END AS risk_status,
                       COALESCE(DATE_FORMAT((SELECT MIN(e.expiry_date) FROM contract_expiry e
                                              WHERE e.customer_id=c.id), '%Y-%m-%d'), c.due_summary) AS due_summary
                FROM customer c
                LEFT JOIN user_customer_scope scope ON scope.customer_id = c.id
                WHERE c.record_status = 'ACTIVE'
                  AND c.customer_code IS NOT NULL AND c.customer_code <> ''
                  AND (? = TRUE OR scope.user_id = (SELECT id FROM app_user WHERE username = ?))
                GROUP BY c.id, c.customer_code, c.customer_name, c.manager_name, c.business_type,
                         c.balance_known, c.risk_status, c.due_summary
                ORDER BY c.id
                """,
                new Object[]{admin, authentication.getName()},
                (rs, rowNum) -> new CustomerResponse(
                        rs.getLong("id"),
                        rs.getString("customer_code"),
                        rs.getString("customer_name"),
                        rs.getString("manager_name"),
                        rs.getString("business_type"),
                        rs.getBigDecimal("balance"),
                        rs.getString("risk_status"),
                        rs.getString("due_summary")
                )
        );
        return ApiResponse.success(customers);
    }

    @GetMapping("/page")
    public ApiResponse<CustomerPage> page(Authentication authentication,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String manager, @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String riskLevel, @RequestParam(required = false) String expiryFrom,
            @RequestParam(required = false) String expiryTo, @RequestParam(required = false) String exceptionStatus,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "customerCode") String sort, @RequestParam(defaultValue = "asc") String direction) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效");
        String sortColumn = switch (sort) {
            case "customerCode" -> "c.customer_code"; case "name" -> "c.customer_name";
            case "manager" -> "c.manager_name"; case "balance" -> "balance_value";
            case "status" -> "risk_status"; case "due" -> "due_date";
            default -> throw new IllegalArgumentException("不支持的排序字段");
        };
        if (!Set.of("asc", "desc").contains(direction.toLowerCase())) throw new IllegalArgumentException("排序方向无效");
        var args = new ArrayList<Object>();
        boolean admin = isAdmin(authentication);
        StringBuilder where = new StringBuilder(" WHERE c.record_status='ACTIVE' AND c.customer_code IS NOT NULL AND c.customer_code<>'' ");
        if (!admin) { where.append(" AND EXISTS (SELECT 1 FROM user_customer_scope sc WHERE sc.customer_id=c.id AND sc.user_id=(SELECT id FROM app_user WHERE username=?)) "); args.add(authentication.getName()); }
        if (customerId != null) { if(customerId<1) throw new IllegalArgumentException("客户编号无效"); where.append(" AND c.id=? "); args.add(customerId); }
        if (q != null && !q.isBlank()) { where.append(" AND (c.customer_code LIKE ? OR c.customer_name LIKE ? OR EXISTS (SELECT 1 FROM business_service qs WHERE qs.customer_id=c.id AND qs.service_code LIKE ?)) "); String p="%"+q.trim()+"%"; args.add(p);args.add(p);args.add(p); }
        if (manager != null && !manager.isBlank()) { where.append(" AND c.manager_name=? "); args.add(manager); }
        if (businessType != null && !businessType.isBlank()) { where.append(" AND c.business_type=? "); args.add(businessType); }
        if (status != null && !status.isBlank()) { where.append(" AND c.record_status=? "); args.add(status); }
        if (riskLevel != null && !riskLevel.isBlank()) {
            if (!Set.of("HIGH", "MEDIUM", "LOW", "PENDING", "高风险", "中风险", "低风险", "待核查").contains(riskLevel)) throw new IllegalArgumentException("风险级别无效");
            where.append(" AND EXISTS (SELECT 1 FROM risk_result rr WHERE rr.customer_id=c.id AND rr.status='ACTIVE' AND rr.risk_level=?) "); args.add(riskLevel);
        }
        if (expiryFrom != null && !expiryFrom.isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM contract_expiry ex WHERE ex.customer_id=c.id AND ex.expiry_date>=?) "); args.add(parseDate(expiryFrom)); }
        if (expiryTo != null && !expiryTo.isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM contract_expiry ex WHERE ex.customer_id=c.id AND ex.expiry_date<=?) "); args.add(parseDate(expiryTo)); }
        if (exceptionStatus != null && !exceptionStatus.isBlank()) {
            if (!Set.of("OPEN", "RESOLVED", "IGNORED").contains(exceptionStatus)) throw new IllegalArgumentException("异常状态无效");
            where.append(" AND EXISTS (SELECT 1 FROM exception_record er JOIN import_row ir ON ir.id=er.source_row_id WHERE er.status=? AND ir.customer_id=c.customer_code) "); args.add(exceptionStatus);
        }
        String base = " FROM customer c WHERE " + where.substring(" WHERE ".length());
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base, Long.class, args.toArray());
        String select = "SELECT c.id,c.customer_code,c.customer_name,c.manager_name,c.business_type,COALESCE((SELECT SUM(b.arrears_amount) FROM arrears_bill b WHERE b.customer_id=c.id AND b.record_status='ACTIVE'),0) balance_value,CASE WHEN COALESCE((SELECT SUM(b.arrears_amount) FROM arrears_bill b WHERE b.customer_id=c.id AND b.record_status='ACTIVE'),0)>1000 THEN '高风险' WHEN c.balance_known=0 THEN '待核查' ELSE c.risk_status END risk_status,(SELECT MIN(e.expiry_date) FROM contract_expiry e WHERE e.customer_id=c.id) due_date" + base + " ORDER BY " + sortColumn + " " + direction.toUpperCase() + ",c.id LIMIT ? OFFSET ?";
        var pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((long)(page-1)*pageSize);
        List<CustomerResponse> items = jdbcTemplate.query(select, pageArgs.toArray(), (rs,n) -> new CustomerResponse(rs.getLong("id"),rs.getString("customer_code"),rs.getString("customer_name"),rs.getString("manager_name"),rs.getString("business_type"),rs.getBigDecimal("balance_value"),rs.getString("risk_status"),rs.getString("due_date") == null ? "暂无到期信息" : rs.getString("due_date")));
        return ApiResponse.success(new CustomerPage(items,total == null ? 0 : total,page,pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerDetail> detail(@PathVariable long id, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        String scope = admin ? "" : " AND EXISTS (SELECT 1 FROM user_customer_scope sc WHERE sc.customer_id=c.id AND sc.user_id=(SELECT id FROM app_user WHERE username=?))";
        Object[] args = admin ? new Object[]{id} : new Object[]{id,authentication.getName()};
        List<Map<String,Object>> customer = jdbcTemplate.queryForList("SELECT c.* FROM customer c WHERE c.id=? AND c.record_status='ACTIVE' AND c.customer_code IS NOT NULL"+scope,args);
        if (customer.isEmpty()) throw new org.springframework.security.access.AccessDeniedException("客户不存在或无权查看");
        return ApiResponse.success(new CustomerDetail(customer.get(0),
                jdbcTemplate.queryForList("SELECT * FROM business_service WHERE customer_id=? ORDER BY id",id),
                jdbcTemplate.queryForList("SELECT * FROM arrears_bill WHERE customer_id=? AND record_status='ACTIVE' ORDER BY due_date DESC,id DESC",id),
                jdbcTemplate.queryForList("SELECT * FROM contract_expiry WHERE customer_id=? ORDER BY expiry_date,id",id),
                jdbcTemplate.queryForList("SELECT * FROM risk_result WHERE customer_id=? ORDER BY calculated_at DESC,id DESC",id),
                jdbcTemplate.queryForList("SELECT t.* FROM task t WHERE t.customer_id=? ORDER BY t.created_at DESC,t.id DESC",id),
                jdbcTemplate.queryForList("SELECT f.* FROM follow_up f JOIN task t ON t.id=f.task_id WHERE t.customer_id=? ORDER BY f.created_at DESC,f.id DESC",id),
                jdbcTemplate.queryForList("SELECT id,file_name,source_type,import_status,created_at FROM import_batch WHERE id IN (SELECT source_batch_id FROM customer WHERE id=? UNION SELECT source_batch_id FROM business_service WHERE customer_id=? UNION SELECT source_batch_id FROM arrears_bill WHERE customer_id=? UNION SELECT source_batch_id FROM contract_expiry WHERE customer_id=?) ORDER BY created_at DESC",id,id,id,id)));
    }

    private boolean isAdmin(Authentication authentication) { return authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())); }
    private java.time.LocalDate parseDate(String value) { try { return java.time.LocalDate.parse(value); } catch (RuntimeException ex) { throw new IllegalArgumentException("日期必须使用 YYYY-MM-DD"); } }
    public record CustomerPage(List<CustomerResponse> items,long total,int page,int pageSize) {}
    public record CustomerDetail(Map<String,Object> customer,List<Map<String,Object>> services,List<Map<String,Object>> bills,List<Map<String,Object>> expiries,List<Map<String,Object>> risks,List<Map<String,Object>> tasks,List<Map<String,Object>> followUps,List<Map<String,Object>> batches) {}

    public record CustomerResponse(
            Long id,
            String customerCode,
            String name,
            String manager,
            String type,
            BigDecimal balance,
            String status,
            String due
    ) {
    }
}
