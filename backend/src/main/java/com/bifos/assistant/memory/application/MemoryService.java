package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.domain.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryService {

    private final MemoryRepository memories;

    /** 이 사용자가 볼 수 있는 항목만 낸다. 상태로 거르지 않는다. 화면이 제안도 봐야 한다. */
    public List<Memory> readableBy(CurrentUser user) {
        return memories.findAll().stream().filter(memory -> readable(memory, user)).toList();
    }

    /** 본문까지 항상 싣는 항목. ACCEPTED 이고 alwaysInject 인 것만이다. */
    public List<Memory> alwaysInjectedFor(CurrentUser user) {
        return injectableFor(user, true);
    }

    /** 제목만 싣는 항목. ACCEPTED 이고 alwaysInject 가 아닌 것이다. */
    public List<Memory> indexedFor(CurrentUser user) {
        return injectableFor(user, false);
    }

    /** 제목만 실렸던 ACCEPTED 항목의 본문을 낸다. 볼 수 없거나 승인 전이면 MEMORY_NOT_FOUND 다. */
    public Memory bodyFor(CurrentUser user, Long id) {
        Memory memory = requireReadable(user, id);
        if (memory.status() != MemoryStatus.ACCEPTED || memory.alwaysInject()) {
            throw notFound();
        }
        return memory;
    }

    @Transactional
    public Memory create(CurrentUser user, MemoryScope scope, String title, String content,
            boolean alwaysInject) {
        if (scope == null) {
            throw new ApiException(ErrorCode.MEMORY_SCOPE_REQUIRED, "memory scope is required");
        }
        if (scope == MemoryScope.FAMILY) {
            requireAdmin(user);
            return memories.save(Memory.accepted(scope, null, user.familyId(), title, content,
                    alwaysInject, user.id()));
        }
        return memories.save(Memory.accepted(scope, user.id(), null, title, content,
                alwaysInject, user.id()));
    }

    /** 에이전트가 제안한 개인 항목을 만든다. FAMILY 제안은 만들지 않는다. */
    @Transactional
    public Memory proposeUser(CurrentUser user, String title, String content, Long proposedByExecutionId) {
        String dedupKey = proposalDedupKey(user.id(), title, content);
        return memories.findByProposalDedupKey(dedupKey)
                .orElseGet(() -> memories.save(Memory.proposedUser(
                        user.id(), title, content, proposedByExecutionId, dedupKey)));
    }

    @Transactional
    public Memory accept(CurrentUser user, Long id) {
        Memory memory = requireReadable(user, id);
        requireWritable(user, memory);
        memory.accept(user.id(), Instant.now());
        return memories.save(memory);
    }

    @Transactional
    public Memory reject(CurrentUser user, Long id) {
        Memory memory = requireReadable(user, id);
        requireWritable(user, memory);
        memory.reject();
        return memories.save(memory);
    }

    @Transactional
    public Memory update(CurrentUser user, Long id, String content, boolean alwaysInject) {
        Memory memory = requireReadable(user, id);
        requireWritable(user, memory);
        String dedupKey = memory.proposalDedupKey() == null
                ? null
                : proposalDedupKey(memory.ownerUserId(), memory.title(), content);
        memory.updateContentAndInjection(content, alwaysInject, dedupKey);
        return memories.save(memory);
    }

    @Transactional
    public void delete(CurrentUser user, Long id) {
        Memory memory = requireReadable(user, id);
        requireWritable(user, memory);
        memories.delete(memory);
    }

    private List<Memory> injectableFor(CurrentUser user, boolean alwaysInject) {
        return memories.findAll().stream()
                .filter(memory -> readable(memory, user))
                .filter(memory -> memory.status() == MemoryStatus.ACCEPTED)
                .filter(memory -> memory.alwaysInject() == alwaysInject)
                .toList();
    }

    private Memory requireReadable(CurrentUser user, Long id) {
        Memory memory = memories.findById(id).orElseThrow(MemoryService::notFound);
        if (!readable(memory, user)) {
            throw notFound();
        }
        return memory;
    }

    private boolean readable(Memory memory, CurrentUser user) {
        return memory.isReadableBy(user.id(), user.familyId());
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the family admin");
        }
    }

    private static void requireWritable(CurrentUser user, Memory memory) {
        if (memory.scope() == MemoryScope.FAMILY && !user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the family admin");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory");
    }

    private static String proposalDedupKey(Long ownerUserId, String title, String content) {
        try {
            String value = ownerUserId + "\u0000" + title + "\u0000" + content;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
