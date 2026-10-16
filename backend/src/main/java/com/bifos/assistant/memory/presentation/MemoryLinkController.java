package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryLinkView;
import com.bifos.assistant.memory.presentation.MemoryDtos.SupersedeProposalView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기억 사이의 연결을 사람이 정하는 API 다(ADR-20261015 / memory-link). 계약은 {@code docs/features/memory.md} 의 「기억 사이의 연결」 이
 * 갖는다.
 *
 * <p>없는 줄과 남의 줄은 같은 404 이고, 그룹 항목의 대체를 {@code MEMBER} 가 정하면 403, 지금 상태에서 허용하지 않는 전이는 409 다.
 */
@RestController
@RequestMapping("/api/v1/memory-links")
@RequiredArgsConstructor
public class MemoryLinkController {
    private final MemoryLinkService links;
    private final CurrentUserProvider currentUser;


    /** 요청자가 받아들이거나 거절할 수 있는 대체 제안을 오래된 것부터 낸다. */
    @GetMapping("/pending")
    public List<SupersedeProposalView> pending() {
        return links.pendingSupersedesFor(currentUser.require()).stream()
            .map(SupersedeProposalView::from)
            .toList();
    }


    @PostMapping("/{id}/accept")
    public MemoryLinkView accept(@PathVariable Long id) {
        return MemoryLinkView.from(links.accept(currentUser.require(), id));
    }


    @PostMapping("/{id}/reject")
    public MemoryLinkView reject(@PathVariable Long id) {
        return MemoryLinkView.from(links.reject(currentUser.require(), id));
    }


    /** 효력 있는 연결을 끊는다. 대체를 되돌리면 옛 항목이 다음 조립부터 다시 실린다. */
    @PostMapping("/{id}/revoke")
    public MemoryLinkView revoke(@PathVariable Long id) {
        return MemoryLinkView.from(links.revoke(currentUser.require(), id));
    }
}
