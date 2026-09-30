package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.SkillSource;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 커맨드가 부를 수 있는 이름의 캐시가 30초 뒤에 다시 읽고, 읽기가 실패하면 캐시에 두지 않는 것을 본다. */
class SkillCommandCatalogTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final SkillService skills = mock(SkillService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
    private final SkillCommandCatalog catalog = new SkillCommandCatalog(skills, clock);
    private final Agent agent = mock(Agent.class);

    @BeforeEach
    void 준비한다() {
        when(agent.id()).thenReturn(7L);
        when(agent.code()).thenReturn("dad");
    }

    private void listed(String... enabledNames) {
        List<SkillListItem> items = Arrays.stream(enabledNames)
                .map(name -> new SkillListItem(name, "", SkillSource.UPLOADED, true, null))
                .toList();
        when(skills.list(any(), eq("dad"))).thenReturn(new SkillList(items, true, true));
    }

    @Test
    void 삼십초_안에는_들고_있던_이름을_주고_삼십초가_지나면_다시_읽는다() {
        listed("shopping");
        assertThat(catalog.enabledNames(DAD, agent)).containsExactly("shopping");

        listed("cooking");
        clock.advance(Duration.ofSeconds(29));
        assertThat(catalog.enabledNames(DAD, agent)).as("29초 뒤").containsExactly("shopping");

        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.enabledNames(DAD, agent)).as("30초 뒤").containsExactly("cooking");
    }

    @Test
    void 목록을_읽다_Hermes_가_실패하면_그_예외를_올리고_다음에_다시_읽는다() {
        when(skills.list(any(), eq("dad"))).thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> catalog.enabledNames(DAD, agent))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);

        listed("shopping");
        assertThat(catalog.enabledNames(DAD, agent)).containsExactly("shopping");
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
