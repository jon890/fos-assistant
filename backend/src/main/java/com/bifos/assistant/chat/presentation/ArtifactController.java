package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ArtifactFile;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.util.SandboxedContentPolicy;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트가 대화 폴더에 만든 결과물 파일을 대화 주인에게 준다.
 *
 * <p>모든 응답에 스크립트가 돌지 않는 머리글을 붙인다. 화면의 iframe 뿐 아니라 이 주소를 직접 열어도 스크립트가
 * 돌지 않게 하려는 것이다. 근거는 ADR-027 에 있다.
 *
 * <p>조건부 요청을 받는다. 200 응답에 {@code ETag} 와 {@code Last-Modified} 를 붙이고, 같은 값으로 다시 물으면
 * 파일을 열지 않고 본문 없이 304 로 답한다. 주인 확인과 경로 판정이 먼저이므로 남의 대화에 맞는 {@code ETag} 를
 * 보내도 404 다.
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ArtifactController {

    /** 스크립트를 막고 같은 출처의 사진과 스타일만 부르게 한다. */
    static final String CONTENT_SECURITY_POLICY = SandboxedContentPolicy.HTML;

    private final ArtifactService artifacts;
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;

    /**
     * {@code {*path}} 는 디코딩한 상대 경로를 앞의 {@code /} 와 함께 준다.
     *
     * <p>정규화되지 않은 경로({@code /../}, {@code %2e})는 Spring Security 의 방화벽이 이 메서드 앞에서 거절한다.
     * 폴더 밖인지는 {@link ArtifactService#find} 가 실제 경로로 다시 판정한다.
     */
    @GetMapping("/conversations/{conversationId}/files/{*path}")
    public ResponseEntity<InputStreamResource> read(
            @PathVariable UUID conversationId,
            @PathVariable("path") String path,
            @RequestHeader HttpHeaders requestHeaders) {
        CurrentUser user = currentUser.require();
        Long number = access.requireOwnId(user, conversationId);
        String relativePath = path.startsWith("/") ? path.substring(1) : path;
        ArtifactFile file = artifacts.find(number, relativePath);
        if (notModified(requestHeaders, file.etag(), file.lastModified())) {
            // 파일을 열지 않고 답한다. 연 스트림을 304 로 끝내면 아무도 닫지 않는다.
            return common(ResponseEntity.status(HttpStatus.NOT_MODIFIED), file).build();
        }
        return common(ResponseEntity.ok(), file)
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.byteSize())
                .body(new InputStreamResource(artifacts.open(file)));
    }

    /** 200 과 304 가 같은 코드로 붙이는 머리글이다. */
    private static ResponseEntity.BodyBuilder common(ResponseEntity.BodyBuilder builder, ArtifactFile file) {
        return builder
                .eTag(file.etag())
                .lastModified(file.lastModified())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache");
    }

    /**
     * 요청의 조건이 지금 파일과 맞아 본문 없이 답해도 되는지 판정한다.
     *
     * <p><b>Spring 의 {@code HttpEntityMethodProcessor} 가 304 로 바꾸는 요청을 여기서 거짓이라 하면 안 된다.</b>
     * 200 으로 돌려준 {@code ResponseEntity} 에 {@code ETag} 나 {@code Last-Modified} 가 있으면 Spring 이 조건부
     * 요청을 다시 판정해 304 로 바꾼다. 그러면 이미 연 스트림이 닫히지 않는다. 반대로 여기서만 참인 요청은 파일을 열지
     * 않고 304 로 끝나므로 안전하다. GET 의 {@code If-None-Match: *} 가 그 경우다. Spring 은 별표를 쓰기 요청에서만
     * 맞는 것으로 본다. 두 판정을 견주는 검사는 {@code ArtifactControllerNotModifiedTest} 에 있다.
     *
     * <ul>
     *   <li>{@code If-None-Match} 가 있으면 그것만 본다. {@code *} 이거나 값 하나가 {@code W/} 를 뗀 채 같으면 참이다.
     *   <li>없고 {@code If-Modified-Since} 가 있으면, 수정 시각을 초 단위로 내린 값이 그 값보다 크지 않을 때 참이다.
     *   <li>둘 다 없으면 거짓이다.
     * </ul>
     */
    static boolean notModified(HttpHeaders requestHeaders, String etag, Instant lastModified) {
        var ifNoneMatch = requestHeaders.getIfNoneMatch();
        if (!ifNoneMatch.isEmpty()) {
            String current = withoutWeakPrefix(etag);
            return ifNoneMatch.stream()
                    .anyMatch(value -> "*".equals(value) || current.equals(withoutWeakPrefix(value)));
        }
        long ifModifiedSince = requestHeaders.getIfModifiedSince();
        return ifModifiedSince >= 0 && lastModified.toEpochMilli() / 1000 * 1000 <= ifModifiedSince;
    }

    private static String withoutWeakPrefix(String etag) {
        String trimmed = etag.trim();
        return trimmed.startsWith("W/") ? trimmed.substring(2) : trimmed;
    }
}
