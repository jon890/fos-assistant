package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.presentation.MemoryDtos.CollectionView;
import com.bifos.assistant.memory.presentation.MemoryDtos.CreateDocumentRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.DocumentSummaryView;
import com.bifos.assistant.memory.presentation.MemoryDtos.DocumentView;
import com.bifos.assistant.memory.presentation.MemoryDtos.UpdateDocumentRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자가 자기 문서를 쓰고 고치는 API 다(ADR-057). 웹의 JWT 로 부르고 요청자가 주인인 USER 범위의 문서만 다룬다.
 * 삭제는 {@code DELETE /api/v1/memories/{id}} 를 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MemoryDocumentController {

    private final MemoryService memories;
    private final CurrentUserProvider currentUser;

    @GetMapping("/memory-documents")
    public List<DocumentSummaryView> list() {
        return memories.documentsOf(currentUser.require()).stream()
                .map(DocumentSummaryView::from)
                .toList();
    }

    @GetMapping("/memory-documents/{id}")
    public DocumentView get(@PathVariable Long id) {
        Memory memory = memories.documentFor(currentUser.require(), id);
        return DocumentView.from(memory, memories.contentOf(memory));
    }

    @PostMapping("/memory-documents")
    public DocumentView create(@Valid @RequestBody CreateDocumentRequest request) {
        CurrentUser user = currentUser.require();
        Memory memory = memories.createDocument(
                user,
                request.collection(),
                request.documentKey(),
                request.title(),
                request.content(),
                sensitivity(request.sensitive()));
        return DocumentView.from(memory, request.content());
    }

    @PutMapping("/memory-documents/{id}")
    public DocumentView update(@PathVariable Long id, @Valid @RequestBody UpdateDocumentRequest request) {
        Memory memory = memories.reviseDocument(
                currentUser.require(),
                id,
                request.content(),
                sensitivity(request.sensitive()),
                request.expectedRevision());
        return DocumentView.from(memory, request.content());
    }

    /** 문서 폼과 서비스 토큰 발급이 고를 collection 목록이다. */
    @GetMapping("/memory-collections")
    public List<CollectionView> collections() {
        return memories.collectionsFor(currentUser.require()).stream()
                .map(CollectionView::from)
                .toList();
    }

    private static MemorySensitivity sensitivity(boolean sensitive) {
        return sensitive ? MemorySensitivity.SENSITIVE : MemorySensitivity.NORMAL;
    }
}
