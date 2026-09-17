package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.presentation.MemoryDtos.CreateMemoryRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.memory.presentation.MemoryDtos.UpdateMemoryRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
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
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<MemoryView> readable() {
        return memories.readableBy(currentUser.require()).stream().map(MemoryView::from).toList();
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
