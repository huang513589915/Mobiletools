package com.mobiletools.arrears.system.api;

import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import com.mobiletools.arrears.system.security.DatabaseUserDetailsService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ManagerAccountManagementTests {

    private JdbcTemplate jdbc;
    private PasswordEncoder passwords;
    private UserController users;
    private UsernamePasswordAuthenticationToken admin;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:manager-accounts-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) NOT NULL UNIQUE,"
                + " real_name VARCHAR(100) NOT NULL, password_hash VARCHAR(100) NOT NULL, role VARCHAR(30) NOT NULL,"
                + " enabled BOOLEAN NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + " updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE operation_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT,"
                + " operation_type VARCHAR(60) NOT NULL, target_type VARCHAR(60), target_id BIGINT, result VARCHAR(30) NOT NULL,"
                + " detail VARCHAR(2000), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        passwords = new BCryptPasswordEncoder();
        jdbc.update("INSERT INTO app_user(username,real_name,password_hash,role,enabled) VALUES (?,?,?,?,TRUE)",
                "admin", "测试管理员", passwords.encode("admin-password"), "ADMIN");
        users = new UserController(jdbc, passwords, new AdminAccess(jdbc), new PinyinUsernameGenerator());
        admin = new UsernamePasswordAuthenticationToken("admin", "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void suggestsPinyinAndFlagsOccupiedBaseWithoutChangingRealName() {
        var created = users.create(admin, new UserController.CreateUserRequest(
                null, "黄铃", "CUSTOMER_MANAGER", "manager-password"));
        assertEquals("huangling", created.data().username());
        assertEquals("黄铃", created.data().realName());

        var suggestion = users.usernameSuggestion(admin, "黄玲").data();
        assertEquals("huangling", suggestion.username());
        assertFalse(suggestion.available());

        ResponseStatusException collision = assertThrows(ResponseStatusException.class, () ->
                users.create(admin, new UserController.CreateUserRequest(
                        null, "黄玲", "CUSTOMER_MANAGER", "manager-password")));
        assertEquals(HttpStatus.CONFLICT, collision.getStatus());

        var suffixed = users.create(admin, new UserController.CreateUserRequest(
                "huangling-2", "黄玲", "CUSTOMER_MANAGER", "manager-password"));
        assertEquals("huangling-2", suffixed.data().username());
        assertEquals("黄玲", suffixed.data().realName());
    }

    @Test
    void duplicateExplicitLoginNameIsRejectedAndDisabledManagerCannotAuthenticate() {
        var created = users.create(admin, new UserController.CreateUserRequest(
                "manager-one", "张伟", "CUSTOMER_MANAGER", "manager-password"));
        assertThrows(ResponseStatusException.class, () -> users.create(admin,
                new UserController.CreateUserRequest("manager-one", "李伟", "CUSTOMER_MANAGER", "manager-password")));

        users.enabled(admin, created.data().id(), new UserController.EnabledRequest(false));
        DaoAuthenticationProvider authentication = new DaoAuthenticationProvider();
        authentication.setUserDetailsService(new DatabaseUserDetailsService(jdbc));
        authentication.setPasswordEncoder(passwords);
        assertThrows(DisabledException.class, () -> authentication.authenticate(
                new UsernamePasswordAuthenticationToken("manager-one", "manager-password")));
    }
}
