package com.mobiletools.arrears.customer.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    private final JdbcTemplate jdbcTemplate;

    public LedgerController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/services")
    public ApiResponse<List<ServiceResponse>> services(Authentication authentication) {
        var scope = scope(authentication, "c.id");
        return ApiResponse.success(jdbcTemplate.query("""
                SELECT s.id, s.service_code, c.customer_code, c.customer_name, s.service_type,
                       s.bandwidth, e.expiry_date, e.renewal_status, s.updated_at
                FROM business_service s JOIN customer c ON c.id=s.customer_id
                LEFT JOIN contract_expiry e ON e.service_id=s.id
                WHERE """ + scope.sql() + " ORDER BY e.expiry_date, s.id", scope.args(),
                (rs, n) -> new ServiceResponse(rs.getLong("id"), rs.getString("service_code"),
                        rs.getString("customer_code"), rs.getString("customer_name"), rs.getString("service_type"),
                        rs.getString("bandwidth"), rs.getObject("expiry_date", LocalDate.class),
                        rs.getString("renewal_status"), rs.getTimestamp("updated_at").toLocalDateTime())));
    }

    @GetMapping("/bills")
    public ApiResponse<List<BillResponse>> bills(Authentication authentication) {
        var scope = scope(authentication, "c.id");
        return ApiResponse.success(jdbcTemplate.query("""
                SELECT b.id, b.bill_code, c.customer_code, c.customer_name, b.billing_period,
                       b.due_amount, b.paid_amount, b.arrears_amount, b.bill_status, b.due_date
                FROM arrears_bill b JOIN customer c ON c.id=b.customer_id
                WHERE """ + scope.sql() + " ORDER BY b.due_date, b.id", scope.args(),
                (rs, n) -> new BillResponse(rs.getLong("id"), rs.getString("bill_code"),
                        rs.getString("customer_code"), rs.getString("customer_name"), rs.getString("billing_period"),
                        rs.getBigDecimal("due_amount"), rs.getBigDecimal("paid_amount"),
                        rs.getBigDecimal("arrears_amount"), rs.getString("bill_status"),
                        rs.getObject("due_date", LocalDate.class))));
    }

    @GetMapping("/expiries")
    public ApiResponse<List<ExpiryResponse>> expiries(Authentication authentication) {
        var scope = scope(authentication, "c.id");
        return ApiResponse.success(jdbcTemplate.query("""
                SELECT e.id, s.service_code, c.customer_code, c.customer_name, e.expiry_date,
                       e.renewal_status, e.updated_at
                FROM contract_expiry e JOIN customer c ON c.id=e.customer_id
                LEFT JOIN business_service s ON s.id=e.service_id
                WHERE """ + scope.sql() + " ORDER BY e.expiry_date, e.id", scope.args(),
                (rs, n) -> new ExpiryResponse(rs.getLong("id"), rs.getString("service_code"),
                        rs.getString("customer_code"), rs.getString("customer_name"),
                        rs.getObject("expiry_date", LocalDate.class), rs.getString("renewal_status"),
                        rs.getTimestamp("updated_at").toLocalDateTime())));
    }

    @GetMapping("/expiries/page")
    public ApiResponse<ExpiryPage> expiryPage(Authentication authentication,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String from,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String to,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="10") int pageSize) {
        if(page<1||pageSize<1||pageSize>100) throw new IllegalArgumentException("分页参数无效");
        LocalDate start=parseOptionalDate(from),end=parseOptionalDate(to);
        if(start!=null&&end!=null&&start.isAfter(end)) throw new IllegalArgumentException("到期开始日期不能晚于结束日期");
        var access=scope(authentication,"c.id");
        StringBuilder where=new StringBuilder(" WHERE "+access.sql());
        var args=new java.util.ArrayList<Object>(java.util.Arrays.asList(access.args()));
        if(start!=null){where.append(" AND e.expiry_date>=?");args.add(start);}
        if(end!=null){where.append(" AND e.expiry_date<=?");args.add(end);}
        if(q!=null&&!q.isBlank()){where.append(" AND (c.customer_code LIKE ? OR c.customer_name LIKE ? OR s.service_code LIKE ?)");String p="%"+q.trim()+"%";args.add(p);args.add(p);args.add(p);}
        String fromSql=" FROM contract_expiry e JOIN customer c ON c.id=e.customer_id LEFT JOIN business_service s ON s.id=e.service_id"+where;
        Long total=jdbcTemplate.queryForObject("SELECT COUNT(*)"+fromSql,Long.class,args.toArray());
        var pageArgs=new java.util.ArrayList<>(args);pageArgs.add(pageSize);pageArgs.add((long)(page-1)*pageSize);
        List<ExpiryResponse> items=jdbcTemplate.query("SELECT e.id,s.service_code,c.customer_code,c.customer_name,e.expiry_date,e.renewal_status,e.updated_at"+fromSql+" ORDER BY e.expiry_date,e.id LIMIT ? OFFSET ?",pageArgs.toArray(),(rs,n)->new ExpiryResponse(rs.getLong("id"),rs.getString("service_code"),rs.getString("customer_code"),rs.getString("customer_name"),rs.getObject("expiry_date",LocalDate.class),rs.getString("renewal_status"),rs.getTimestamp("updated_at").toLocalDateTime()));
        return ApiResponse.success(new ExpiryPage(items,total==null?0:total,page,pageSize));
    }

    @GetMapping("/services/{id}")
    public ApiResponse<ServiceDetail> serviceDetail(@PathVariable long id, Authentication authentication) {
        var access=scope(authentication,"c.id");
        var service=jdbcTemplate.queryForList("SELECT s.*,c.customer_code,c.customer_name FROM business_service s JOIN customer c ON c.id=s.customer_id WHERE s.id=? AND "+access.sql(),append(new Object[]{id},access.args()));
        if(service.isEmpty()) throw new org.springframework.security.access.AccessDeniedException("业务不存在或无权查看");
        return ApiResponse.success(new ServiceDetail(service.get(0),
                jdbcTemplate.queryForList("SELECT * FROM arrears_bill WHERE service_id=? AND record_status='ACTIVE' ORDER BY due_date DESC,id DESC",id),
                jdbcTemplate.queryForList("SELECT * FROM contract_expiry WHERE service_id=? ORDER BY expiry_date,id",id),
                jdbcTemplate.queryForList("SELECT * FROM risk_result WHERE service_id=? ORDER BY calculated_at DESC,id DESC",id),
                jdbcTemplate.queryForList("SELECT * FROM task WHERE service_id=? ORDER BY created_at DESC,id DESC",id)));
    }

    @GetMapping("/services/page")
    public ApiResponse<ServicePage> servicePage(Authentication authentication,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String from,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String to,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="10") int pageSize) {
        if(page<1||pageSize<1||pageSize>100) throw new IllegalArgumentException("分页参数无效");
        LocalDate start=parseOptionalDate(from),end=parseOptionalDate(to);
        if(start!=null&&end!=null&&start.isAfter(end)) throw new IllegalArgumentException("到期开始日期不能晚于结束日期");
        var access=scope(authentication,"c.id");
        StringBuilder where=new StringBuilder(" WHERE "+access.sql()); var args=new java.util.ArrayList<Object>(java.util.Arrays.asList(access.args()));
        if(start!=null){where.append(" AND e.expiry_date>=?");args.add(start);}
        if(end!=null){where.append(" AND e.expiry_date<=?");args.add(end);}
        if(q!=null&&!q.isBlank()){where.append(" AND (c.customer_code LIKE ? OR c.customer_name LIKE ? OR s.service_code LIKE ?)");String p="%"+q.trim()+"%";args.add(p);args.add(p);args.add(p);}
        String fromSql=" FROM business_service s JOIN customer c ON c.id=s.customer_id LEFT JOIN contract_expiry e ON e.service_id=s.id"+where;
        Long total=jdbcTemplate.queryForObject("SELECT COUNT(*)"+fromSql,Long.class,args.toArray());
        var pageArgs=new java.util.ArrayList<>(args);pageArgs.add(pageSize);pageArgs.add((long)(page-1)*pageSize);
        List<ServiceResponse> items=jdbcTemplate.query("SELECT s.id,s.service_code,c.customer_code,c.customer_name,s.service_type,s.bandwidth,e.expiry_date,e.renewal_status,s.updated_at"+fromSql+" ORDER BY e.expiry_date,s.id LIMIT ? OFFSET ?",pageArgs.toArray(),(rs,n)->new ServiceResponse(rs.getLong("id"),rs.getString("service_code"),rs.getString("customer_code"),rs.getString("customer_name"),rs.getString("service_type"),rs.getString("bandwidth"),rs.getObject("expiry_date",LocalDate.class),rs.getString("renewal_status"),rs.getTimestamp("updated_at").toLocalDateTime()));
        return ApiResponse.success(new ServicePage(items,total==null?0:total,page,pageSize));
    }

    private Scope scope(Authentication authentication, String customerColumn) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return admin ? new Scope(" TRUE", new Object[]{})
                : new Scope(" EXISTS (SELECT 1 FROM user_customer_scope sc WHERE sc.customer_id=" + customerColumn +
                " AND sc.user_id=(SELECT id FROM app_user WHERE username=?))", new Object[]{authentication.getName()});
    }

    private record Scope(String sql, Object[] args) {}
    private LocalDate parseOptionalDate(String value){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(RuntimeException ex){throw new IllegalArgumentException("日期必须使用 YYYY-MM-DD");}}
    private Object[] append(Object[] first,Object[] second) { Object[] result=java.util.Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,result,first.length,second.length);return result; }
    public record ServiceDetail(java.util.Map<String,Object> service,List<java.util.Map<String,Object>> bills,List<java.util.Map<String,Object>> expiries,List<java.util.Map<String,Object>> risks,List<java.util.Map<String,Object>> tasks) {}
    public record ExpiryPage(List<ExpiryResponse> items,long total,int page,int pageSize) {}
    public record ServicePage(List<ServiceResponse> items,long total,int page,int pageSize) {}
    public record ServiceResponse(Long id, String serviceCode, String customerCode, String customerName,
                                  String serviceType, String bandwidth, LocalDate expiryDate, String renewalStatus,
                                  java.time.LocalDateTime updatedAt) {}
    public record BillResponse(Long id, String billCode, String customerCode, String customerName,
                               String billingPeriod, BigDecimal dueAmount, BigDecimal paidAmount,
                               BigDecimal arrearsAmount, String status, LocalDate dueDate) {}
    public record ExpiryResponse(Long id, String serviceCode, String customerCode, String customerName,
                                 LocalDate expiryDate, String renewalStatus, java.time.LocalDateTime updatedAt) {}
}
