package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactBundle;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactBundleFile;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultContent;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.domain.ArtifactReferences;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultBodies;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultPart;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultRow;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultWriter;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자가 고른 결과물을 대화와 독립된 보관본으로 암호화해 두고, 주인에게만 읽고 지우게 한다(#530).
 *
 * <p>HTML 이 사진이나 스타일을 부르면 부르는 파일까지 한 묶음으로 보관한다. 묶음은 뿌리 행 하나와 그 뿌리를 가리키는
 * 보관 사본 행들이다. 외부 주소, {@code data:} 주소, 빠진 파일, 상한 초과는 묶음 전체를 거절한다. 사진 하나도 보관한다.
 *
 * <p>보관 주인은 대화 요청자다. 호출하는 쪽이 대화 주인을 먼저 확인하고 이 서비스에는 사용자 번호를 넘긴다. 에이전트
 * 주인에게는 보이지 않는다. 대화를 지워도 보관본은 남는다. 용량을 넘으면 새 보관만 거절하고 지우지 않는다.
 *
 * <p>용량 확인과 저장은 사용자별 잠금 안의 한 트랜잭션이다. Control Plane 은 한 인스턴스로 돈다. 인스턴스가 늘면
 * 데이터베이스 잠금으로 바꾼다. 동시에 도는 보관은 두 개까지이고 넘으면 기다리지 않고 거절한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArtifactVaultService {

    private static final int LOCK_COUNT = 64;
    private static final int VAULT_SLOTS = 2;

    private final ArtifactBundleReader reader;
    private final ChatArtifactRepository rows;
    private final ArtifactVaultWriter writer;
    private final ArtifactVaultBodies bodies;
    private final ArtifactVaultProperties properties;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final ReentrantLock[] userLocks = newLocks();

    /** 동시에 도는 보관의 자리다. 보관 하나는 파일 하나씩만 메모리에 올리므로 메모리 상한은 이 수에 비례한다. */
    private final Semaphore running = new Semaphore(VAULT_SLOTS);


    /**
     * 대화 폴더의 파일 하나를, 그 파일이 부르는 파일과 함께 사용자의 보관함에 암호화해 둔다.
     *
     * <p>같은 대화·경로·묶음 내용을 다시 보관하면 새로 만들지 않고 이미 있는 것을 돌려준다. 내용이 바뀌었으면 새 보관본이다.
     * 대화 주인 확인은 호출하는 쪽이 한다.
     */
    public ArtifactVaultItem vault(Long ownerUserId, Long conversationId, String relativePath) {
        return vault(ownerUserId, conversationId, relativePath, true).orElseThrow();
    }


    /**
     * turn 이 끝날 때 결과물을 자동으로 보관한다. 사용자가 같은 대화·경로·본문의 보관본을 지운 적이 있으면 다시 만들지 않고
     * 빈 값을 준다. 나머지는 {@link #vault(Long, Long, String)} 와 같다.
     */
    public Optional<ArtifactVaultItem> vaultAutomatically(Long ownerUserId, Long conversationId, String relativePath) {
        return vault(ownerUserId, conversationId, relativePath, false);
    }


    /** @param revive 사용자가 지운 같은 보관본이 있을 때 다시 만든다. 사용자가 직접 보관할 때만 참이다 */
    private Optional<ArtifactVaultItem> vault(
        Long ownerUserId, Long conversationId, String relativePath, boolean revive) {
        String rootPath = ArtifactReferences.normalize(relativePath);
        ArtifactBundleReader.requireVaultable(rootPath);
        if (!running.tryAcquire()) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_UNAVAILABLE, "too many files are being kept right now");
        }
        try {
            ArtifactBundle bundle = reader.scan(conversationId, rootPath, properties);
            ReentrantLock lock = lockOf(ownerUserId);
            lock.lock();
            try {
                ChatArtifact existing = rows.findByVaultUserIdAndVaultFingerprint(ownerUserId, bundle.fingerprint()).orElse(null);
                if (existing != null && !existing.isDeleted()) {
                    return Optional.of(new ArtifactVaultItem(
                        existing.id(), existing.path(), bundle.totalBytes(), bundle.fileCount(), existing.createdAt()));
                }
                if (existing != null && !revive) {
                    return Optional.empty();
                }
                return Optional.of(new TransactionTemplate(transactionManager).execute(status -> {
                    if (existing != null) {
                        // 지우기 이력을 지우고 같은 지문으로 새로 넣는다. 같은 트랜잭션이라 중간에 실패하면 이력이 되살아난다.
                        rows.deleteForgotten(existing.id(), ownerUserId);
                    }
                    return save(ownerUserId, bundle);
                }));
            } finally {
                lock.unlock();
            }
        } finally {
            running.release();
        }
    }


    /** 주인의 보관 목록이다. 새로 보관한 것이 먼저다. 묶음은 한 줄이다. 본문은 풀지 않는다. */
    public List<ArtifactVaultItem> list(Long ownerUserId) {
        return rows.listVaulted(ownerUserId).stream()
            .map(row -> toItem(row))
            .toList();
    }


    /** 보관본 하나의 뿌리 파일을 푼다. 없거나 남의 것이면 같은 응답이다. 풀지 못하면 본문 일부도 주지 않는다. */
    public ArtifactVaultContent read(Long ownerUserId, Long id) {
        return content(root(ownerUserId, id));
    }


    /**
     * 보관본 안의 파일 하나를 푼다. 미리보기의 HTML 이 상대 주소로 부르는 사진과 스타일이 이 길로 온다.
     *
     * <p>파일마다 주인을 다시 확인한다. 뿌리 행이 주인의 것이 아니거나 그 묶음에 없는 경로면 없는 것과 같은 응답이다.
     */
    public ArtifactVaultContent readFile(Long ownerUserId, Long id, String path) {
        ChatArtifact root = root(ownerUserId, id);
        String normalized = normalizedOrMissing(path);
        if (normalized.equals(root.path())) {
            return content(root);
        }
        return content(rows.findByVaultBundleIdAndPathAndVaultUserId(root.id(), normalized, ownerUserId)
            .orElseThrow(ArtifactVaultService::missing));
    }


    /**
     * 묶음 전체를 zip 으로 내려받기 전에 확인한다. 모든 파일을 한 번 풀어 보고 하나라도 풀리지 않으면 거절한다.
     *
     * <p>내려받기가 쓰기 시작한 뒤에는 상태 코드를 바꿀 수 없어, 풀리지 않는 파일이 있으면 시작하기 전에 거절한다. 파일은
     * 하나씩 풀고 버린다.
     *
     * @return 내려받을 zip 이름
     */
    public String prepareArchive(Long ownerUserId, Long id) {
        ChatArtifact root = root(ownerUserId, id);
        bodies.open(root);
        for (ArtifactVaultPart part : rows.listParts(root.id(), ownerUserId)) {
            bodies.open(rows.findById(part.getId()).orElseThrow(ArtifactVaultService::missing));
        }
        return baseName(fileName(root.path())) + ".zip";
    }


    /** 묶음 전체를 대화 폴더 안의 상대 경로 그대로 zip 에 쓴다. 파일은 하나씩 풀어 쓴다. */
    public void writeArchive(Long ownerUserId, Long id, OutputStream out) throws IOException {
        ChatArtifact root = root(ownerUserId, id);
        List<ArtifactVaultPart> parts = rows.listParts(root.id(), ownerUserId);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeEntry(zip, root);
            for (ArtifactVaultPart part : parts) {
                writeEntry(zip, rows.findById(part.getId()).orElseThrow(ArtifactVaultService::missing));
            }
        }
    }


    /**
     * 보관본 하나를 지운다. 묶음이면 안의 파일을 함께 지운다. 본문이 행에만 있어 지우는 순간 읽을 수 없고 재시작으로
     * 되살아나지 않는다.
     *
     * <p>뿌리 행은 본문과 경로를 비운 지우기 이력으로 남는다. 자동 보관은 이 이력이 있으면 같은 보관본을 다시 만들지 않는다.
     * 이미 없는 것을 지우려 해도 같은 응답이다. 묶음 안 파일의 번호로는 지우지 못한다.
     */
    public void delete(Long ownerUserId, Long id) {
        int deleted = new TransactionTemplate(transactionManager).execute(status -> {
            int forgotten = rows.forgetVaulted(id, ownerUserId, clock.instant());
            if (forgotten == 1) {
                rows.deleteVaultParts(id, ownerUserId);
            }
            return forgotten;
        });
        if (deleted == 0) {
            throw missing();
        }
    }


    /**
     * 묶음을 다시 읽어 암호화해 넣는다. 한 파일씩 읽고 넣은 행은 바로 떼어 메모리에 파일 하나만 남긴다.
     *
     * <p>훑은 뒤 파일이 바뀌거나 지워졌으면 거절하고, 트랜잭션이 되돌려 일부만 남지 않는다.
     */
    private ArtifactVaultItem save(Long ownerUserId, ArtifactBundle bundle) {
        if (rows.sumVaultBytes(ownerUserId) + bundle.totalBytes() > properties.userQuotaBytes()) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_FULL, "the vault of this user is full");
        }
        Long conversationId = bundle.conversationId();
        ArtifactBundleFile rootFile = bundle.root();
        byte[] rootBytes = reader.reread(conversationId, rootFile, properties.maxFileBytes());
        SealedText sealedRoot = bodies.seal(ownerUserId, conversationId, rootFile.path(), bundle.fingerprint(), null, rootBytes);
        // 같은 지문의 동시 요청은 사용자별 잠금이 막고, 남는 경쟁은 유일 인덱스가 거절한다. 같은 트랜잭션에서 다시 찾지 않는다.
        ChatArtifact root = ChatArtifact.vaulted(
            conversationId, rootFile.path(), rootFile.byteSize(), ownerUserId, sealedRoot.keyId(),
            sealedRoot.content(), bundle.fingerprint());
        Long rootId = writer.insertDetached(root);
        for (ArtifactBundleFile file : bundle.parts()) {
            byte[] bytes = reader.reread(conversationId, file, properties.maxFileBytes());
            String fingerprint = ArtifactVaultBodies.partFingerprint(bundle.fingerprint(), file.path());
            SealedText sealed = bodies.seal(ownerUserId, conversationId, file.path(), fingerprint, rootId, bytes);
            writer.insertDetached(ChatArtifact.vaultedPart(
                conversationId, file.path(), file.byteSize(), ownerUserId, sealed.keyId(), sealed.content(),
                fingerprint, rootId));
        }
        log.info("artifact vaulted id={} files={} bytes={}", rootId, bundle.fileCount(), bundle.totalBytes());
        return new ArtifactVaultItem(
            rootId, rootFile.path(), bundle.totalBytes(), bundle.fileCount(), root.createdAt());
    }


    private ChatArtifact root(Long ownerUserId, Long id) {
        return rows.findByIdAndVaultUserIdAndVaultBundleIdIsNullAndDeletedAtIsNull(id, ownerUserId)
            .orElseThrow(ArtifactVaultService::missing);
    }


    private ArtifactVaultContent content(ChatArtifact row) {
        byte[] body = bodies.open(row);
        return new ArtifactVaultContent(fileName(row.path()), ArtifactBundleReader.contentTypeOf(row.path()), body);
    }


    private void writeEntry(ZipOutputStream zip, ChatArtifact row) throws IOException {
        byte[] body = bodies.open(row);
        zip.putNextEntry(new ZipEntry(row.path()));
        zip.write(body);
        zip.closeEntry();
    }


    private static String normalizedOrMissing(String path) {
        try {
            String normalized = ArtifactReferences.normalize(path);
            if (normalized == null) {
                throw missing();
            }
            return normalized;
        } catch (ApiException ex) {
            throw missing();
        }
    }


    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }


    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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


    private static ArtifactVaultItem toItem(ArtifactVaultRow row) {
        return new ArtifactVaultItem(
            row.getId(),
            row.getPath(),
            row.getByteSize() + row.getPartBytes(),
            Math.toIntExact(row.getPartCount() + 1),
            row.getCreatedAt());
    }


    /** 없는 번호와 남의 번호를 가르지 않는다. 가르면 번호를 훑어 남의 보관본이 있는지 알 수 있다. */
    private static ApiException missing() {
        return new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this kept file does not exist");
    }
}
