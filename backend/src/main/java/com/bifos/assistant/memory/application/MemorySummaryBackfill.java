package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동할 때 요약 칸이 이관 전({@code NULL})인 기억에 요약을 처음 적는다({@code docs/features/memory/design.md} 의 「이관(PR1)」).
 *
 * <p>대상(개인 범위의 승인된 사실이고 꺼내는 방식이 {@code SEARCH} 이며 대체되지 않았고 본문이 {@link Memory#SUMMARY_MAX} 자 이하)은 본문을
 * 요약으로, 나머지는 빈 글을 적는다. 빈 글도 적어 다음 기동에 다시 보지 않는다. 배포 직전의 옛 개인 사실 구역에 들던 기억이 요약 구역에 그대로 든다.
 *
 * <p>암호화 이관({@link MemoryTextMigration}) 뒤에 돈다. 암호화가 켜진 설치는 데이터 key 로 옮긴 줄만 본다. 줄마다 새 트랜잭션에서 쓰기 잠금으로
 * 다시 읽고, revision 번호와 갱신 시각은 바꾸지 않는다. 풀지 못한 줄은 건너뛰고 번호만 남기며 기동은 잇는다. 로그에 글을 남기지 않는다.
 */
@Slf4j
@Component
@Order(MemorySummaryBackfill.ORDER)
public class MemorySummaryBackfill implements ApplicationRunner {

    /** 기동 이관의 차례다. 암호화 이관({@link MemoryTextMigration#ORDER}) 뒤에 돈다. */
    public static final int ORDER = 200;

    private final MemoryRepository memories;
    private final MemoryTexts texts;
    private final TransactionTemplate transactions;


    public MemorySummaryBackfill(MemoryRepository memories, MemoryTexts texts, PlatformTransactionManager transactionManager) {
        this.memories = memories;
        this.texts = texts;
        this.transactions = new TransactionTemplate(transactionManager);
        // 바깥 트랜잭션 안에서 불려도 줄마다 따로 커밋하고 되돌린다
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }


    @Override
    public void run(ApplicationArguments args) {
        try {
            backfill();
        } catch (RuntimeException e) {
            // 예외 메시지는 본문을 담을 수 있어 클래스 이름만 남긴다
            log.error("기억 요약 이관이 실패했다 exception={}", e.getClass().getName());
        }
    }


    /** @return 요약을 적은 줄 수. 빈 글을 적은 줄도 센다 */
    public int backfill() {
        List<Long> ids = texts.sealing() ? memories.findSealedIdsWithPendingSummary() : memories.findIdsWithPendingSummary();
        int filled = 0;
        List<Long> skipped = new ArrayList<>();
        for (Long id : ids) {
            try {
                Boolean done = transactions.execute(status -> fillOne(id));
                if (Boolean.TRUE.equals(done)) {
                    filled++;
                } else if (Boolean.FALSE.equals(done)) {
                    skipped.add(id);
                }
            } catch (RuntimeException e) {
                log.warn("기억 줄 하나에 요약을 적지 못했다 exception={}", e.getClass().getName());
                skipped.add(id);
            }
        }
        if (filled > 0) {
            log.info("기억 요약을 이관했다 rows={}", filled);
        }
        if (!skipped.isEmpty()) {
            log.warn("요약을 이관하지 못해 건너뛴 기억이 있다 memoryIds={}", skipped);
        }
        return filled;
    }


    /** @return 적었으면 참, 풀지 못해 건너뛰었으면 거짓, 이미 적혔거나 없으면 null */
    private Boolean fillOne(Long id) {
        Memory row = memories.findByIdForUpdate(id).orElse(null);
        if (row == null || row.storedSummary() != null) {
            return null;
        }
        if (!row.readable()) {
            return false;
        }
        String summary = target(row) ? row.content() : "";
        row.fillSummary(summary, texts.sealSummary(row.stored(), summary).orElse(null));
        memories.saveAndFlush(row);
        return true;
    }


    /** 배포 직전 옛 개인 사실 구역의 후보 조건과 같다. */
    static boolean target(Memory memory) {
        return memory.scope() == MemoryScope.USER
            && memory.entryType() == MemoryEntryType.MEMORY
            && memory.status() == MemoryStatus.ACCEPTED
            && memory.retrieval() == MemoryRetrieval.SEARCH
            && memory.supersededByMemoryId() == null
            && memory.content().length() <= Memory.SUMMARY_MAX;
    }
}
