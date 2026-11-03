package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.domain.MemoryEmbeddingStamp;
import com.bifos.assistant.memory.domain.MemoryText;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.StoredMemoryText;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.entity.MemoryRevisionId;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.util.Sha256;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동할 때 데이터 key 로 옮기지 않은 기억과 revision 을 주인 데이터 key 로 다시 쓴다(ADR-20261103 / memory-encryption).
 *
 * <p>대상은 평문 줄이다. 줄마다 새 트랜잭션에서 쓰기 잠금으로 다시 읽고, 다시 쓴 암호문을 같은 트랜잭션에서 풀어 옮기기 전 평문의 해시와
 * 비교한다. 다르면 그 줄을 되돌린다. revision 번호와 갱신 시각은 바꾸지 않는다.
 *
 * <p>글을 옮긴 뒤 제안 중복 키와 임베딩 지문을 주인 key 의 HMAC 으로 바꾼다(스펙 DECISION17). 평문의 SHA-256 이던 지문은 그 자리에서
 * HMAC 으로 바꿔 다시 임베딩하지 않는다. 지금 글과 맞지 않는 낡은 지문은 벡터째 비워 채우는 작업이 다시 만든다.
 *
 * <p>한 줄이 실패해도 다음 줄로 가고 기동을 멈추지 않는다. 실패한 줄의 번호만 로그에 남기고, 남은 줄은 다음 기동에서 다시 본다. KEK 가
 * 없으면 옮기지 않고 남은 줄 수만 경고한다. 로그에 본문과 해시 값을 넣지 않는다.
 */
@Slf4j
@Component
public class MemoryTextMigration implements ApplicationRunner {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryEmbeddingRepository embeddings;
    private final MemoryEmbeddingProperties embeddingProperties;
    private final MemoryVectorCache vectorCache;
    private final MemoryTexts texts;
    private final TransactionTemplate transactions;


