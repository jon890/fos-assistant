package com.bifos.assistant.chat.artifact.presentation;

import com.bifos.assistant.chat.artifact.application.ArtifactAutoVault;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultContent;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.AutoVaultRetryRequest;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.AutoVaultSettingView;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.AutoVaultStatusView;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.VaultItemView;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.VaultListView;
import com.bifos.assistant.chat.artifact.presentation.ArtifactVaultDtos.VaultRequest;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.util.SandboxedContentPolicy;
import com.bifos.assistant.user.application.ArtifactAutoVaultSetting;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 사용자가 보관한 결과물의 목록과 읽기, 지우기다. 주인은 웹 토큰의 사용자다. 묶음은 뿌리 파일의 번호 하나로 읽고 지운다.
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
    private final ArtifactAutoVault autoVault;
    private final ArtifactAutoVaultSetting autoVaultSetting;


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


    /** 내가 결과물 자동 보관을 켰는지다. */
    @GetMapping("/auto")
    public AutoVaultSettingView autoVaultSetting() {
        return new AutoVaultSettingView(autoVaultSetting.isEnabled(currentUser.require().id()));
    }


    /** 결과물 자동 보관을 켜거나 끈다. 끄면 다음 turn 부터 보관하지 않고 이미 보관한 것은 그대로다. */
    @PutMapping("/auto")
    public AutoVaultSettingView changeAutoVault(@Valid @RequestBody AutoVaultSettingView request) {
        Long user = currentUser.require().id();
        autoVaultSetting.change(user, request.enabled());
        return new AutoVaultSettingView(autoVaultSetting.isEnabled(user));
    }


    /** 자동 보관이 실패한 결과물을 지금 다시 보관한다. 대화가 내 것이 아니면 없는 대화와 같은 응답이다. */
    @PostMapping("/auto/retry")
    public AutoVaultStatusView retryAutoVault(@Valid @RequestBody AutoVaultRetryRequest request) {
        Long conversation = access.requireOwnId(currentUser.require(), request.conversationId());
        return AutoVaultStatusView.of(autoVault.retry(conversation, request.messageId(), request.path()));
    }


    /**
     * 보관본 뿌리 파일의 본문이다. 스크립트가 돌지 않는 머리글을 붙이고 캐시하지 않는다. {@code download=1} 이면 내려받기다.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id, @RequestParam(required = false) String download) {
        return body(vault.read(currentUser.require().id(), id), "1".equals(download));
    }


    /**
     * 보관본 안의 파일 하나다. 묶음 미리보기의 HTML 이 상대 주소로 부르는 사진과 스타일이 이 길로 온다.
     *
     * <p>{@code {*path}} 는 디코딩한 상대 경로를 앞의 {@code /} 와 함께 준다. 파일마다 보관 주인을 다시 확인한다.
     */
    @GetMapping("/{id}/files/{*path}")
    public ResponseEntity<byte[]> file(@PathVariable Long id, @PathVariable("path") String path) {
        String relativePath = path.startsWith("/") ? path.substring(1) : path;
        return body(vault.readFile(currentUser.require().id(), id, relativePath), false);
    }


    /** 묶음 전체를 zip 으로 내려받는다. 풀리지 않는 파일이 있으면 쓰기 전에 거절한다. */
    @GetMapping("/{id}/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable Long id) {
        Long owner = currentUser.require().id();
        String name = vault.prepareArchive(owner, id);
        ContentDisposition disposition = ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build();
        StreamingResponseBody stream = out -> vault.writeArchive(owner, id, out);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("Content-Security-Policy", SandboxedContentPolicy.NONE)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(stream);
    }


    private static ResponseEntity<byte[]> body(ArtifactVaultContent content, boolean download) {
        ContentDisposition disposition = (download
            ? ContentDisposition.attachment()
            : ContentDisposition.inline())
                .filename(content.name(), StandardCharsets.UTF_8)
                .build();
        String type = content.contentType().startsWith("text/")
            ? content.contentType() + ";charset=UTF-8"
            : content.contentType();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(type))
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
