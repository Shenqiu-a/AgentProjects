package com.example.aftersales.agent;

import com.example.aftersales.common.ApiResponses;
import com.example.aftersales.platform.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/** 工单和确认单留在同一个数据库，后续确认写入仍可使用单库事务。 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final NamedParameterJdbcTemplate jdbc;
    public TicketController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/{ticketId}")
    public Map<String, Object> detail(@PathVariable UUID ticketId, @AuthenticationPrincipal Caller caller,
                                      HttpServletRequest request) {
        var rows = jdbc.queryForList("""
                SELECT id, ticket_no AS "ticketNo", order_id AS "orderId", type,
                       reason_code AS "reasonCode", description, status, version,
                       created_at AS "createdAt", updated_at AS "updatedAt"
                FROM ticket WHERE id = :id AND user_id = :userId
                """, Map.of("id", ticketId, "userId", caller.id()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ApiResponses.ok(rows.getFirst(), request);
    }
}
