package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 번호로 모은 Memory 가운데 요청자가 지금 읽을 수 있는 승인된 항목을 고른다(ADR-20261008 / memory-facts).
 *
 * <p>답마다 참고한 기억처럼 실행 기록이 가리키는 번호를 지금 권한으로 다시 거를 때 쓴다.
 */
@Service
@RequiredArgsConstructor
public class AcceptedMemoryLookup {

    private final MemoryRepository memories;

    /** 번호들 가운데 요청자가 읽을 수 있는 ACCEPTED 항목을 번호로 묶어 낸다. 번호가 비면 읽지 않는다. */
    @Transactional(readOnly = true)
    public Map<Long, Memory> acceptedReadableAmong(CurrentUser user, Collection<Long> ids) {
        List<Memory> found = ids.isEmpty() ? List.of() : memories.findAllById(ids);
        return found.stream()
                .filter(memory ->
                        memory.status() == MemoryStatus.ACCEPTED && memory.isReadableBy(user.id(), user.groupId()))
                .collect(Collectors.toMap(Memory::id, Function.identity()));
    }
}
