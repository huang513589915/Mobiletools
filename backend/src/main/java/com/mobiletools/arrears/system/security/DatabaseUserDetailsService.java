package com.mobiletools.arrears.system.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return jdbcTemplate.query(
                "SELECT username, password_hash, enabled FROM app_user WHERE username = ?",
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new UsernameNotFoundException("Invalid username or password");
                    }
                    return User.withUsername(resultSet.getString("username"))
                            .password(resultSet.getString("password_hash"))
                            .disabled(!resultSet.getBoolean("enabled"))
                            .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                            .build();
                },
                username
        );
    }
}
