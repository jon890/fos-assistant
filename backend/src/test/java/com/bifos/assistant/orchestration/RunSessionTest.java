package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.orchestration.domain.RunSession;
import org.junit.jupiter.api.Test;

/** 보낼 session 과 실행 줄에 적을 session 을 정하는 규칙을 고정한다. */
class RunSessionTest {

    @Test
    void 새_대화는_보낼_session과_적을_session이_같다() {
        RunSession session = RunSession.ofConversation("fos-a", "fos-a");

        assertThat(session.runtimeSessionId()).isEqualTo("fos-a");
        assertThat(session.correlationSessionId()).isEqualTo("fos-a");
    }

    @Test
    void 압축_교체_뒤에는_보낼_session만_바뀌고_적을_session은_뿌리를_가리킨다() {
        RunSession session = RunSession.ofConversation("fos-b", "fos-a");

        assertThat(session.runtimeSessionId()).isEqualTo("fos-b");
        assertThat(session.correlationSessionId()).isEqualTo("fos-a");
    }

    @Test
    void 뿌리가_빈_옛_대화는_두_값이_모두_보낸_session이다() {
        RunSession session = RunSession.ofConversation("legacy-session", null);

        assertThat(session.runtimeSessionId()).isEqualTo("legacy-session");
        assertThat(session.correlationSessionId()).isEqualTo("legacy-session");
    }

    @Test
    void fresh는_fos_접두사의_같은_값_둘이고_부를_때마다_다르다() {
        RunSession first = RunSession.fresh();
        RunSession second = RunSession.fresh();

        assertThat(first.runtimeSessionId()).startsWith("fos-");
        assertThat(first.correlationSessionId()).isEqualTo(first.runtimeSessionId());
        assertThat(second.runtimeSessionId()).isNotEqualTo(first.runtimeSessionId());
    }
}
