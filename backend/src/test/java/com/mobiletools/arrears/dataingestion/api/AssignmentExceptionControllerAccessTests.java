package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.dataingestion.ledger.AssignmentExceptionQueryService;
import com.mobiletools.arrears.dataingestion.ledger.AssignmentManagementService;
import com.mobiletools.arrears.system.api.UserController;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentExceptionControllerAccessTests {

    private JdbcTemplate jdbc;
    private AssignmentExceptionQueryService queries;
    private AssignmentExceptionController controller;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:assignment-exception-access;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100), role VARCHAR(30), enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE operation_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT, operation_type VARCHAR(60), target_type VARCHAR(60), target_id BIGINT, result VARCHAR(30), detail VARCHAR(2000))");
        jdbc.update("INSERT INTO app_user VALUES (1,'admin','管理员','ADMIN',TRUE),(2,'manager','客户经理','CUSTOMER_MANAGER',TRUE)");
        queries = mock(AssignmentExceptionQueryService.class);
        controller = new AssignmentExceptionController(queries, mock(AssignmentManagementService.class),
                new UserController.AdminAccess(jdbc));
        when(queries.list(null, null, null, null, null, null, null, 1, 20))
                .thenReturn(new AssignmentExceptionQueryService.Page(List.of(), 0, 1, 20));
    }

    @Test
    void onlyAdministratorCanListOrReadUnassignedExceptionRows() {
        var manager = authentication("manager", "ROLE_CUSTOMER_MANAGER");
        assertThrows(AccessDeniedException.class,
                () -> controller.list(null, null, null, null, null, null, null, 1, 20, manager));
        assertThrows(AccessDeniedException.class, () -> controller.detail(88, manager));
        verifyNoInteractions(queries);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE result='DENIED'", Integer.class));

        var page = controller.list(null, null, null, null, null, null, null, 1, 20,
                authentication("admin", "ROLE_ADMIN"));
        assertEquals(0, page.data().total());
        verify(queries).list(null, null, null, null, null, null, null, 1, 20);
    }

    private static UsernamePasswordAuthenticationToken authentication(String username, String authority) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
