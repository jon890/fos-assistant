package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.SkillSource;
import com.bifos.assistant.skill.application.SkillsChanged;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 커맨드가 부를 수 있는 이름의 캐시를 본다. 30초 뒤에 다시 읽고, 읽기가 실패하거나 읽는 동안 스킬이 바뀌면
 * 캐시에 두지 않는다.
 */
class SkillCommandCatalogTest {

    private final SkillService skills = mock(SkillService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
    private final SkillCommandCatalog catalog = new SkillCommandCatalog(skills, clock);
    private final Agent agent = mock(Agent.class);

    @BeforeEach
    void setUp() {
        when(agent.id()).thenReturn(7L);
        when(agent.code()).thenReturn("dad");
    }

    private void listed(String... enabledNames) {
        // 앞에서 던지게 한 대역을 부르지 않고 바꾼다.
        doReturn(listOf(enabledNames)).when(skills).commandList(agent);
    }

    @Test
    @DisplayName("삼십초 안에는 들고 있던 이름을 주고 삼십초가 지나면 다시 읽는다")
    void holdsNamesForThirtySecondsAndRereadsAfter() {
        listed("shopping");
        assertThat(catalog.enabledNames(agent)).containsExactly("shopping");

        listed("cooking");
        clock.advance(Duration.ofSeconds(29));
        assertThat(catalog.enabledNames(agent)).as("29초 뒤").containsExactly("shopping");

        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.enabledNames(agent)).as("30초 뒤").containsExactly("cooking");
    }

    @Test
    @DisplayName("목록을 읽다 Hermes 가 실패하면 그 예외를 올리고 다음에 다시 읽는다")
    void rethrowsAndRereadsNextTimeWhenHermesFailsWhileReadingList() {
        when(skills.commandList(agent)).thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> catalog.enabledNames(agent))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);

        listed("shopping");
        assertThat(catalog.enabledNames(agent)).containsExactly("shopping");
    }

    @Test
    @DisplayName("목록을 읽는 동안 SkillsChanged 가 오면 읽은 목록을 캐시에 넣지 않는다")
    void doesNotCacheListReadWhileSkillsChangedArrives() {
        // 첫 읽기가 끝나기 전에 스킬이 바뀌었다. 그 읽기는 바뀌기 전의 목록을 들고 돌아온다.
        when(skills.commandList(agent))
                .thenAnswer(call -> {
                    catalog.on(new SkillsChanged(7L));
                    return listOf("shopping");
                })
                .thenReturn(listOf("cooking"));

        assertThat(catalog.enabledNames(agent)).as("사건과 겹친 읽기").containsExactly("shopping");
        assertThat(catalog.enabledNames(agent)).as("같은 시각의 다음 읽기").containsExactly("cooking");
        assertThat(catalog.enabledNames(agent)).as("그다음은 캐시").containsExactly("cooking");
    }

    @Test
    @DisplayName("skills toolset 이 꺼져 있으면 켜진 스킬이 있어도 빈 집합이다")
    void returnsEmptySetWhenSkillsToolsetIsOffEvenWithEnabledSkills() {
        when(skills.commandList(agent))
                .thenReturn(new SkillList(
                        List.of(new SkillListItem("shopping", "", SkillSource.UPLOADED, true, null)),
                        false,
                        false,
                        30));

        assertThat(catalog.enabledNames(agent)).isEmpty();
    }

    private static SkillList listOf(String... enabledNames) {
        List<SkillListItem> items = Arrays.stream(enabledNames)
                .map(name -> new SkillListItem(name, "", SkillSource.UPLOADED, true, null))
                .toList();
        return new SkillList(items, false, true, 30);
    }

    /** 테스트가 시각을 앞으로 옮긴다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
