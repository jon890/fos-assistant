package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 위임 답 본문이 저장할 때 암호화되고, 옮긴 암호문은 풀리지 않는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
class ExecutionOutputEncryptionTest {

    private static final String ANSWER = "평문-표식-9912 지난달과 같아요";

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

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppUser owner;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        owner = user();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "월급 확인", null, Instant.now()));
    }


    @AfterEach
    void tearDown() {
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("답을 적으면 데이터베이스에는 암호문이 남고 열면 평문이다")
    void storesCiphertextAndOpensPlaintext() {
        AgentExecution execution = finishedWith(ANSWER);

        Map<String, Object> row = rowOf(execution.id());
        assertThat((String)row.get("OUTPUT_TEXT")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("OUTPUT_KEY_ID")).isNotNull();
        AgentExecution reloaded = executions.findById(execution.id()).orElseThrow();
        assertThat(reloaded.outputText()).startsWith("v1.");
        assertThat(outputs.open(reloaded)).isEqualTo(ANSWER);
    }


    @Test
    @DisplayName("key 칸이 비어 있는 옛 줄은 평문으로 읽힌다")
    void legacyRowWithoutKeyReadsAsPlaintext() {
        AgentExecution execution = finishedWith(ANSWER);
        jdbc.update("UPDATE agent_execution SET output_text = ?, output_key_id = NULL WHERE id = ?", "옛 평문 답", execution.id());

        assertThat(outputs.open(executions.findById(execution.id()).orElseThrow())).isEqualTo("옛 평문 답");
    }


    @Test
    @DisplayName("답이 없는 실행은 열어도 null 이다")
    void rowWithoutOutputOpensToNull() {
        AgentExecution execution = recorder.start(
            user(owner), conversation.executionConversation(), agent(owner), null, null, 0L);

        assertThat(outputs.open(executions.findById(execution.id()).orElseThrow())).isNull();
    }


    @Test
    @DisplayName("다른 줄로 옮긴 암호문은 읽을 수 없는 답으로 보이고 null 이 아니다")
    void movedCiphertextIsUnreadable() {
        AgentExecution first = finishedWith(ANSWER);
        AgentExecution second = finishedWith("평문-표식-9913 다른 답");
        Map<String, Object> firstRow = rowOf(first.id());
        jdbc.update("UPDATE agent_execution SET output_text = ?, output_key_id = ? WHERE id = ?",
            firstRow.get("OUTPUT_TEXT"), firstRow.get("OUTPUT_KEY_ID"), second.id());
        dataKeys.forgetCachedKeys();

        assertThat(outputs.open(executions.findById(second.id()).orElseThrow()))
            .isEqualTo(ExecutionOutputs.UNREADABLE_OUTPUT);
        assertThat(outputs.open(executions.findById(first.id()).orElseThrow())).isEqualTo(ANSWER);
    }


    @Test
    @DisplayName("대화 정리가 답과 key 칸을 모두 비운다")
    void clearOutputsOfNullsBothColumns() {
        AgentExecution execution = finishedWith(ANSWER);

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> executions.clearOutputsOf(conversation.id()));

        Map<String, Object> row = rowOf(execution.id());
        assertThat(row.get("OUTPUT_TEXT")).isNull();
        assertThat(row.get("OUTPUT_KEY_ID")).isNull();
    }


    private AgentExecution finishedWith(String answer) {
        AgentExecution execution = recorder.start(
            user(owner), conversation.executionConversation(), agent(owner), null, null, 0L);
        recorder.complete(execution, agent(owner), result(), new ModelChoice("anthropic", "example-model-large", null), answer);
        return execution;
    }


    private Map<String, Object> rowOf(Long executionId) {
        return jdbc.queryForMap("SELECT output_text, output_key_id FROM agent_execution WHERE id = ?", executionId);
    }


    private AppUser user() {
        String email = "output-" + UUID.randomUUID() + "@example.test";
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
