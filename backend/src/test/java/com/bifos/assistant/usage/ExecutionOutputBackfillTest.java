package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.application.dto.BackfillDirection;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionOutputBackfill;
import com.bifos.assistant.usage.execution.application.ExecutionOutputs;
import com.bifos.assistant.usage.execution.application.ExecutionRecorder;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 키를 넣기 전에 저장한 위임 답을 조금씩 암호화하고, 되돌릴 때 평문으로 되돌리는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
class ExecutionOutputBackfillTest {

    private static final String ANSWER = "평문-표식-9921 지난달과 같아요";
    private static final int ALL = 100_000;

    @Autowired
    ExecutionOutputBackfill backfill;

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    ExecutionOutputs outputs;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser owner;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        // 같은 데이터베이스를 쓰는 다른 검사가 남긴 평문 줄이 먼저 잡히지 않게 비운다
        backfill.run(BackfillDirection.SEAL, ALL);
        owner = user();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "월급 확인", null, Instant.now()));
    }


    @AfterEach
    void tearDown() {
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("평문 답이 암호화되고 다시 돌려도 바뀌지 않으며 열면 평문이다")
    void sealsPlainOutputAndStaysStable() {
        AgentExecution execution = plainExecution(ANSWER);

        assertThat(backfill.run(BackfillDirection.SEAL, ALL)).isEqualTo(1);

        Map<String, Object> row = rowOf(execution.id());
        assertThat((String)row.get("OUTPUT_TEXT")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("OUTPUT_KEY_ID")).isNotNull();
        assertThat(outputs.open(executions.findById(execution.id()).orElseThrow())).isEqualTo(ANSWER);

        assertThat(backfill.run(BackfillDirection.SEAL, ALL)).isZero();
        assertThat(rowOf(execution.id()).get("OUTPUT_TEXT")).isEqualTo(row.get("OUTPUT_TEXT"));
    }


    @Test
    @DisplayName("한 번에 고치는 줄 수는 배치 크기까지다")
    void sealsOnlyBatchSizeRowsPerRun() {
        AgentExecution first = plainExecution(ANSWER);
        AgentExecution second = plainExecution("평문-표식-9922 둘");
        AgentExecution third = plainExecution("평문-표식-9923 셋");

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isEqualTo(2);
        assertThat(rowOf(third.id()).get("OUTPUT_KEY_ID")).isNull();

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isEqualTo(1);
        assertThat(rowOf(first.id()).get("OUTPUT_KEY_ID")).isNotNull();
        assertThat(rowOf(second.id()).get("OUTPUT_KEY_ID")).isNotNull();
        assertThat(rowOf(third.id()).get("OUTPUT_KEY_ID")).isNotNull();
    }


    @Test
    @DisplayName("답이 없는 실행은 대상이 아니라 null 로 남는다")
    void rowWithoutOutputIsLeftAlone() {
        AgentExecution execution = recorder.start(
            user(owner), conversation.executionConversation(), agent(owner), null, null, 0L);

        backfill.run(BackfillDirection.SEAL, ALL);

        Map<String, Object> row = rowOf(execution.id());
        assertThat(row.get("OUTPUT_TEXT")).isNull();
        assertThat(row.get("OUTPUT_KEY_ID")).isNull();
    }


    @Test
    @DisplayName("UNSEAL 이면 암호문 답이 평문과 빈 key 칸으로 돌아가고 OFF 이면 돌지 않는다")
    void unsealRestoresPlaintext() {
        AgentExecution execution = plainExecution(ANSWER);
        backfill.run(BackfillDirection.SEAL, ALL);
        assertThat(rowOf(execution.id()).get("OUTPUT_KEY_ID")).isNotNull();

        assertThat(backfill.run(BackfillDirection.OFF, ALL)).isZero();
        assertThat(rowOf(execution.id()).get("OUTPUT_KEY_ID")).isNotNull();

        backfill.run(BackfillDirection.UNSEAL, ALL);

        Map<String, Object> row = rowOf(execution.id());
        assertThat(row.get("OUTPUT_TEXT")).isEqualTo(ANSWER);
        assertThat(row.get("OUTPUT_KEY_ID")).isNull();
        assertThat(outputs.open(executions.findById(execution.id()).orElseThrow())).isEqualTo(ANSWER);

        // 다른 검사가 남긴 줄도 암호문으로 되돌려 둔다
        backfill.run(BackfillDirection.SEAL, ALL);
    }


    private AgentExecution plainExecution(String answer) {
        AgentExecution execution = recorder.start(
            user(owner), conversation.executionConversation(), agent(owner), null, null, 0L);
        recorder.complete(execution, agent(owner), result(), new ModelChoice("anthropic", "example-model-large", null), answer);
        // 키를 넣기 전에 저장한 옛 줄처럼 평문과 빈 key 칸으로 돌린다
        jdbc.update("UPDATE agent_execution SET output_text = ?, output_key_id = NULL WHERE id = ?", answer, execution.id());
        return execution;
    }


    private Map<String, Object> rowOf(Long executionId) {
        return jdbc.queryForMap("SELECT output_text, output_key_id FROM agent_execution WHERE id = ?", executionId);
    }


    private AppUser user() {
        String email = "output-backfill-" + UUID.randomUUID() + "@example.test";
        return users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
    }


    private static CurrentUser user(AppUser owner) {
        return new CurrentUser(owner.id(), owner.email(), "dad", 1L, UserRole.MEMBER);
    }


    private static Agent agent(AppUser owner) {
        return Agent.of(
            "dad",
            "Dad",
            "dad",
            "http://127.0.0.1:1/p/dad",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id());
    }


    private static HermesRunResult result() {
        return HermesRunResult.of(
            "run-1",
            "session-1",
            "completed",
            "끝",
            "example-model-large",
            "anthropic",
            new TokenUsage(120L, 80L, 40L, 160L));
    }
}