    public MemoryTextMigration(
        MemoryRepository memories,
        MemoryRevisionRepository revisions,
        MemoryEmbeddingRepository embeddings,
        MemoryEmbeddingProperties embeddingProperties,
        MemoryVectorCache vectorCache,
        MemoryTexts texts,
        PlatformTransactionManager transactionManager) {
        this.memories = memories;
        this.revisions = revisions;
        this.embeddings = embeddings;
        this.embeddingProperties = embeddingProperties;
        this.vectorCache = vectorCache;
        this.texts = texts;
        this.transactions = new TransactionTemplate(transactionManager);
        // 바깥 트랜잭션 안에서 불려도 줄마다 따로 커밋하고 되돌린다
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }


    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
            rekeyHashes();
        } catch (RuntimeException e) {
            // 예외 메시지와 스택은 본문을 담을 수 있어 클래스 이름만 남긴다
            log.error("기억 글의 데이터 key 이관이 실패했다 exception={}", e.getClass().getName());
        }
    }


    /** @return 옮긴 기억과 revision 의 줄 수. KEK 가 없으면 0 이다 */
    public int migrate() {
        List<Long> memoryIds = memories.findIdsWithoutDataKey();
        List<MemoryRevisionId> revisionIds = revisions.findIdsWithoutDataKey();
        if (!texts.sealing()) {
            if (!memoryIds.isEmpty() || !revisionIds.isEmpty()) {
                log.warn("KEK 가 없어 기억 글을 데이터 key 로 옮기지 않는다 memory={} revision={}", memoryIds.size(), revisionIds.size());
            }
            return 0;
        }
        Counter counter = new Counter();
        List<Long> failedMemories = new ArrayList<>();
        for (Long id : memoryIds) {
            counter.add(false, migrateOne(() -> migrateMemory(id), () -> failedMemories.add(id)));
        }
        List<MemoryRevisionId> failedRevisions = new ArrayList<>();
        for (MemoryRevisionId id : revisionIds) {
            counter.add(true, migrateOne(() -> migrateRevision(id), () -> failedRevisions.add(id)));
        }
        if (!memoryIds.isEmpty() || !revisionIds.isEmpty()) {
            log.info(
                "기억 글을 데이터 key 로 옮겼다 memory={} revision={} failedMemory={} failedRevision={}",
                counter.memories, counter.revisions, failedMemories.size(), failedRevisions.size());
        }
        if (!failedMemories.isEmpty() || !failedRevisions.isEmpty()) {
            log.error("데이터 key 로 옮기지 못한 기억 줄이 있다 memoryIds={} revisionIds={}", failedMemories, failedRevisions);
        }
        return counter.memories + counter.revisions;
    }


    /**
     * 제안 중복 키와 임베딩 지문을 주인 key 의 HMAC 으로 바꾼다. 이미 HMAC 인 줄은 건드리지 않아 기동마다 불러도 된다. KEK 가 없으면 하지
     * 않는다.
     *
     * @return 바꾸거나 비운 줄 수
     */
    public int rekeyHashes() {
        if (!texts.sealing()) {
            return 0;
        }
        List<Long> failed = new ArrayList<>();
        int changed = 0;
        for (Long id : memories.findIdsWithProposalDedupKey()) {
            Boolean done = migrateOne(() -> rekeyDedup(id), () -> failed.add(id));
            changed += Boolean.TRUE.equals(done) ? 1 : 0;
        }
        for (MemoryEmbeddingStamp stamp : embeddings.findStamps()) {
            Boolean done = migrateOne(() -> rekeyFingerprint(stamp), () -> failed.add(stamp.memoryId()));
            changed += Boolean.TRUE.equals(done) ? 1 : 0;
        }
        if (changed > 0 || !failed.isEmpty()) {
            log.info("기억의 중복 키와 임베딩 지문을 주인 key 로 바꿨다 changed={} failed={}", changed, failed.size());
        }
        if (!failed.isEmpty()) {
            log.error("중복 키나 임베딩 지문을 바꾸지 못한 기억이 있다 memoryIds={}", failed);
        }
        return changed;
    }


    private Boolean rekeyDedup(Long id) {
        Memory row = memories.findByIdForUpdate(id).orElse(null);
        if (row == null || row.proposalDedupKey() == null) {
            return null;
        }
        if (!row.readable()) {
            throw new IllegalStateException("the memory text cannot be opened");
        }
        String rekeyed = texts.proposalDedupKey(row.ownerUserId(), row.title(), row.content());
        if (rekeyed.equals(row.proposalDedupKey())) {
            return false;
        }
        row.rekeyProposalDedup(rekeyed);
        memories.saveAndFlush(row);
        return true;
    }


    /** 평문의 SHA-256 인 지문은 HMAC 으로 바꾸고, 지금 글과 맞지 않는 지문은 벡터째 비운다. 벡터는 바꾸지 않는다. */
    private Boolean rekeyFingerprint(MemoryEmbeddingStamp stamp) {
        Memory row = memories.findByIdForUpdate(stamp.memoryId()).orElse(null);
        if (row == null) {
            return null;
        }
        if (!row.readable()) {
            throw new IllegalStateException("the memory text cannot be opened");
        }
        String text = EmbeddingText.of(row.title(), row.content(), embeddingProperties.maxTextChars());
        Long ownerId = row.stored().ownerId();
        byte[] rekeyed = texts.embeddingFingerprint(row.scope(), ownerId, stamp.model(), text)
            .orElseThrow(() -> new IllegalStateException("the owner key is not available"));
        if (Arrays.equals(rekeyed, stamp.textFingerprint())) {
            return false;
        }
        int updated = Arrays.equals(EmbeddingText.plainFingerprint(stamp.model(), text), stamp.textFingerprint())
            ? embeddings.replaceFingerprint(stamp.memoryId(), stamp.textFingerprint(), rekeyed)
            : embeddings.clearVectors(List.of(stamp.memoryId()));
        vectorCache.evict(stamp.memoryId());
        return updated > 0;
    }


    /** @return 커밋했으면 바꿨는지, 건너뛰었거나 실패했으면 null 이다 */
    private Boolean migrateOne(Supplier<Boolean> work, Runnable onFailure) {
        try {
            return transactions.execute(status -> work.get());
        } catch (RuntimeException e) {
            // 예외 메시지는 본문을 담을 수 있어 클래스 이름만 남긴다. 번호는 끝에 한 줄로 모아 남긴다
            log.warn("기억 줄 하나를 데이터 key 로 옮기지 못했다 exception={}", e.getClass().getName());
            onFailure.run();
            return null;
        }
    }


    private Boolean migrateMemory(Long id) {
        Memory row = memories.findByIdForUpdate(id).orElse(null);
        if (row == null || row.dataKeyId() != null) {
            return null;
        }
        MemoryText plain = rewrite(row.stored(), row.readable(), row::title, row::content, row::seal);
        memories.saveAndFlush(row);
        verify(row.stored(), plain);
        return true;
    }


    private Boolean migrateRevision(MemoryRevisionId id) {
        MemoryRevision row = revisions.findByIdForUpdate(id).orElse(null);
        if (row == null || row.dataKeyId() != null) {
            return null;
        }
        MemoryText plain = rewrite(row.stored(), row.readable(), row::title, row::content, row::seal);
        revisions.saveAndFlush(row);
        verify(row.stored(), plain);
        return true;
    }


    private MemoryText rewrite(
        StoredMemoryText where,
        boolean readable,
        Supplier<String> title,
        Supplier<String> content,
        Consumer<SealedMemoryText> seal) {
        if (!readable) {
            throw new IllegalStateException("the memory text cannot be opened");
        }
        MemoryText plain = new MemoryText(title.get(), content.get());
        seal.accept(texts.seal(where, plain.title(), plain.content()).orElseThrow());
        return plain;
    }


    /**
     * 다시 쓴 암호문을 풀어 옮기기 전 평문과 해시를 비교한다. 다르면 예외로 그 줄의 트랜잭션을 되돌린다.
     *
     * <p>flush 한 엔티티의 값으로 푼다. 컬럼보다 긴 글은 MySQL strict 모드가 flush 에서 오류로 거절하므로 잘린 채 커밋되지 않는다.
     */
    private void verify(StoredMemoryText sealed, MemoryText plain) {
        MemoryText reopened = texts.open(sealed).orElseThrow(() -> new IllegalStateException("the rewritten text cannot be opened"));
        if (!Objects.equals(Sha256.hex(reopened.title()), Sha256.hex(plain.title()))
            || !Objects.equals(Sha256.hex(reopened.content()), Sha256.hex(plain.content()))) {
            throw new IllegalStateException("the rewritten text does not match");
        }
    }


    /** 옮긴 줄 수다. 한 기동 안에서만 쓴다. */
    private static final class Counter {

        private int memories;
        private int revisions;


        void add(boolean revision, Boolean moved) {
            if (moved == null) {
                return;
            }
            if (revision) {
                revisions++;
            } else {
                memories++;
            }
        }
    }
}
