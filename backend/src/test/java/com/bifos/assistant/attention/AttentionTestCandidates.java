package com.bifos.assistant.attention;

import com.bifos.assistant.attention.application.AttentionCandidates;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 먼저 알리기 검사가 함께 쓰는 후보 출처 대역이다.
 *
 * <p>검사마다 {@code @TestConfiguration} 을 따로 두면 컨텍스트가 따로 뜬다. 두 대역은 꺼 두면 후보를 내지 않는다. 검사는
 * {@code @BeforeEach} 에서 자기 대역을 꺼 둔 상태로 되돌린다.
 */
@TestConfiguration
public class AttentionTestCandidates {

    /** 켜 두면 기록 읽기가 실패하는 나를 기다리는 카드의 출처다. */
    public static final FailingCandidates FAILING = new FailingCandidates();

    /** 후보를 읽은 횟수를 세는 출처다. 후보를 내지 않아 다른 카드의 판정을 바꾸지 않는다. */
    public static final ReadCountingCandidates PROBE = new ReadCountingCandidates();

    @Bean
    AttentionCandidates failingNeedsMeCandidates() {
        return FAILING;
    }

    @Bean
    AttentionCandidates readCountingCandidates() {
        return PROBE;
    }

    public static final class FailingCandidates implements AttentionCandidates {
        public volatile boolean failing;

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

    public static final class ReadCountingCandidates implements AttentionCandidates {
        public final AtomicInteger reads = new AtomicInteger();

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
