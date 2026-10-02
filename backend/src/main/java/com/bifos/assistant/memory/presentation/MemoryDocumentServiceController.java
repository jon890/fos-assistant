package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.ServicePrincipal;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.presentation.MemoryDtos.ServiceDocumentView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다른 서비스가 서비스 토큰으로 문서를 읽는 API 다(ADR-056). 읽기만 있다. 인증은 {@link ServiceTokenInterceptor} 가 한다.
 */
@RestController
@RequestMapping("/api/v1/service/memory-documents")
@RequiredArgsConstructor
@Slf4j
public class MemoryDocumentServiceController {

    private static final String EXPIRES_AT_HEADER = "X-Service-Token-Expires-At";

    private final MemoryService memories;

    @GetMapping("/{collection}/{documentKey}")
    public ResponseEntity<ServiceDocumentView> read(
            @RequestAttribute(ServiceTokenInterceptor.PRINCIPAL_ATTRIBUTE) ServicePrincipal principal,
            @PathVariable String collection,
            @PathVariable String documentKey) {
        Memory memory = memories.documentForService(principal.userId(), principal.access(), collection, documentKey);
        ServiceDocumentView body = new ServiceDocumentView(
                memory.collection(),
                memory.documentKey(),
                memory.title(),
                memories.contentOf(memory),
                memory.revision(),
                memory.updatedAt());
        // 본문과 제목은 적지 않는다
        log.info(
                "service document read userId={} tokenId={} collection={} documentKey={} revision={}",
                principal.userId(),
                principal.tokenId(),
                memory.collection(),
                memory.documentKey(),
                memory.revision());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(EXPIRES_AT_HEADER, principal.expiresAt().toString())
                .body(body);
    }
}
