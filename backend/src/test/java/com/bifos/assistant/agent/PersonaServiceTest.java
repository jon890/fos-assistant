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
import org.junit.jupiter.api.DisplayName;
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
    void setUp() {
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent("dad", "dad-profile", OWNER.id())));
    }

    @Test
    @DisplayName("지금 본문과 그 해시를 돌려준다")
    void returnsCurrentBodyAndItsHash() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot snapshot = personas.read(OWNER, "dad");

        assertThat(snapshot.body()).isEqualTo(SOUL);
        assertThat(snapshot.bodyHash()).isEqualTo(Sha256.hex16(SOUL));
        assertThat(snapshot.editable()).isTrue();
    }

    @Test
    @DisplayName("파일이 없는 profile 은 본문이 빈 문자열이다")
    void returnsEmptyBodyForProfileWithoutFile() {
        PersonaSnapshot snapshot = personas.read(OWNER, "dad");

        assertThat(snapshot.body()).isEmpty();
        assertThat(snapshot.bodyHash()).isEqualTo(Sha256.hex16(""));
    }

    @Test
    @DisplayName("맞는 해시로 쓰면 그 본문이 그 profile 로 넘어간다")
    void writesBodyToProfileWhenHashMatches() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot written = personas.write(OWNER, "dad", "새 성격", Sha256.hex16(SOUL));

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
        assertThat(written.body()).isEqualTo("새 성격");
        assertThat(written.bodyHash()).isEqualTo(Sha256.hex16("새 성격"));
    }

    @Test
    @DisplayName("한 글자 다른 해시로 쓰면 거절하고 쓰지 않는다")
    void rejectsWriteWithOneCharDifferentHash() {
        dashboard.seedSoul("dad-profile", SOUL);
        String wrong = "0" + Sha256.hex16(SOUL).substring(1);

        assertThatThrownBy(() -> personas.write(OWNER, "dad", "새 성격", wrong))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PERSONA_STALE);
        assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    @DisplayName("지금 본문이 있는데 해시가 비어 있으면 거절한다")
    void rejectsBlankHashWhenBodyExists() {
        dashboard.seedSoul("dad-profile", SOUL);

        assertThatThrownBy(() -> personas.write(OWNER, "dad", "새 성격", ""))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PERSONA_STALE);
        assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    @DisplayName("파일이 없는 profile 에는 해시 없이 처음 쓸 수 있다")
    void allowsFirstWriteWithoutHashForProfileWithoutFile() {
        personas.write(OWNER, "dad", "새 성격", null);

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
    }

    @Test
    @DisplayName("공백만 있는 본문은 거절하고 쓰지 않는다")
    void rejectsWhitespaceOnlyBodyWithoutWriting() {
        dashboard.seedSoul("dad-profile", SOUL);

        assertThatThrownBy(() -> personas.write(OWNER, "dad", "   \n ", Sha256.hex16(SOUL)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    @DisplayName("앞뒤 공백은 떼고 쓴다")
    void trimsSurroundingWhitespaceOnWrite() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot written = personas.write(OWNER, "dad", "  새 성격 \n", Sha256.hex16(SOUL));

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
        assertThat(written.body()).isEqualTo("새 성격");
    }

    @Test
    @DisplayName("줄바꿈으로 끝나는 본문을 읽어 그대로 되보내면 저장된다")
    void savesBodyEndingWithNewlineWhenReadAndSentBackUnchanged() {
        dashboard.seedSoul("dad-profile", SOUL);

        PersonaSnapshot read = personas.read(OWNER, "dad");
        personas.write(OWNER, "dad", "새 성격", read.bodyHash());

        assertThat(dashboard.soulWrites()).containsExactly(new SoulWrite("dad-profile", "새 성격"));
    }

    @Test
    @DisplayName("대시보드에는 code 가 아니라 그 에이전트의 profile 이름이 넘어간다")
    void sendsAgentProfileNameToDashboardInsteadOfCode() {
        dashboard.seedSoul("dad-profile", SOUL);

        personas.write(OWNER, "dad", "새 성격", Sha256.hex16(SOUL));

        assertThat(dashboard.soulWrites())
                .extracting(SoulWrite::profile)
                .containsExactly("dad-profile");
    }

    private static Agent agent(String code, String profile, Long ownerUserId) {
        return Agent.of(code, "아빠", profile, "http://127.0.0.1:1/p/" + profile, CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, ownerUserId);
    }
}
