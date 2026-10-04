package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.DelegationOutputClip;
import com.bifos.assistant.orchestration.application.DelegationOutput;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelegationOutputTest {

    private static final String NOTICE = "\n\n[답이 5자를 넘어 뒷부분을 잘랐다]";

    private final DelegationOutputClip clip =
            new DelegationOutput(new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 5, Duration.ofSeconds(20)));

    @Test
    @DisplayName("상한 이하의 답은 그대로 돌려준다")
    void clipKeepsShortOutput() {
        assertThat(clip.clip("가가가")).isEqualTo("가가가");
    }

    @Test
    @DisplayName("상한을 넘는 답은 상한까지 자르고 안내 한 줄을 붙인다")
    void clipTruncatesOverLimit() {
        assertThat(clip.clip("가".repeat(6))).isEqualTo("가".repeat(5) + NOTICE);
    }

    @Test
    @DisplayName("상한 자리에서 대리 쌍이 갈리면 그 앞에서 자른다")
    void clipDoesNotSplitSurrogatePair() {
        assertThat(clip.clip("가가가가😀가")).isEqualTo("가가가가" + NOTICE);
    }

    @Test
    @DisplayName("null 답은 clip 이 빈 글을, partial 이 null 을 돌려준다")
    void nullOutput() {
        assertThat(clip.clip(null)).isEmpty();
        assertThat(clip.partial(null)).isNull();
    }

    @Test
    @DisplayName("공백뿐인 답은 partial 이 null 이다")
    void partialBlankIsNull() {
        assertThat(clip.partial("  \n ")).isNull();
    }

    @Test
    @DisplayName("partial 은 상한을 넘는 답을 clip 과 같게 자른다")
    void partialClipsOverLimit() {
        String output = "가".repeat(6);
        assertThat(clip.partial(output)).isEqualTo(clip.clip(output));
    }
}
