package com.mobiletools.arrears.risk.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/risk-rules")
public class RiskRuleController {
    private final JdbcTemplate jdbcTemplate;
    private final AdminAccess users;
    public RiskRuleController(JdbcTemplate jdbcTemplate, AdminAccess users) {
        this.jdbcTemplate = jdbcTemplate;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<List<RuleResponse>> list(Authentication authentication) {
        users.requireAdmin(authentication, "RULE_LIST", "RISK_RULE", null);
        return ApiResponse.success(jdbcTemplate.query("""
                SELECT id, rule_code, rule_name, rule_type, threshold_value, operator, version,
                       is_enabled, effective_from, effective_to
                FROM risk_rule ORDER BY rule_code, version DESC
                """, (rs, n) -> new RuleResponse(rs.getLong("id"), rs.getString("rule_code"),
                rs.getString("rule_name"), rs.getString("rule_type"), rs.getBigDecimal("threshold_value"),
                rs.getString("operator"), rs.getInt("version"), rs.getBoolean("is_enabled"),
                rs.getTimestamp("effective_from"), rs.getTimestamp("effective_to"))));
    }

    @PostMapping
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public ApiResponse<RuleResponse> update(Authentication authentication, @RequestBody RuleUpdate request) {
        long actor = users.requireAdmin(authentication, "RULE_UPDATE", "RISK_RULE", null);
        String operator = switch (request.code() == null ? "" : request.code()) {
            case "R001" -> "LT";
            case "R002", "R004" -> "GT";
            case "R003" -> "LTE";
            default -> throw new IllegalArgumentException("code must be R001, R002, R003 or R004");
        };
        if (!operator.equals(request.operator())) throw new IllegalArgumentException("operator for " + request.code() + " must be " + operator);
        BigDecimal threshold = request.threshold();
        if (threshold == null || threshold.signum() < 0 || threshold.compareTo(new BigDecimal("9999999999999999.99")) > 0
                || threshold.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("threshold must be nonnegative and fit DECIMAL(18,2)");
        }
        if (("R003".equals(request.code()) || "R004".equals(request.code()))
                && (threshold.stripTrailingZeros().scale() > 0 || threshold.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)) {
            throw new IllegalArgumentException("threshold must be a nonnegative integer number of days within integer range");
        }
        if (request.enabled() == null) throw new IllegalArgumentException("enabled is required");
        // Lock the full code history so concurrent updates cannot allocate the same next version.
        var history = jdbcTemplate.query("SELECT id,rule_name,rule_type,version FROM risk_rule WHERE rule_code=? ORDER BY version DESC FOR UPDATE",
                (rs, row) -> new PreviousRule(rs.getLong("id"), rs.getString("rule_name"), rs.getString("rule_type"), rs.getInt("version")), request.code());
        if (history.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        PreviousRule previous = history.get(0);
        java.sql.Timestamp now = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class);
        jdbcTemplate.update("UPDATE risk_rule SET is_enabled=FALSE,effective_to=? WHERE rule_code=? AND effective_to IS NULL", now, request.code());
        int version = Math.addExact(previous.version(), 1);
        jdbcTemplate.update("INSERT INTO risk_rule(rule_code,rule_name,rule_type,threshold_value,operator,version,is_enabled,effective_from,created_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?)", request.code(), previous.name(), previous.type(), threshold, operator, version, request.enabled(), now, actor);
        long id = jdbcTemplate.queryForObject("SELECT id FROM risk_rule WHERE rule_code=? AND version=?", Long.class, request.code(), version);
        users.audit(actor, "RULE_UPDATE", "RISK_RULE", id, "SUCCESS", "code=" + request.code() + ", previousVersion="
                + previous.version() + ", version=" + version + ", operator=" + operator + ", threshold=" + threshold + ", enabled=" + request.enabled());
        return ApiResponse.success(new RuleResponse(id, request.code(), previous.name(), previous.type(), threshold, operator,
                version, request.enabled(), now, null));
    }

    public record RuleUpdate(String code, BigDecimal threshold, String operator, Boolean enabled) {}
    private record PreviousRule(long id, String name, String type, int version) {}

    public record RuleResponse(Long id, String code, String name, String type, BigDecimal threshold,
                               String operator, int version, boolean enabled,
                               java.sql.Timestamp effectiveFrom, java.sql.Timestamp effectiveTo) {}
}
