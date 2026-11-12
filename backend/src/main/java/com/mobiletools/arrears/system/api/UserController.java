package com.mobiletools.arrears.system.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final String USER_COLUMNS = "id, username, real_name, role, enabled, created_at, updated_at";
    private static final RowMapper<UserResponse> USER_MAPPER = (rs, row) -> new UserResponse(
            rs.getLong("id"), rs.getString("username"), rs.getString("real_name"), rs.getString("role"),
            rs.getBoolean("enabled"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AdminAccess admin;
    private final PinyinUsernameGenerator usernames;

    public UserController(JdbcTemplate jdbc, PasswordEncoder passwords, AdminAccess admin,
                         PinyinUsernameGenerator usernames) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.admin = admin;
        this.usernames = usernames;
    }

    @GetMapping
    public ApiResponse<List<UserResponse>> list(Authentication authentication) {
        admin.requireAdmin(authentication, "USER_LIST", "USER", null);
        return ApiResponse.success(jdbc.query("SELECT " + USER_COLUMNS + " FROM app_user ORDER BY id", USER_MAPPER));
    }

    @GetMapping("/username-suggestion")
    public ApiResponse<UsernameSuggestion> usernameSuggestion(Authentication authentication,
                                                               @RequestParam String realName) {
        admin.requireAdmin(authentication, "USER_USERNAME_SUGGEST", "USER", null);
        String name = requiredText(realName, "realName", 100);
        String username = usernames.baseUsername(name);
        boolean available = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class,
                username) == 0;
        return ApiResponse.success(new UsernameSuggestion(username, available));
    }

    @PostMapping
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public ApiResponse<UserResponse> create(Authentication authentication, @RequestBody CreateUserRequest request) {
        long actor = admin.requireAdmin(authentication, "USER_CREATE", "USER", null);
        String name = requiredText(request.realName(), "realName", 100);
        String username = request.username() == null
                ? usernames.baseUsername(name)
                : requiredText(request.username(), "username", 100);
        if (!List.of("ADMIN", "CUSTOMER_MANAGER").contains(request.role() == null ? "" : request.role())) {
            throw new IllegalArgumentException("role must be ADMIN or CUSTOMER_MANAGER");
        }
        if (request.password() == null || request.password().isBlank()
                || request.password().length() < 6 || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password must contain at least 6 characters and at most 72 UTF-8 bytes");
        }
        var key = new GeneratedKeyHolder();
        try {
            String hash = passwords.encode(request.password());
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "INSERT INTO app_user(username,real_name,password_hash,role,enabled) VALUES (?,?,?,?,TRUE)",
                        new String[]{"id"});
                statement.setString(1, username);
                statement.setString(2, name);
                statement.setString(3, hash);
                statement.setString(4, request.role());
                return statement;
            }, key);
        } catch (DuplicateKeyException exception) {
            if (request.username() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "姓名拼音登录名已占用，请在登录名中添加唯一后缀");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        }
        long id = key.getKey().longValue();
        admin.audit(actor, "USER_CREATE", "USER", id, "SUCCESS", "Created account with role " + request.role());
        return ApiResponse.success(user(id, false));
    }

    @PatchMapping("/{id}")
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public ApiResponse<UserResponse> enabled(Authentication authentication, @PathVariable long id,
                                             @RequestBody EnabledRequest request) {
        long actor = admin.requireAdmin(authentication, "USER_STATUS", "USER", id);
        if (request.enabled() == null) throw new IllegalArgumentException("enabled is required");
        user(id, true);
        jdbc.update("UPDATE app_user SET enabled=? WHERE id=?", request.enabled(), id);
        admin.audit(actor, request.enabled() ? "USER_ENABLE" : "USER_DISABLE", "USER", id, "SUCCESS",
                "enabled=" + request.enabled());
        return ApiResponse.success(user(id, false));
    }

    @Component
    public static class AdminAccess {
        private final JdbcTemplate jdbc;

        public AdminAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        public long requireAdmin(Authentication authentication, String operation, String target, Long targetId) {
            var actors = authentication == null ? List.<Long>of() : jdbc.queryForList(
                    "SELECT id FROM app_user WHERE username=? AND enabled=TRUE AND role='ADMIN'", Long.class,
                    authentication.getName());
            boolean authority = authentication != null && authentication.getAuthorities().stream()
                    .anyMatch(value -> "ROLE_ADMIN".equals(value.getAuthority()));
            if (!authority || actors.isEmpty()) {
                Long actor = authentication == null ? null : jdbc.queryForList(
                        "SELECT id FROM app_user WHERE username=?", Long.class, authentication.getName()).stream().findFirst().orElse(null);
                audit(actor, operation, target, targetId, "DENIED", "Administrator permission required");
                throw new AccessDeniedException("Administrator permission required");
            }
            return actors.get(0);
        }

        public void audit(Long actor, String operation, String target, Long targetId, String result, String detail) {
            jdbc.update("INSERT INTO operation_audit_log(operator_user_id,operation_type,target_type,target_id,result,detail)"
                    + " VALUES (?,?,?,?,?,?)", actor, operation, target, targetId, result, detail);
        }
    }

    private UserResponse user(long id, boolean lock) {
        var users = jdbc.query("SELECT " + USER_COLUMNS + " FROM app_user WHERE id=?" + (lock ? " FOR UPDATE" : ""), USER_MAPPER, id);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return users.get(0);
    }

    private static String requiredText(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.strip().length() > limit) {
            throw new IllegalArgumentException(field + " is required and must be at most " + limit + " characters");
        }
        return value.strip();
    }

    public record CreateUserRequest(String username, String realName, String role, String password) {
        @Override
        public String toString() { return "CreateUserRequest[credentials=REDACTED]"; }
    }
    public record UsernameSuggestion(String username, boolean available) {}
    public record EnabledRequest(Boolean enabled) {}
    public record UserResponse(long id, String username, String realName, String role, boolean enabled,
                               Timestamp createdAt, Timestamp updatedAt) {}
}
