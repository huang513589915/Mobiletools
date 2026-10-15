package com.mobiletools.arrears.system.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public BootstrapAdminInitializer(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap-admin.username:admin}") String username,
            @Value("${app.bootstrap-admin.password:}") String password
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "No initial account exists. Set APP_ADMIN_PASSWORD before starting the application."
            );
        }

        ensureUser(username, "系统管理员", "ADMIN");
        ensureUser("huangling", "黄铃", "CUSTOMER_MANAGER");
        ensureUser("zhoumin", "周敏", "CUSTOMER_MANAGER");
        ensureUser("chenchen", "陈晨", "CUSTOMER_MANAGER");
    }

    private void ensureUser(String account, String realName, String role) {
        Integer existingUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?",
                Integer.class,
                account
        );
        if (existingUsers != null && existingUsers > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO app_user (username, real_name, password_hash, role, enabled) VALUES (?, ?, ?, ?, TRUE)",
                account, realName, passwordEncoder.encode(password), role
        );
        log.info("Created local application account '{}' with role '{}'.", account, role);
    }
}
