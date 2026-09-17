package com.bifos.assistant.mcp.application;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class McpToolService {
    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    private final MemoryService memories;
    public List<Map<String, Object>> tools() { return List.of(Map.of("name", "memory_read", "description", "지금 묻는 사람의 Memory 항목 본문을 번호로 읽는다. 번호는 지시문의 색인에 있다.", "inputSchema", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")), "required", List.of("id")))); }
    public Map<String, Object> call(CurrentUser user, String name, Long id) {
        if (!"memory_read".equals(name)) throw new UnknownToolException();
        try { Memory memory = memories.bodyFor(user, id); log.info("memory read userId={} memoryId={}", user.id(), id); return result(memory.content(), false); }
        catch (ApiException ex) { return result("Memory 항목을 읽을 수 없습니다.", true); }
    }
    private static Map<String, Object> result(String text, boolean error) { Map<String, Object> result = new LinkedHashMap<>(); result.put("content", List.of(Map.of("type", "text", "text", text))); result.put("isError", error); return result; }
    public static class UnknownToolException extends RuntimeException {}
}
