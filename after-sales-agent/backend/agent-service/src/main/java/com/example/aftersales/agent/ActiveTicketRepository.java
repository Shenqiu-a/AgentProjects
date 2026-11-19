package com.example.aftersales.agent;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Map;
import java.util.UUID;

/**
 * 功能：
 * 作者：yml
 * 日期：2026/11/1811:45
 */

@Repository
public class ActiveTicketRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ActiveTicketRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean exists(UUID userId, UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM ticket
                    WHERE user_id = :userId AND order_id = :orderId
                      AND status IN ('OPEN', 'PROCESSING')
                )
                """, Map.of("userId", userId, "orderId", orderId), Boolean.class));
    }
}
