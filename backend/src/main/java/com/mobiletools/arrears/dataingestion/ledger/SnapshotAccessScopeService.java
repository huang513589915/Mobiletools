package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/** Resolves the current account role and supplies the mandatory snapshot-row visibility predicate. */
@Service
public class SnapshotAccessScopeService {

    private static final Pattern SQL_ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private final JdbcTemplate jdbc;

    public SnapshotAccessScopeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Scope resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException("Authenticated account required");
        }
        List<Account> accounts = jdbc.query("""
                SELECT id, role, enabled FROM app_user WHERE username=?
                """, (rs, row) -> new Account(rs.getLong("id"), rs.getString("role"),
                rs.getBoolean("enabled")), authentication.getName());
        if (accounts.isEmpty() || !accounts.get(0).enabled()) {
            throw new AccessDeniedException("Enabled account required");
        }

        Account account = accounts.get(0);
        String expectedAuthority = switch (account.role()) {
            case "ADMIN" -> "ROLE_ADMIN";
            case "CUSTOMER_MANAGER" -> "ROLE_CUSTOMER_MANAGER";
            default -> null;
        };
        if (expectedAuthority == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> expectedAuthority.equals(authority.getAuthority()))) {
            throw new AccessDeniedException("Account role does not permit ledger access");
        }
        return new Scope(account.role().equals("ADMIN"), account.id());
    }

    private record Account(long id, String role, boolean enabled) {}

    public record Scope(boolean administrator, long userId) {
        /** Adds a row-level predicate to a query that joins excel_snapshot_record with this alias. */
        public void appendPredicate(String snapshotAlias, StringBuilder where, List<Object> parameters) {
            if (snapshotAlias == null || !SQL_ALIAS.matcher(snapshotAlias).matches()) {
                throw new IllegalArgumentException("Invalid snapshot SQL alias");
            }
            if (!administrator) {
                where.append(" AND ").append(snapshotAlias).append(".effective_manager_user_id=?");
                parameters.add(userId);
            }
        }
    }
}
