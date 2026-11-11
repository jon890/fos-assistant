package com.bifos.assistant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.crypto.domain.BackfillColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 번호 커서와 방향, 실패한 줄 처리를 데이터베이스 없이 확인한다(ADR-20261008 / data-encryption). */
class BackfillColumnTest {

    @Test
    @DisplayName("한 차례에 본 줄은 다음 차례에 건너뛰고 끝까지 가면 처음으로 돌아간다")
    void cursorMovesForwardAndWrapsAround() {
        List<Long> sealed = new ArrayList<>();
        BackfillColumn column = column(List.of(1L, 2L, 3L), sealed, id -> id != 2L);

        // 2번은 건너뛴 줄(대상이 아니게 바뀜)이라 고치지 않는다
        assertThat(column.run(true, 2, true)).isEqualTo(1);
        assertThat(column.run(true, 2, true)).isEqualTo(1);
        assertThat(sealed).containsExactly(1L, 3L);
        // 끝까지 갔으니 처음부터 다시 본다. 건너뛴 2번도 다시 시도하지만 고치지 못한다
        assertThat(column.run(true, 2, true)).isEqualTo(1);
    }


    @Test
    @DisplayName("한 줄이 예외를 던져도 다음 줄로 가고 고친 수에서는 뺀다")
    void failingRowDoesNotStopTheRun() {
        List<Long> sealed = new ArrayList<>();
        BackfillColumn column = new BackfillColumn(
            "t.c",
            (after, limit) -> List.of(1L, 2L, 3L).stream().filter(id -> id > after).limit(limit).toList(),
            (after, limit) -> List.of(),
            () -> 3,
            () -> 0,
            id -> {
                if (id == 2L) {
                    throw new IllegalStateException("본문이 든 예외 메시지");
                }
                sealed.add(id);
                return true;
            },
            id -> false);

        assertThat(column.run(true, 10, true)).isEqualTo(2);
        assertThat(sealed).containsExactly(1L, 3L);
    }


    @Test
    @DisplayName("키가 없거나 되돌리는 방향에 대상이 없으면 줄을 고치지 않는다")
    void missingKeyDoesNothing() {
        List<Long> sealed = new ArrayList<>();
        BackfillColumn column = column(List.of(1L, 2L), sealed, id -> true);

        assertThat(column.run(true, 10, false)).isZero();
        assertThat(column.run(false, 10, true)).isZero();
        assertThat(sealed).isEmpty();
    }


    @Test
    @DisplayName("키가 없으면 남은 줄 수를 한 번만 세고 그 뒤에는 세지 않는다")
    void missingKeyCountsOnlyOnce() {
        AtomicLong counted = new AtomicLong();
        BackfillColumn column = new BackfillColumn(
            "t.c", (after, limit) -> List.of(), (after, limit) -> List.of(), counted::incrementAndGet, () -> 0, id -> true, id -> true);

        column.run(true, 10, false);
        column.run(true, 10, false);

        assertThat(counted.get()).isEqualTo(1);
    }


    private static BackfillColumn column(List<Long> plain, List<Long> sealed, LongPredicate eligible) {
        AtomicLong count = new AtomicLong(plain.size());
        return new BackfillColumn(
            "t.c",
            (after, limit) -> plain.stream().filter(id -> id > after).limit(limit).toList(),
            (after, limit) -> List.of(),
            count::get,
            () -> 0,
            id -> {
                if (!eligible.test(id)) {
                    return false;
                }
                sealed.add(id);
                return true;
            },
            id -> false);
    }
}
