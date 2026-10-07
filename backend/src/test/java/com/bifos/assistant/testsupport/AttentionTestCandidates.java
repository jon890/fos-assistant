package com.bifos.assistant.testsupport;

import com.bifos.assistant.attention.application.AttentionCandidates;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 먼저 알리기 검사가 쓰는 후보 출처 대역이다. {@link IntegrationTestDoubles} 가 빈으로 둔다.
 *
 * <p>둘 다 후보를 내지 않아 다른 카드의 판정을 바꾸지 않는다. 읽기 실패는 기본이 꺼짐이고, 쓰는 검사가 {@code @Autowired} 로 받아
 * 켠다. {@link IntegrationTestIsolation} 이 검사마다 {@code reset()} 으로 되돌린다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AttentionTestCandidates {

    /** 켜 두면 기록 읽기가 실패하는 나를 기다리는 카드의 출처다. */
    public static final class FailingCandidates implements AttentionCandidates {
        private volatile boolean failing;

        /** 이 뒤의 읽기를 실패시킨다. */
        public void fail() {
            failing = true;
        }

        public void reset() {
            failing = false;
        }

        @Override
        public Set<CardKey> cards() {
            return Set.of(CardKey.NEEDS_ME);
        }

        @Override
        public List<AttentionCandidate> read(CurrentUser user, Instant now) {
            if (failing) {
                throw new IllegalStateException("검사가 낸 읽기 실패");
            }
            return List.of();
        }
    }

    /** 후보를 읽은 횟수를 세는 출처다. */
    public static final class ReadCountingCandidates implements AttentionCandidates {
        private final AtomicInteger reads = new AtomicInteger();

        /** 마지막 {@link #reset()} 뒤에 읽은 횟수다. */
        public int reads() {
            return reads.get();
        }

        public void reset() {
            reads.set(0);
        }

        @Override
        public Set<CardKey> cards() {
            return Set.of(CardKey.NEEDS_ME);
        }

        @Override
        public List<AttentionCandidate> read(CurrentUser user, Instant now) {
            reads.incrementAndGet();
            return List.of();
        }
    }
}
