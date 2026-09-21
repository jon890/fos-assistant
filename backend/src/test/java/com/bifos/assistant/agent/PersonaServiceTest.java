package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PersonaService;
import com.bifos.assistant.agent.application.PersonaSnapshot;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.StubHermesDashboardClient;
import com.bifos.assistant.hermes.StubHermesDashboardClient.SoulWrite;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.user.domain.UserRole;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 성격을 읽고 쓰는 순서와 덮어쓰기를 막는 판정을 본다.
 *
 * <p>본문은 데이터베이스가 아니라 그 profile 의 {@code SOUL.md} 에 있으므로, 어느 profile 에 무엇이
 * 넘어갔는지를 대역의 기록으로 단언한다.
 */
class PersonaServiceTest {

    private static final String SOUL = "너는 아빠의 비서다.\n";
    private static final CurrentUser OWNER =
            new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final AgentRepository agents = mock(AgentRepository.class);
    private final StubHermesDashboardClient dashboard = new StubHermesDashboardClient();
    private final PersonaService personas = new PersonaService(new AgentService(agents), dashboard);

    @BeforeEach
    void 준비한다() {
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent("dad", "dad-profile", OWNER.id())));
    }

    @Test
    void 지금_본문과_그_해시를_돌려준다() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot snapshot = personas.read(OWNER, "dad");

        assertThat(snapshot.body()).isEqualTo(SOUL);
        assertThat(snapshot.bodyHash()).isEqualTo(Sha256.hex16(SOUL));
        assertThat(snapshot.editable()).isTrue();
    }

    @Test
    void 파일이_없는_profile_은_본문이_빈_문자열이다() {
        PersonaSnapshot snapshot = personas.read(OWNER, "dad");

        assertThat(snapshot.body()).isEmpty();
        assertThat(snapshot.bodyHash()).isEqualTo(Sha256.hex16(""));
    }

    @Test
    void 맞는_해시로_쓰면_그_본문이_그_profile_로_넘어간다() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot written = personas.write(OWNER, "dad", "새 성격", Sha256.hex16(SOUL));

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
        assertThat(written.body()).isEqualTo("새 성격");
        assertThat(written.bodyHash()).isEqualTo(Sha256.hex16("새 성격"));
    }

    @Test
    void 한_글자_다른_해시로_쓰면_거절하고_쓰지_않는다() {
        dashboard.seedSoul("dad-profile", SOUL);
        String wrong = "0" + Sha256.hex16(SOUL).substring(1);

        assertThatThrownBy(() -> personas.write(OWNER, "dad", "새 성격", wrong))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PERSONA_STALE);
        assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    void 지금_본문이_있는데_해시가_비어_있으면_거절한다() {
        dashboard.seedSoul("dad-profile", SOUL);

        assertThatThrownBy(() -> personas.write(OWNER, "dad", "새 성격", ""))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PERSONA_STALE);
        assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    void 파일이_없는_profile_에는_해시_없이_처음_쓸_수_있다() {
        personas.write(OWNER, "dad", "새 성격", null);

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
    }

    @Test
    void 공백만_있는_본문은_거절하고_쓰지_않는다() {
        dashboard.seedSoul("dad-profile", SOUL);

        assertThatThrownBy(() -> personas.write(OWNER, "dad", "   \n ", Sha256.hex16(SOUL)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    void 앞뒤_공백은_떼고_쓴다() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot written = personas.write(OWNER, "dad", "  새 성격 \n", Sha256.hex16(SOUL));

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
        assertThat(written.body()).isEqualTo("새 성격");
    }

    @Test
    void 줄바꿈으로_끝나는_본문을_읽어_그대로_되보내면_저장된다() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot read = personas.read(OWNER, "dad");
        personas.write(OWNER, "dad", "새 성격", read.bodyHash());

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
    }

    @Test
    void 대시보드에는_code_가_아니라_그_에이전트의_profile_이름이_넘어간다() {
        dashboard.seedSoul("dad-profile", SOUL);

        personas.write(OWNER, "dad", "새 성격", Sha256.hex16(SOUL));

        assertThat(dashboard.soulWrites())
                .extracting(SoulWrite::profile)
                .containsExactly("dad-profile");
    }

    private static Agent agent(String code, String profile, Long ownerUserId) {
        return Agent.of(code, "아빠", profile, "http://127.0.0.1:1/p/" + profile, "openai-codex",
                "gpt-5.5", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, ownerUserId);
    }
}
