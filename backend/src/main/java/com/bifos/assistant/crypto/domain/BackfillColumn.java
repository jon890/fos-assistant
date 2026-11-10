package com.bifos.assistant.crypto.domain;

import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 본문 칸 하나를 번호 순으로 조금씩 암호화하거나 평문으로 되돌리는 배치 한 차례를 돌린다(ADR-20261008 / data-encryption).
 *
 * <p>한 줄이 실패해도 다음 줄로 가고, 실패한 줄의 번호와 예외 클래스 이름만 로그에 남긴다. 예외 메시지와 본문은 남기지 않는다. 번호
 * 커서를 메모리에 둬 한 차례에 본 줄은 다음 차례에 건너뛴다. 끝까지 가면 처음으로 돌아가 건너뛴 줄을 다시 본다. 멈췄다 다시 떠도 대상이 key
 * 칸으로 가려지므로 처음부터 이어서 돌아도 이미 고친 줄은 다시 보지 않는다.
 *
 * <p>한 줄을 고치는 일은 {@code seal}, {@code unseal} 이 맡는다. 줄마다 새 트랜잭션에서 쓰기 잠금으로 다시 읽고 대상 조건을 다시 본다.
 * 고쳤으면 참, 그 사이 바뀌어 건너뛰었으면 거짓이다.
 */
@Slf4j
public final class BackfillColumn {

    /** 번호 {@code after} 보다 큰 대상 줄의 번호를 번호 순으로 최대 {@code limit} 개 읽는다. */
    @FunctionalInterface
    public interface IdReader {

        List<Long> read(long after, int limit);
    }


    private final String name;
    private final IdReader plainIds;
    private final IdReader sealedIds;
    private final LongSupplier plainCount;
    private final LongSupplier sealedCount;
    private final LongPredicate seal;
    private final LongPredicate unseal;

    private long cursor;
    private Boolean cursorSealing;
    private boolean warnedDisabled;


    /**
     * @param name 로그에 남기는 칸 이름. {@code 표.칸} 꼴이다
     */
    public BackfillColumn(
        String name,
        IdReader plainIds,
        IdReader sealedIds,
        LongSupplier plainCount,
        LongSupplier sealedCount,
        LongPredicate seal,
        LongPredicate unseal) {
        this.name = name;
        this.plainIds = plainIds;
        this.sealedIds = sealedIds;
        this.plainCount = plainCount;
        this.sealedCount = sealedCount;
        this.seal = seal;
        this.unseal = unseal;
    }


    /**
     * 한 차례 돈다.
     *
     * @param sealing 참이면 평문을 암호화하고 거짓이면 암호문을 평문으로 되돌린다
     * @param enabled 암호화 키가 있는가. 없으면 줄을 고치지 않고, 처음 한 번만 남은 줄 수를 경고한다
     * @return 이번 차례에 고친 줄 수
     */
    public synchronized int run(boolean sealing, int limit, boolean enabled) {
        String direction = sealing ? "SEAL" : "UNSEAL";
        LongSupplier remainingCount = sealing ? plainCount : sealedCount;
        if (!enabled) {
            // KEK 없이 평문으로 쓰는 설치에서 매분 같은 경고와 전체 세기를 되풀이하지 않는다. 프로세스를 띄운 뒤 한 번만 남긴다
            if (!warnedDisabled) {
                warnedDisabled = true;
                long remaining = remainingCount.getAsLong();
                if (remaining > 0) {
                    log.warn("KEK 가 없어 옛 줄을 고치지 않는다 column={} direction={} remaining={}", name, direction, remaining);
                }
            }
            return 0;
        }
        if (cursorSealing == null || cursorSealing != sealing) {
            cursor = 0;
            cursorSealing = sealing;
        }
        List<Long> ids = (sealing ? plainIds : sealedIds).read(cursor, limit);
        if (ids.isEmpty() && cursor > 0) {
            cursor = 0;
            ids = (sealing ? plainIds : sealedIds).read(cursor, limit);
        }
        if (ids.isEmpty()) {
            return 0;
        }
        LongPredicate one = sealing ? seal : unseal;
        int changed = 0;
        int skipped = 0;
        for (Long id : ids) {
            try {
                if (one.test(id)) {
                    changed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // 예외 메시지와 스택은 본문을 담을 수 있어 클래스 이름과 번호만 남긴다
                skipped++;
                log.warn("옛 줄 하나를 고치지 못했다 column={} id={} exception={}", name, id, e.getClass().getName());
            }
            cursor = id;
        }
        log.info(
            "옛 줄을 고쳤다 column={} direction={} changed={} skipped={} remaining={}",
            name, direction, changed, skipped, remainingCount.getAsLong());
        return changed;
    }
}
