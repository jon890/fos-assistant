package com.bifos.assistant.chat.artifact.presentation;

import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultContent;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.VaultItemView;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.VaultListView;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.VaultRequest;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.util.SandboxedContentPolicy;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자가 보관한 결과물의 목록과 읽기, 지우기다. 주인은 웹 토큰의 사용자다.
 *
 * <p>보관은 대화 주인만 할 수 있다. 읽기와 지우기는 보관 주인만 한다. 남의 번호는 없는 번호와 같은 응답이다.
 */
@RestController
@RequestMapping("/api/v1/artifact-vault")
@RequiredArgsConstructor
public class ArtifactVaultController {

    private final ArtifactVaultService vault;
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;


    @GetMapping
    public VaultListView list() {
        CurrentUser user = currentUser.require();
        return new VaultListView(vault.list(user.id()).stream().map(VaultItemView::of).toList());
    }


    /** 대화 결과물 하나를 보관한다. 대화가 내 것이 아니면 없는 대화와 같은 응답이다. */
    @PostMapping
    public VaultItemView vault(@Valid @RequestBody VaultRequest request) {
        CurrentUser user = currentUser.require();
        Long conversation = access.requireOwnId(user, request.conversationId());
        return VaultItemView.of(vault.vault(user.id(), conversation, request.path()));
    }


    /**
     * 보관본 본문이다. 스크립트가 돌지 않는 머리글을 붙이고 캐시하지 않는다. {@code download=1} 이면 내려받기다.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id, @RequestParam(required = false) String download) {
        ArtifactVaultContent content = vault.read(currentUser.require().id(), id);
        ContentDisposition disposition = ("1".equals(download)
            ? ContentDisposition.attachment()
            : ContentDisposition.inline())
                .filename(content.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType() + ";charset=UTF-8"))
            .contentLength(content.body().length)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("Content-Security-Policy", SandboxedContentPolicy.HTML)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(content.body());
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vault.delete(currentUser.require().id(), id);
        return ResponseEntity.noContent().build();
    }
}
