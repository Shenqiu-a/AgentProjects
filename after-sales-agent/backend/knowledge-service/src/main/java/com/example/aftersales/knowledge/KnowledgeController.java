package com.example.aftersales.knowledge;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 本次提供管理员文档目录；解析、上传与向量检索仍按原需求继续实现。 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeController {
    private final NamedParameterJdbcTemplate jdbc;
    public KnowledgeController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        var rows = jdbc.queryForList("""
                SELECT id, title, kind, status, active_generation AS "activeGeneration",
                       created_at AS "createdAt"
                FROM knowledge_document WHERE status <> 'DELETED'
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """, Map.of("limit", limit, "offset", offset));
        return ApiResponses.ok(Map.of("items", rows, "offset", offset, "limit", limit), request);
    }
}
