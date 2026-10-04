package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.context.ContextBodyMode;
import com.bifos.assistant.context.ContextFreshness;
import com.bifos.assistant.context.ContextItem;
import com.bifos.assistant.context.ContextSource;
import com.bifos.assistant.context.ContextTrust;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 자동 turn 결과를 로그에 내도 결과 본문과 알림 줄의 글이 남지 않는지 본다(ADR-071). */
class AutoTurnResultTest {

    private static final String NOTICE = "승인한 「메모」 실행이 끝났어요";

    private static final String INPUT = "메모 본문: 화요일 10시 치과 예약";

    @Test
    @DisplayName("toString 은 key 와 글자 수와 항목의 출처와 참조만 내고 본문과 알림 줄의 글을 내지 않는다")
    void toStringOmitsInputAndNotice() {
        ContextItem item = new ContextItem(
                ContextSource.CONNECTOR_RESULT,
                "connector_action:41",
                MemoryScope.USER,
                7L,
                MemorySensitivity.SENSITIVE,
                ContextTrust.EXTERNAL,
                Instant.parse("2026-10-03T05:00:00Z"),
                ContextFreshness.FRESH,
                ContextBodyMode.INLINE,
                List.of(),
                null,
                null);

        String text = new AutoTurnResult("result-key", NOTICE, INPUT, item).toString();

        assertThat(text)
                .isEqualTo("AutoTurnResult[key=result-key, noticeChars=" + NOTICE.length() + ", inputChars="
                        + INPUT.length() + ", item=CONNECTOR_RESULT:connector_action:41]")
                .doesNotContain(NOTICE)
                .doesNotContain(INPUT);
    }

    @Test
    @DisplayName("항목이 없는 결과의 toString 은 item=null 이고 본문을 내지 않는다")
    void toStringWithoutItemOmitsInput() {
        String text = new AutoTurnResult("result-key", NOTICE, INPUT).toString();

        assertThat(text).contains("item=null").doesNotContain(NOTICE).doesNotContain(INPUT);
    }
}
