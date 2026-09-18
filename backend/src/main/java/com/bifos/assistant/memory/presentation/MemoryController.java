package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.presentation.MemoryDtos.CreateMemoryRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.memory.presentation.MemoryDtos.UpdateMemoryRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
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
    private final ContextAssembler context;
    private final CurrentUserProvider currentUser;

    /**
     * 볼 수 있는 Memory 를 모두 돌려준다.
     *
     * <p>지금 조립하면 자리가 없어 빠질 항목에 표시를 함께 보낸다. 그 표시가 없으면 본문이 긴 항목
     * 하나가 실리지 않는 것을 쓴 사람이 알 길이 없다. 대화 화면에는 끼우지 않고 이 목록에서만 보인다.
     */
    @GetMapping
    public List<MemoryView> readable() {
        CurrentUser user = currentUser.require();
        Set<Long> omitted = Set.copyOf(context.assemble(user).omittedMemoryIds());
        return memories.readableBy(user).stream()
                .map(memory -> MemoryView.from(memory, omitted.contains(memory.id())))
                .toList();
    }

    @PostMapping
    public MemoryView create(@Valid @RequestBody CreateMemoryRequest request) {
        CurrentUser user = currentUser.require();
        return MemoryView.from(memories.create(user, request.scope(), request.title(), request.content(),
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
