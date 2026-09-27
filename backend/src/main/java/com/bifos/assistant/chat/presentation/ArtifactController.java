package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ArtifactContent;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트가 대화 폴더에 만든 결과물 파일을 대화 주인에게 준다.
 *
 * <p>모든 응답에 스크립트가 돌지 않는 머리글을 붙인다. 화면의 iframe 뿐 아니라 이 주소를 직접 열어도 스크립트가
 * 돌지 않게 하려는 것이다. 근거는 ADR-027 에 있다.
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ArtifactController {

    /** 스크립트를 막고 같은 출처의 사진과 스타일만 부르게 한다. */
    static final String CONTENT_SECURITY_POLICY = "sandbox allow-same-origin allow-popups "
            + "allow-popups-to-escape-sandbox; default-src 'none'; img-src 'self' data:; "
            + "style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'";

    private final ArtifactService artifacts;
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;

    /**
     * {@code {*path}} 는 디코딩한 상대 경로를 앞의 {@code /} 와 함께 준다.
     *
     * <p>정규화되지 않은 경로({@code /../}, {@code %2e})는 Spring Security 의 방화벽이 이 메서드 앞에서 거절한다.
     * 폴더 밖인지는 {@link ArtifactService#open} 이 실제 경로로 다시 판정한다.
     */
    @GetMapping("/conversations/{conversationId}/files/{*path}")
    public ResponseEntity<InputStreamResource> read(
            @PathVariable UUID conversationId, @PathVariable("path") String path) {
        CurrentUser user = currentUser.require();
        Long number = access.requireOwnId(user, conversationId);
        String relativePath = path.startsWith("/") ? path.substring(1) : path;
        ArtifactContent content = artifacts.open(number, relativePath);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .body(new InputStreamResource(content.body()));
    }
}
