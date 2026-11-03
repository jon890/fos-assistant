package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.MemoryText;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.StoredMemoryText;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.entity.MemoryRevisionId;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.util.Sha256;
import java.util.ArrayList;
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
 * <p>대상은 평문 줄과 ADR-055 의 옛 key 로 본문을 암호화한 줄이다. 줄마다 새 트랜잭션에서 쓰기 잠금으로 다시 읽고, 다시 쓴 암호문을 같은
 * 트랜잭션에서 풀어 옮기기 전 평문의 해시와 비교한다. 다르면 그 줄을 되돌린다. revision 번호와 갱신 시각은 바꾸지 않는다.
 *
 * <p>한 줄이 실패해도 다음 줄로 가고 기동을 멈추지 않는다. 실패한 줄의 번호만 로그에 남기고, 남은 줄은 다음 기동에서 다시 본다. KEK 가
 * 없으면 옮기지 않고 남은 줄 수만 경고한다. 로그에 본문과 해시 값을 넣지 않는다.
 */
@Slf4j
@Component
public class MemoryTextMigration implements ApplicationRunner {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryTexts texts;
    private final TransactionTemplate transactions;


    public MemoryTextMigration(
        MemoryRepository memories,
        MemoryRevisionRepository revisions,
        MemoryTexts texts,
        PlatformTransactionManager transactionManager) {
        this.memories = memories;
        this.revisions = revisions;
        this.texts = texts;
        this.transactions = new TransactionTemplate(transactionManager);
        // 바깥 트랜잭션 안에서 불려도 줄마다 따로 커밋하고 되돌린다
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }


    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
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
                "기억 글을 데이터 key 로 옮겼다 memory={} revision={} legacyVerified={} failedMemory={} failedRevision={}",
                counter.memories, counter.revisions, counter.legacy, failedMemories.size(), failedRevisions.size());
        }
        if (!failedMemories.isEmpty() || !failedRevisions.isEmpty()) {
            log.error("데이터 key 로 옮기지 못한 기억 줄이 있다 memoryIds={} revisionIds={}", failedMemories, failedRevisions);
        }
        return counter.memories + counter.revisions;
    }


    /** @return 커밋했으면 옛 key 줄이었는지, 건너뛰었거나 실패했으면 null 이다 */
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
        boolean legacy = row.contentKeyId() != null;
        MemoryText plain = rewrite(row.stored(), row.readable(), row::title, row::content, row::seal);
        memories.saveAndFlush(row);
        verify(row.stored(), plain);
        return legacy;
    }


    private Boolean migrateRevision(MemoryRevisionId id) {
        MemoryRevision row = revisions.findByIdForUpdate(id).orElse(null);
        if (row == null || row.dataKeyId() != null) {
            return null;
        }
        boolean legacy = row.contentKeyId() != null;
        MemoryText plain = rewrite(row.stored(), row.readable(), row::title, row::content, row::seal);
        revisions.saveAndFlush(row);
        verify(row.stored(), plain);
        return legacy;
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
        private int legacy;


        void add(boolean revision, Boolean legacyKey) {
            if (legacyKey == null) {
                return;
            }
            if (revision) {
                revisions++;
            } else {
                memories++;
            }
            if (legacyKey) {
                legacy++;
            }
        }
    }
}
