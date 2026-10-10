package com.mobiletools.arrears.task.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final JdbcTemplate jdbcTemplate;

    public TaskController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<TaskResponse>> list() {
        var tasks = jdbcTemplate.query(
                """
                SELECT id, title, owner_name, deadline, task_status, priority
                FROM task
                ORDER BY deadline, id
                """,
                (rs, rowNum) -> new TaskResponse(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("owner_name"),
                        rs.getObject("deadline", LocalDate.class),
                        rs.getString("task_status"),
                        rs.getString("priority")
                )
        );
        return ApiResponse.success(tasks);
    }

    public record TaskResponse(
            Long id,
            String title,
            String owner,
            LocalDate deadline,
            String status,
            String priority
    ) {
    }
}
