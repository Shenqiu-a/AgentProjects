package com.example.aftersales.security;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
/**
 * 功能：PostgreSQL 查询与参数绑定
 * 作者：yml
 * 日期：2026/10/1415:41
 */

@Repository
public class AppUserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AppUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByUserName(String username) {
        return jdbc.query("""
                SELECT id, username, password_hash, role, enabled
                FROM app_user
                WHERE username = :username
                """, Map.of("username", username), (rs, row) -> new Account(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("role"),
                        rs.getBoolean("enabled")))
                .stream().findFirst();
    }

    public record Account(UUID id, String username, String passwordHash, String role, boolean enabled) {
        @Override
        public String toString() {
            return "Account[id=" + id + ", username=" + username + ", role=" + role + " ";
        }
    }

}
