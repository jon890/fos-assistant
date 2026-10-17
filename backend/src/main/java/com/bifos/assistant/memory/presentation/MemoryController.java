package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.MemorySources;
import com.bifos.assistant.memory.application.OmittedMemories;
import com.bifos.assistant.memory.application.model.MemorySource;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.presentation.MemoryDtos.CreateMemoryRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.memory.presentation.MemoryDtos.SupersededByView;
import com.bifos.assistant.memory.presentation.MemoryDtos.UpdateMemoryRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/memories")
@RequiredArgsConstructor
public class MemoryController {
    private final MemoryService memories;
    private final OmittedMemories omittedMemories;
    private final MemorySources sources;
    private final MemoryLinkService links;
    private final CurrentUserProvider currentUser;


    /**
     * 볼 수 있는 Memory 를 모두 돌려준다.
     *
     * <p>지금 조립하면 자리가 없어 빠질 항목에 표시를 함께 보낸다. 그 표시가 없으면 본문이 긴 항목
     * 하나가 실리지 않는 것을 쓴 사람이 알 길이 없다. 대화 화면에는 끼우지 않고 이 목록에서만 보인다.
     *
     * <p>에이전트가 남긴 항목에는 남긴 에이전트를 함께 보낸다. 사용자가 무엇을 에이전트가 기억했는지 검토하는 데 쓴다.
     *
     * <p>대체된 항목에는 대신한 항목의 제목을 함께 보낸다. 대신한 항목을 요청자가 볼 수 없으면 제목 없이 연결 번호만 보낸다.
     */
    @GetMapping
    public List<MemoryView> readable() {
        CurrentUser user = currentUser.require();
        Set<Long> omitted = omittedMemories.omittedFor(user);
        List<Memory> readable = memories.readableBy(user);
        Map<Long, MemorySource> bySource = sources.of(user, readable);
        Map<Long, Memory> byId = readable.stream().collect(Collectors.toMap(Memory::id, Function.identity()));
        Map<Long, MemoryLink> superseded = links.supersededBy(byId.keySet());
        return readable.stream()
            .map(memory -> MemoryView.from(
                memory,
                omitted.contains(memory.id()),
                bySource.get(memory.id()),
                supersededBy(superseded.get(memory.id()), byId)))
            .toList();
    }


    private static SupersededByView supersededBy(MemoryLink link, Map<Long, Memory> byId) {
        if (link == null) {
            return null;
        }
        Memory newer = byId.get(link.fromMemoryId());
        return new SupersededByView(link.id(), newer == null ? null : newer.id(), newer == null ? null : newer.title());
    }


    @PostMapping
    public MemoryView create(@Valid @RequestBody CreateMemoryRequest request) {
        CurrentUser user = currentUser.require();
        return MemoryView.from(memories.create(
            user,
            request.scope(),
            request.title(),
            request.content(),
            Boolean.TRUE.equals(request.alwaysInject())));
    }


    @PatchMapping("/{id}")
    public MemoryView update(@PathVariable Long id, @Valid @RequestBody UpdateMemoryRequest request) {
        return MemoryView.from(memories.update(currentUser.require(), id, request.content(), request.alwaysInject()));
    }


    @PostMapping("/{id}/accept")
    public MemoryView accept(@PathVariable Long id) {
        return MemoryView.from(memories.accept(currentUser.require(), id));
    }


    @PostMapping("/{id}/reject")
    public MemoryView reject(@PathVariable Long id) {
        return MemoryView.from(memories.reject(currentUser.require(), id));
    }


    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        memories.delete(currentUser.require(), id);
    }
}
