package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactFile;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultContent;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultBodies;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultRow;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자가 고른 HTML·CSS 를 대화와 독립된 보관본으로 암호화해 두고, 주인에게만 읽고 지우게 한다(#530 PR 1).
 *
 * <p>보관 주인은 대화 요청자다. 호출하는 쪽이 대화 주인을 먼저 확인하고 이 서비스에는 사용자 번호를 넘긴다. 에이전트
 * 주인에게는 보이지 않는다. 대화를 지워도 보관본은 남는다. 용량을 넘으면 새 보관만 거절하고 지우지 않는다.
 *
 * <p>용량 확인과 저장은 사용자별 잠금 안의 한 트랜잭션이다. Control Plane 은 한 인스턴스로 돈다. 인스턴스가 늘면
 * 데이터베이스 잠금으로 바꾼다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArtifactVaultService {

    private static final int LOCK_COUNT = 64;
    private static final String HTML = "text/html";
    private static final String CSS = "text/css";

    /**
     * 다른 파일을 부르는 표지다. 사진·스타일·프레임·{@code url()} 이 있으면 보관하지 않는다. 사진이 빠진 HTML 을 완성본으로
     * 두지 않으려는 보수적인 판정이다. 묶음 보관은 PR 2 가 맡는다.
     */
    private static final Pattern DEPENDENCY = Pattern.compile(
        "<\\s*(img|picture|source|video|audio|link|iframe|embed|object|image|use)\\b|srcset\\s*=|url\\s*\\(|@import",
        Pattern.CASE_INSENSITIVE);

    private final ArtifactService artifacts;
    private final ChatArtifactRepository rows;
    private final ArtifactVaultBodies bodies;
    private final ArtifactVaultProperties properties;
    private final PlatformTransactionManager transactionManager;
    private final ReentrantLock[] userLocks = newLocks();


    /**
     * 대화 폴더의 파일 하나를 사용자의 보관함에 암호화해 둔다.
     *
     * <p>같은 대화·경로·본문을 다시 보관하면 새로 만들지 않고 이미 있는 것을 돌려준다. 내용이 바뀌었으면 새 보관본이다.
     * 대화 주인 확인은 호출하는 쪽이 한다.
     */
    public ArtifactVaultItem vault(Long ownerUserId, Long conversationId, String relativePath) {
        String contentType = vaultableType(relativePath);
        ArtifactFile file = artifacts.find(conversationId, relativePath);
        if (file.byteSize() > properties.maxFileBytes()) {
            throw unsupported("this file is larger than the vault limit");
        }
        String text = readText(artifacts, file, properties.maxFileBytes());
        if (DEPENDENCY.matcher(text).find()) {
            throw unsupported("files that load other files are not kept yet");
        }
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        String fingerprint = ArtifactVaultBodies.fingerprint(conversationId, relativePath, plain);

        ReentrantLock lock = lockOf(ownerUserId);
        lock.lock();
        try {
            ChatArtifact existing = rows.findByVaultUserIdAndVaultFingerprint(ownerUserId, fingerprint).orElse(null);
            if (existing != null) {
                return item(existing);
            }
            return new TransactionTemplate(transactionManager).execute(status -> save(
                ownerUserId, conversationId, relativePath, fingerprint, text, plain.length, contentType));
        } finally {
            lock.unlock();
        }
    }


    /** 주인의 보관 목록이다. 새로 보관한 것이 먼저다. 본문은 풀지 않는다. */
    public List<ArtifactVaultItem> list(Long ownerUserId) {
        return rows.listVaulted(ownerUserId).stream()
            .map(row -> toItem(row))
            .toList();
    }


    /** 보관본 하나를 푼다. 없거나 남의 것이면 같은 응답이다. 풀지 못하면 본문 일부도 주지 않는다. */
    public ArtifactVaultContent read(Long ownerUserId, Long id) {
        ChatArtifact row = rows.findByIdAndVaultUserId(id, ownerUserId).orElseThrow(ArtifactVaultService::missing);
        String text = bodies.open(row);
        return new ArtifactVaultContent(
            fileName(row.path()), contentTypeOf(row.path()), text.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * 보관본 하나를 지운다. 행이 곧 본문이라 지우는 순간 읽을 수 없고 재시작으로 되살아나지 않는다.
     *
     * <p>이미 없는 것을 지우려 해도 같은 응답이다.
     */
    public void delete(Long ownerUserId, Long id) {
        int deleted = new TransactionTemplate(transactionManager).execute(status -> rows.deleteVaulted(id, ownerUserId));
        if (deleted == 0) {
            throw missing();
        }
    }


    private ArtifactVaultItem save(
        Long ownerUserId, Long conversationId, String path, String fingerprint, String text, long size,
        String contentType) {
        if (rows.sumVaultBytes(ownerUserId) + size > properties.userQuotaBytes()) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_FULL, "the vault of this user is full");
        }
        SealedText sealed = bodies.seal(ownerUserId, conversationId, path, fingerprint, text);
        try {
            ChatArtifact saved = rows.saveAndFlush(ChatArtifact.vaulted(
                conversationId, path, size, ownerUserId, sealed.keyId(), sealed.content(), fingerprint));
            log.info("artifact vaulted id={} contentType={}", saved.id(), contentType);
            return item(saved);
        } catch (DataIntegrityViolationException ex) {
            // 다른 요청이 같은 지문을 먼저 저장했다. 중복을 만들지 않고 그 줄을 돌려준다.
            return rows.findByVaultUserIdAndVaultFingerprint(ownerUserId, fingerprint)
                .map(ArtifactVaultService::item)
                .orElseThrow(() -> ex);
        }
    }


    private static String vaultableType(String relativePath) {
        String lower = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return HTML;
        }
        if (lower.endsWith(".css")) {
            return CSS;
        }
        throw unsupported("only html and css files are kept");
    }


    private static String contentTypeOf(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".css") ? CSS : HTML;
    }


    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }


    private static String readText(ArtifactService artifacts, ArtifactFile file, long limit) {
        try (InputStream in = artifacts.open(file)) {
            byte[] bytes = in.readNBytes((int)limit + 1);
            if (bytes.length > limit) {
                throw unsupported("this file is larger than the vault limit");
            }
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException ex) {
            throw unsupported("this file is not valid UTF-8 text");
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read an artifact to vault", ex);
        }
    }


    private static ReentrantLock[] newLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];
        for (int index = 0; index < LOCK_COUNT; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }


    private ReentrantLock lockOf(Long userId) {
        return userLocks[Math.floorMod(Long.hashCode(userId), LOCK_COUNT)];
    }


    private static ArtifactVaultItem item(ChatArtifact row) {
        return new ArtifactVaultItem(row.id(), row.path(), row.byteSize(), row.createdAt());
    }


    private static ArtifactVaultItem toItem(ArtifactVaultRow row) {
        return new ArtifactVaultItem(row.getId(), row.getPath(), row.getByteSize(), row.getCreatedAt());
    }


    private static ApiException unsupported(String message) {
        return new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, message);
    }


    /** 없는 번호와 남의 번호를 가르지 않는다. 가르면 번호를 훑어 남의 보관본이 있는지 알 수 있다. */
    private static ApiException missing() {
        return new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this kept file does not exist");
    }
}
