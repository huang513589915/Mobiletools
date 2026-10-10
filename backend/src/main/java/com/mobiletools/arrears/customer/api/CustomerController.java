package com.mobiletools.arrears.customer.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final JdbcTemplate jdbcTemplate;

    public CustomerController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<CustomerResponse>> list() {
        var customers = jdbcTemplate.query(
                """
                SELECT id, customer_name, manager_name, business_type, balance, risk_status, due_summary
                FROM customer
                ORDER BY id
                """,
                (rs, rowNum) -> new CustomerResponse(
                        rs.getLong("id"),
                        rs.getString("customer_name"),
                        rs.getString("manager_name"),
                        rs.getString("business_type"),
                        rs.getBigDecimal("balance"),
                        rs.getString("risk_status"),
                        rs.getString("due_summary")
                )
        );
        return ApiResponse.success(customers);
    }

    public record CustomerResponse(
            Long id,
            String name,
            String manager,
            String type,
            BigDecimal balance,
            String status,
            String due
    ) {
    }
}
