package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.orchestration.application.SubagentRegistrationResult;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 하위 에이전트 session 등록이 부모를 풀어 origin 실행을 정하고, 한 번 적은 줄을 바꾸지 않는 것을 고정한다(ADR-037).
 *
 * <p>번호가 붙은 검사는 리뷰가 요구한 필수 검사의 번호다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubagentSessionRegistrarTest {

    private static final String PROFILE_A = "registrar-a";
    private static final String PROFILE_B = "registrar-b";
    private static final List<String> MY_EMAILS = List.of("registrar-dad@example.com", "registrar-kid@example.com");
    private static final Long CONVERSATION_ID = 1L;

    @Autowired
    SubagentSessionRegistrar registrar;

    @Autowired
    HermesSessionBindingRepository bindings;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser dad;
    private AppUser kid;
    private String root;
    private AgentExecution dadRun;

    /** 같은 H2 를 다른 검사 클래스와 함께 쓰므로 이 검사의 사용자와 그 대화, 이 검사가 쓰는 profile 의 줄만 지운다. */
    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE_A, PROFILE_B));
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(user -> {
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
            users.delete(user);
        }));
        dad = users.save(AppUser.of(MY_EMAILS.get(0), "아빠", 1L, UserRole.MEMBER, Instant.now()));
        kid = users.save(AppUser.of(MY_EMAILS.get(1), "아이", 1L, UserRole.MEMBER, Instant.now()));
        root = newRoot();
        dadRun = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE_A, root);
    }

    @Test
    @DisplayName("루트에서 도는 실행 아래 하위 에이전트는 그 실행을 origin 으로 등록한다")
    void registersRunUnderRootAsOriginOfSubagent() {
        String s1 = newChild();

        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_A, s1);
        assertThat(row.originExecutionId()).as("origin 실행").isEqualTo(dadRun.id());
        assertThat(row.userId()).as("사용자").isEqualTo(dad.id());
        assertThat(row.rootSessionId()).as("루트").isEqualTo(root);
        assertThat(row.parentSessionId()).as("부모").isEqualTo(root);
        assertThat(row.createdAt()).isNotNull();
    }

    @Test // 3
    @DisplayName("origin 실행이 끝난 뒤에도 하위 에이전트의 하위 에이전트는 그 origin 을 잇는다")
    void subagentOfSubagentFollowsOriginEvenAfterOriginRunEnds() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);
        String s2 = newChild();

        assertThat(registrar.register(PROFILE_A, root, s1, s2)).isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_A, s2);
        assertThat(row.originExecutionId()).as("origin 실행").isEqualTo(dadRun.id());
        assertThat(row.userId()).as("사용자").isEqualTo(dad.id());
        assertThat(row.parentSessionId()).as("부모").isEqualTo(s1);
    }

    @Test // 4
    @DisplayName("다른 에이전트에게 맡긴 FOS 실행 안의 하위 에이전트는 그 FOS 실행을 origin 으로 갖는다")
    void subagentInsideFosRunDelegatedToOtherAgentHasThatRunAsOrigin() {
        String delegatedRoot = newRoot();
        AgentExecution delegated =
                McpCallSigner.running(executions, kid.id(), CONVERSATION_ID, PROFILE_B, delegatedRoot);
        jdbc.update(
                "UPDATE agent_execution SET parent_execution_id = ?, root_execution_id = ? WHERE id = ?",
                dadRun.id(),
                dadRun.id(),
                delegated.id());
        String s3 = newChild();

        assertThat(registrar.register(PROFILE_B, delegatedRoot, delegatedRoot, s3))
                .isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_B, s3);
        assertThat(row.originExecutionId()).as("origin 실행").isEqualTo(delegated.id());
        assertThat(row.userId()).as("사용자").isEqualTo(kid.id());
    }

    @Test // 8
    @DisplayName("다른 profile 의 부모 등록이나 도는 실행으로는 등록하지 못한다")
    void cannotRegisterByParentRegistrationOrRunningRunOfOtherProfile() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        String underBinding = newChild();
        String underRoot = newChild();

        assertRejected(PROFILE_B, root, s1, underBinding);
        assertRejected(PROFILE_B, root, root, underRoot);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_B, underBinding))
                .isEmpty();
        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_B, underRoot)).isEmpty();
    }

    @Test // 9
    @DisplayName("같은 네 값으로 두 번 오면 두 번째는 그대로 두고 줄은 하나다")
    void secondArrivalWithSameFourValuesIsLeftAsIsAndOneRow() {
        String s1 = newChild();

        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.CREATED);
        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.EXISTS);

        assertThat(rowCount(PROFILE_A, s1)).isEqualTo(1);
    }

    @Test // 9
    @DisplayName("같은 네 값이 동시에 와도 줄은 하나이고 나머지는 그대로 둔다")
    void concurrentSameFourValuesLeaveOneRowAndRestAsIs() throws Exception {
        String s1 = newChild();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubagentRegistrationResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return registrar.register(PROFILE_A, root, root, s1);
                }));
            }
            start.countDown();
            List<SubagentRegistrationResult> results = new ArrayList<>();
            for (Future<SubagentRegistrationResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results)
                    .as("결과 %s", results)
                    .containsOnlyOnce(SubagentRegistrationResult.CREATED)
                    .filteredOn(result -> result == SubagentRegistrationResult.EXISTS)
                    .hasSize(threads - 1);
        }
        assertThat(rowCount(PROFILE_A, s1)).isEqualTo(1);
        assertThat(binding(PROFILE_A, s1).originExecutionId()).isEqualTo(dadRun.id());
    }

    @Test // 10
    @DisplayName("이미 등록한 session 이 다른 origin 으로 오면 거절하고 덮어쓰지 않는다")
    void rejectsWithoutOverwritingWhenRegisteredSessionComesWithOtherOrigin() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        String otherRoot = newRoot();
        AgentExecution otherRun = McpCallSigner.running(executions, kid.id(), CONVERSATION_ID, PROFILE_A, otherRoot);

        assertThatThrownBy(() -> registrar.register(PROFILE_A, otherRoot, otherRoot, s1))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_CONFLICT));

        HermesSessionBinding row = binding(PROFILE_A, s1);
        assertThat(row.originExecutionId())
                .as("origin 은 %d 그대로", dadRun.id())
                .isEqualTo(dadRun.id())
                .isNotEqualTo(otherRun.id());
        assertThat(row.userId()).isEqualTo(dad.id());
    }

    @Test
    @DisplayName("최상위 부모 실행이 끝난 뒤 같은 네 값의 재전송은 그대로 두고 다른 부모로 오면 거절한다")
    void resendAfterTopParentRunEndsIsLeftAsIsAndOtherParentIsRejected() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.EXISTS);
        assertRejected(PROFILE_A, root, newRoot(), s1);

        HermesSessionBinding row = binding(PROFILE_A, s1);
        assertThat(row.originExecutionId()).as("origin 실행").isEqualTo(dadRun.id());
        assertThat(row.parentSessionId()).as("부모는 처음 값 그대로").isEqualTo(root);
        assertThat(rowCount(PROFILE_A, s1)).isEqualTo(1);
    }

    @Test
    @DisplayName("대화가 보낼 session 이나 루트 session 으로 쓰는 값은 하위 에이전트로 등록하지 못한다")
    void cannotRegisterValueUsedAsConversationSendOrRootSessionAsSubagent() {
        String compacted = newRoot();
        String conversationRoot = newRoot();
        Conversation conversation = Conversation.startedBy(dad.id(), "압축된 대화", null, Instant.now());
        conversation.adoptSessions(compacted, conversationRoot);
        conversations.save(conversation);

        assertRejected(PROFILE_A, root, root, compacted);
        assertRejected(PROFILE_A, root, root, conversationRoot);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, compacted)).isEmpty();
        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, conversationRoot))
                .isEmpty();
    }

    @Test
    @DisplayName("압축 교체된 최상위 session 이 만든 하위 에이전트는 루트에서 도는 실행으로 풀린다")
    void subagentOfCompactedTopSessionResolvesToRunRunningOnRoot() {
        String compacted = newRoot();
        String child = newChild();

        assertThat(registrar.register(PROFILE_A, root, compacted, child)).isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_A, child);
        assertThat(row.originExecutionId()).isEqualTo(dadRun.id());
        assertThat(row.parentSessionId()).isEqualTo(compacted);
    }

    @Test
    @DisplayName("최상위 session 은 하위 에이전트로 등록하지 못한다")
    void cannotRegisterTopSessionAsSubagent() {
        AgentExecution earlierTurn = McpCallSigner.save(
                executions, dad.id(), CONVERSATION_ID, PROFILE_A, newRoot(), ExecutionStatus.SUCCEEDED);

        assertRejected(PROFILE_A, root, root, root);
        assertRejected(PROFILE_A, root, root, earlierTurn.hermesSessionId());

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, root)).isEmpty();
        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, earlierTurn.hermesSessionId()))
                .isEmpty();
    }

    @Test
    @DisplayName("부모 등록이 없고 루트에서 도는 실행도 없으면 거절한다")
    void rejectsWhenNoParentRegistrationAndNoRunRunningOnRoot() {
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);
        String child = newChild();

        assertRejected(PROFILE_A, root, root, child);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, child)).isEmpty();
    }

    @Test
    @DisplayName("부모 등록의 루트가 서명한 루트와 다르면 거절한다")
    void rejectsWhenParentRegistrationRootDiffersFromSignedRoot() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        String otherRoot = newRoot();
        McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE_A, otherRoot);
        String child = newChild();

        assertRejected(PROFILE_A, otherRoot, s1, child);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, child)).isEmpty();
    }

    @Test
    @DisplayName("부모 등록의 origin 실행 줄이 없으면 거절한다")
    void rejectsWhenParentRegistrationOriginRunRowIsMissing() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", dadRun.id());
        String child = newChild();

        assertRejected(PROFILE_A, root, s1, child);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, child)).isEmpty();
    }

    @Test
    @DisplayName("비었거나 칸 길이를 넘는 값은 거절하고 칸 길이까지는 받는다")
    void rejectsBlankOrOverColumnLengthAndAcceptsUpToColumnLength() {
        String longest = "하".repeat(128);
        String tooLong = "하".repeat(129);

        assertRejected(null, root, root, newChild());
        assertRejected(" ", root, root, newChild());
        assertRejected(PROFILE_A, null, root, newChild());
        assertRejected(PROFILE_A, root, "", newChild());
        assertRejected(PROFILE_A, root, root, null);
        assertRejected(PROFILE_A, root, root, tooLong);

        assertThat(registrar.register(PROFILE_A, root, root, longest)).isEqualTo(SubagentRegistrationResult.CREATED);
        assertThat(binding(PROFILE_A, longest).sessionId()).isEqualTo(longest);
    }

    private HermesSessionBinding binding(String profileName, String sessionId) {
        return bindings.findByProfileNameAndSessionId(profileName, sessionId)
                .orElseThrow(() -> new AssertionError("등록 줄이 없다 profile=" + profileName));
    }

    private long rowCount(String profileName, String sessionId) {
        return bindings.findAll().stream()
                .filter(row ->
                        row.profileName().equals(profileName) && row.sessionId().equals(sessionId))
                .count();
    }

    private void setStatus(AgentExecution execution, ExecutionStatus status) {
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", status.name(), execution.id());
    }

    private void assertRejected(String profileName, String parentRoot, String parent, String child) {
        assertThatThrownBy(() -> registrar.register(profileName, parentRoot, parent, child))
                .as("profile=%s", profileName)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_REJECTED));
    }

    private static String newRoot() {
        return "fos-" + UUID.randomUUID();
    }

    private static String newChild() {
        return "하위-" + UUID.randomUUID();
    }
}
