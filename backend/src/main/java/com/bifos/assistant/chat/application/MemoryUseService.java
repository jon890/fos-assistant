package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.memory.application.AcceptedMemoryLookup;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionMemoryRef;
import com.bifos.assistant.usage.application.ExecutionMemoryRefs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화의 답마다 그 실행이 본문을 받은 기억을 지금 볼 수 있는 것만 제목과 함께 낸다(ADR-20261008 / memory-facts).
 *
 * <p>실행 당시의 권한이 아니라 지금 권한으로 거른다. 지운 항목, 남의 항목, 되돌려 제안으로 돌아간 항목은 빠진다. 본문은 싣지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
public class MemoryUseService {

    private static final String READ = "READ";

    private final ChatMessageRepository messages;
    private final ExecutionMemoryRefs refs;
    private final AcceptedMemoryLookup accepted;
    private final MemoryService memoryService;

    /** 답 실행 번호 오름차순으로, 한 실행 안에서는 실은 순서 뒤에 읽은 순서로 낸다. */
    @Transactional(readOnly = true)
    public List<MemoryUse> usesOf(CurrentUser user, Long conversationId) {
        List<Long> executionIds = messages.findAssistantExecutionIds(conversationId);
        List<ExecutionMemoryRef> found = refs.of(executionIds);
        if (found.isEmpty()) {
            return List.of();
        }
        Set<Long> memoryIds = found.stream().map(ExecutionMemoryRef::memoryId).collect(Collectors.toSet());
        Map<Long, Memory> visible = accepted.acceptedReadableAmong(user, memoryIds);
        Map<Long, MemoryAccess> accesses = new HashMap<>();
        List<MemoryUse> uses = new ArrayList<>();
        for (ExecutionMemoryRef ref : found) {
            Memory memory = visible.get(ref.memoryId());
            if (memory != null && (!READ.equals(ref.via()) || readable(accesses, ref, memory))) {
                uses.add(new MemoryUse(ref.executionId(), memory.id(), memory.title(), memory.scope(), ref.via()));
            }
        }
        return uses;
    }

    /** {@code memory_read} 로 읽은 항목은 그 실행의 에이전트가 지금 읽을 수 있을 때만 낸다. 에이전트마다 접근 범위를 한 번만 읽는다. */
    private boolean readable(Map<Long, MemoryAccess> accesses, ExecutionMemoryRef ref, Memory memory) {
        if (ref.agentId() == null) {
            return false;
        }
        MemoryAccess access = accesses.computeIfAbsent(ref.agentId(), memoryService::accessOf);
        return memoryService.readableByTool(memory, access);
    }
}
