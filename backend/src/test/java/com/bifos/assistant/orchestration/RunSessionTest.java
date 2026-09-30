package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.orchestration.domain.RunSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 보낼 session 과 실행 줄에 적을 session 을 정하는 규칙을 고정한다. */
class RunSessionTest {

    @Test
    @DisplayName("새 대화는 보낼 session과 적을 session이 같다")
    void newConversationSendSessionEqualsRecordedSession() {
        RunSession session = RunSession.ofConversation("fos-a", "fos-a");

        assertThat(session.runtimeSessionId()).isEqualTo("fos-a");
        assertThat(session.correlationSessionId()).isEqualTo("fos-a");
    }

    @Test
    @DisplayName("압축 교체 뒤에는 보낼 session만 바뀌고 적을 session은 뿌리를 가리킨다")
    void afterCompactionOnlySendSessionChangesAndRecordedPointsToRoot() {
        RunSession session = RunSession.ofConversation("fos-b", "fos-a");

        assertThat(session.runtimeSessionId()).isEqualTo("fos-b");
        assertThat(session.correlationSessionId()).isEqualTo("fos-a");
    }

    @Test
    @DisplayName("뿌리가 빈 옛 대화는 두 값이 모두 보낸 session이다")
    void oldConversationWithBlankRootHasBothValuesAsSendSession() {
        RunSession session = RunSession.ofConversation("legacy-session", null);

        assertThat(session.runtimeSessionId()).isEqualTo("legacy-session");
        assertThat(session.correlationSessionId()).isEqualTo("legacy-session");
    }

    @Test
    @DisplayName("fresh는 fos 접두사의 같은 값 둘이고 부를 때마다 다르다")
    void freshMakesTwoEqualValuesWithFosPrefixAndDiffersEachCall() {
        RunSession first = RunSession.fresh();
        RunSession second = RunSession.fresh();

        assertThat(first.runtimeSessionId()).startsWith("fos-");
        assertThat(first.correlationSessionId()).isEqualTo(first.runtimeSessionId());
        assertThat(second.runtimeSessionId()).isNotEqualTo(first.runtimeSessionId());
    }
}
