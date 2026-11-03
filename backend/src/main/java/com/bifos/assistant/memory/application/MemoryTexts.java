package com.bifos.assistant.memory.application;

import com.bifos.assistant.crypto.domain.KeyOwner;
import com.bifos.assistant.crypto.domain.OwnedTextCipher;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.memory.domain.MemoryText;
import com.bifos.assistant.memory.domain.MemoryTextOpener;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.StoredMemoryText;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기억과 revision 의 제목과 본문을 주인 데이터 key 로 암호화하고 푼다(ADR-20261103 / memory-encryption).
 *
 * <p>{@code USER} 는 주인 사용자의 데이터 key, {@code GROUP} 은 그 그룹의 데이터 key 다. AAD 는
 * {@code memory:<기억 번호>:<title 또는 content>:<범위>:<주인 번호>} 이다. 암호문을 다른 기억이나 다른 컬럼, 다른 주인의 줄로 옮기면
 * 풀리지 않는다.
 *
 * <p>KEK 가 없는 설치는 평문으로 저장하고 한 번 경고한다. KEK 가 있는데 데이터 key 를 풀지 못하면 저장을 거절한다. 평문으로 내려 저장하지
 * 않는다. 로그에는 기억 번호만 남긴다.
 *
 * <p>같은 글이 같은 값이어야 하는 칸(제안 중복 키, 임베딩 지문)은 주인 key 에서 뽑은 HMAC 으로 만든다. 평문의 해시를 DB 에 남기지 않는다(스펙
 * DECISION17). KEK 가 없는 설치는 글이 평문이라 SHA-256 을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryTexts implements MemoryTextOpener {

    private final OwnedTextCipher cipher;
    private final MemoryRepository memories;
    private final AtomicBoolean warnedPlain = new AtomicBoolean();


    /** 새 글을 암호화하는가. 거짓이면 KEK 가 없는 설치다 */
    public boolean sealing() {
        return cipher.enabled();
    }


    /**
     * 새 기억을 넣는다. 암호화가 켜져 있으면 빈 글로 넣어 번호를 얻고 같은 트랜잭션에서 암호문을 적는다. 부르는 쪽의 트랜잭션 안에서 부른다.
     *
     * @throws ApiException 데이터 key 를 풀지 못할 때. MEMORY_ENCRYPTION_UNAVAILABLE. 줄을 넣기 전에 거절한다
     */
    public Memory insert(Memory memory) {
        if (!sealing()) {
            warnPlainOnce();
            return memories.saveAndFlush(memory);
        }
        StoredMemoryText where = memory.stored();
        // 넣기 전에 주인 데이터 key 를 쓸 수 있는지 본다. 넣은 뒤에 거절하면 부르는 쪽이 잡았을 때 빈 줄이 남는다
        sealOrReject(where, () -> cipher.seal(owner(where), "memory:probe", ""));
        memory.detachForSealing();
        Memory saved = memories.saveAndFlush(memory);
        saved.seal(seal(saved.stored(), saved.title(), saved.content()).orElseThrow());
        return saved;
    }


    /**
     * 그 자리의 제목과 본문을 암호화한다.
     *
     * @param where 암호문이 놓일 줄. 기억 번호와 범위, 주인만 쓴다
     * @return 암호화가 꺼져 있으면 빈 값이다. 부르는 쪽은 평문으로 둔다
     * @throws ApiException 데이터 key 를 풀지 못할 때. MEMORY_ENCRYPTION_UNAVAILABLE
     */
    public Optional<SealedMemoryText> seal(StoredMemoryText where, String title, String content) {
        if (!sealing()) {
            warnPlainOnce();
            return Optional.empty();
        }
        KeyOwner owner = owner(where);
        SealedText sealedTitle = sealOrReject(where, () -> cipher.seal(owner, aad(where, "title"), title));
        SealedText sealedContent = sealOrReject(where, () -> cipher.seal(owner, aad(where, "content"), content));
        return Optional.of(new SealedMemoryText(sealedTitle.content(), sealedContent.content(), sealedContent.keyId()));
    }


    @Override
    public Optional<MemoryText> open(StoredMemoryText stored) {
        if (stored.dataKeyId() != null) {
            KeyOwner owner = owner(stored);
            Optional<String> title = cipher.open(stored.dataKeyId(), owner, aad(stored, "title"), stored.title());
            Optional<String> content = title.isEmpty()
                ? Optional.empty()
                : cipher.open(stored.dataKeyId(), owner, aad(stored, "content"), stored.content());
            return content.map(body -> new MemoryText(title.get(), body));
        }
        return Optional.of(new MemoryText(stored.title(), stored.content()));
    }


    /**
     * 제안 중복 키다. 같은 사용자가 같은 제목과 본문을 다시 제안하면 같은 값이다.
     *
     * @throws ApiException 데이터 key 를 풀지 못할 때. MEMORY_ENCRYPTION_UNAVAILABLE
     */
    public String proposalDedupKey(Long ownerUserId, String title, String content) {
        String text = ownerUserId + "\u0000" + title + "\u0000" + content;
        try {
            return cipher.mac(KeyOwner.user(ownerUserId), "memory:proposal_dedup", text)
                .map(HexFormat.of()::formatHex)
                .orElseGet(() -> Sha256.hex(text));
        } catch (IllegalStateException e) {
            log.warn("제안 중복 키를 만들지 못해 저장을 거절한다 ownerUserId={} reason={}", ownerUserId, e.getMessage());
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "memory encryption key is not available");
        }
    }


    /**
     * 임베딩 지문이다. 모델 이름과 임베딩한 글이 같으면 같은 값이다. 모델을 바꾸면 모든 지문이 달라진다.
     *
     * @param scope 기억의 범위. 주인 key 를 고른다
     * @param ownerId {@code USER} 면 주인 사용자 번호, {@code GROUP} 이면 그룹 번호
     * @return 주인 데이터 key 를 풀지 못하면 빈 값이다. 그 기억은 벡터를 쓰지 않는다
     */
    public Optional<byte[]> embeddingFingerprint(MemoryScope scope, Long ownerId, String model, String text) {
        String message = model + "\n" + text;
        KeyOwner owner = scope == MemoryScope.GROUP ? KeyOwner.group(ownerId) : KeyOwner.user(ownerId);
        try {
            return Optional.of(cipher.mac(owner, "memory:embedding", message)
                .orElseGet(() -> EmbeddingText.plainFingerprint(model, text)));
        } catch (IllegalStateException e) {
            log.warn("임베딩 지문을 만들지 못했다 owner={} reason={}", owner, e.getMessage());
            return Optional.empty();
        }
    }


    private SealedText sealOrReject(StoredMemoryText where, Supplier<Optional<SealedText>> sealing) {
        try {
            return sealing.get().orElseThrow(() -> new IllegalStateException("memory encryption is not enabled"));
        } catch (IllegalStateException e) {
            log.warn("기억을 암호화하지 못해 저장을 거절한다 memoryId={} reason={}", where.memoryId(), e.getMessage());
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "memory encryption key is not available");
        }
    }


    private void warnPlainOnce() {
        if (warnedPlain.compareAndSet(false, true)) {
            log.warn("KEK 가 설정되지 않아 기억의 제목과 본문을 평문으로 저장한다");
        }
    }


    private static KeyOwner owner(StoredMemoryText stored) {
        return stored.scope() == MemoryScope.GROUP ? KeyOwner.group(stored.ownerId()) : KeyOwner.user(stored.ownerId());
    }


    private static String aad(StoredMemoryText stored, String column) {
        return "memory:" + stored.memoryId() + ":" + column + ":" + stored.scope().name() + ":" + stored.ownerId();
    }

}
