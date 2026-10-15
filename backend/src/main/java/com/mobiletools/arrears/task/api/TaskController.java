package com.mobiletools.arrears.task.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final JdbcTemplate jdbcTemplate;

    public TaskController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @GetMapping
    public ApiResponse<List<TaskResponse>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String owner,
                                                @RequestParam(required = false) String priority,
                                                @RequestParam(required = false) Boolean overdue,
                                                Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        var tasks = jdbcTemplate.query("""
                SELECT t.id, t.title, t.owner_name, t.deadline, t.task_status, t.priority,
                       c.customer_code, c.customer_name
                FROM task t JOIN customer c ON c.id = t.customer_id
                WHERE c.customer_code IS NOT NULL AND c.customer_code <> ''
                  AND c.record_status = 'ACTIVE'
                  AND (? = TRUE OR EXISTS (
                      SELECT 1 FROM user_customer_scope scope
                      WHERE scope.customer_id = t.customer_id
                        AND scope.user_id = (SELECT id FROM app_user WHERE username = ?)
                  ))
                  AND (? IS NULL OR t.task_status = ? OR (? = 'UNFINISHED' AND t.task_status NOT IN ('已完成','已关闭')))
                  AND (? IS NULL OR t.owner_name = ?)
                  AND (? IS NULL OR t.priority = ?)
                  AND (? IS NULL OR (? = TRUE AND t.deadline < CURDATE()
                                      AND t.task_status NOT IN ('已完成', '已关闭'))
                                  OR (? = FALSE AND (t.deadline >= CURDATE()
                                      OR t.task_status IN ('已完成', '已关闭'))))
                ORDER BY t.deadline, t.id
                """,
                new Object[]{admin, authentication.getName(), status, status, status, owner, owner,
                        priority, priority, overdue, overdue, overdue},
                (rs, row) -> {
                    LocalDate deadline = rs.getObject("deadline", LocalDate.class);
                    String taskStatus = rs.getString("task_status");
                    long days = deadline.isBefore(LocalDate.now()) && !"已完成".equals(taskStatus)
                            && !"已关闭".equals(taskStatus)
                            ? ChronoUnit.DAYS.between(deadline, LocalDate.now()) : 0;
                    return new TaskResponse(rs.getLong("id"), rs.getString("title"), rs.getString("owner_name"),
                            deadline, taskStatus, rs.getString("priority"), rs.getString("customer_code"),
                            rs.getString("customer_name"), days > 0, days);
                });
        return ApiResponse.success(tasks);
    }

    public record TaskResponse(Long id, String title, String owner, LocalDate deadline,
                               String status, String priority, String customerCode,
                               String customerName, boolean overdue, long overdueDays) {}
}
