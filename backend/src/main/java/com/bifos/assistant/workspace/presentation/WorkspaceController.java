package com.bifos.assistant.workspace.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.util.SandboxedContentPolicy;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.application.model.WorkspaceFile;
import com.bifos.assistant.workspace.presentation.WorkspaceDtos.ListingView;
import com.bifos.assistant.workspace.presentation.WorkspaceDtos.StatusView;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자가 자기 실행 공간을 보는 경로다. 계약은 {@code docs/code-architecture.md} 의 「실행 공간 파일」 이 갖는다.
 *
 * <p>주인은 웹 토큰의 사용자다. 경로 변수와 인자는 그 사용자 디렉터리 안의 상대 경로뿐이다.
 */
@RestController
@RequestMapping("/api/v1/workspace")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspace;
    private final CurrentUserProvider currentUser;

    /** 루트가 설정되지 않아도 200 이다. 화면이 「쓸 수 없음」 을 그릴 수 있게 한다. */
    @GetMapping
    public StatusView status() {
        return StatusView.of(workspace.status(currentUser.require()));
    }

    @GetMapping("/entries")
    public ListingView entries(@RequestParam(required = false) String path) {
        return ListingView.of(workspace.list(currentUser.require(), path));
    }

    /**
     * 미리보기나 내려받기 본문이다. {@code {*path}} 는 디코딩한 상대 경로를 앞의 {@code /} 와 함께 준다.
     *
     * <p>{@code ETag} 와 {@code Last-Modified} 를 붙이지 않는다. 붙이면 Spring 이 조건부 요청을 304 로 바꿔 연 스트림이 닫히지 않는다.
     */
    @GetMapping("/files/{*path}")
    public ResponseEntity<InputStreamResource> file(
            @PathVariable("path") String path, @RequestParam(required = false) String download) {
        WorkspaceFile file = workspace.open(currentUser.require(), path, "1".equals(download));
        ContentDisposition disposition = (file.download()
                        ? ContentDisposition.attachment()
                        : ContentDisposition.inline())
                .filename(file.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(
                        "Content-Security-Policy",
                        file.html() ? SandboxedContentPolicy.HTML : SandboxedContentPolicy.NONE)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new InputStreamResource(file.body()));
    }
}
