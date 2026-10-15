package com.mobiletools.arrears.system.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JdbcTemplate jdbcTemplate;

    public AuthController(AuthenticationManager authenticationManager, JdbcTemplate jdbcTemplate) {
        this.authenticationManager = authenticationManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken csrfToken) {
        return ApiResponse.success(Map.of("token", csrfToken.getToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            HttpSession session = servletRequest.getSession(true);
            servletRequest.changeSessionId();
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    context
            );
            audit(authentication.getName(), "LOGIN", "SUCCESS");
            return ResponseEntity.ok(ApiResponse.success(userResponse(authentication)));
        } catch (AuthenticationException exception) {
            audit(request.username(), "LOGIN", "FAILED");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponse<>("AUTH_FAILED", "账号或密码错误", null));
        }
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> currentUser(Authentication authentication) {
        return ApiResponse.success(userResponse(authentication));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) audit(authentication.getName(), "LOGOUT", "SUCCESS");
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.success(Map.of("loggedOut", true));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
        @Override
        public String toString() { return "LoginRequest[credentials=REDACTED]"; }
    }

    private void audit(String username, String operation, String result) {
        jdbcTemplate.update("INSERT INTO operation_audit_log(operator_user_id,operation_type,target_type,result,detail) VALUES ((SELECT id FROM app_user WHERE username=?),?,'SESSION',?,?)",
                username, operation, result, "认证操作，不记录密码或会话令牌");
    }

    private UserResponse userResponse(Authentication authentication) {
        return jdbcTemplate.queryForObject("SELECT username, real_name, role FROM app_user WHERE username=?",
                (rs, row) -> new UserResponse(rs.getString("username"), rs.getString("real_name"), rs.getString("role")),
                authentication.getName());
    }

    public record UserResponse(String username, String realName, String role) {
    }
}
