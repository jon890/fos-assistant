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
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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

    @Autowired SubagentSessionRegistrar registrar;
    @Autowired HermesSessionBindingRepository bindings;
    @Autowired AgentExecutionRepository executions;
    @Autowired AppUserRepository users;
    @Autowired ConversationRepository conversations;
    @Autowired JdbcTemplate jdbc;

    private AppUser dad;
    private AppUser kid;
    private String root;
    private AgentExecution dadRun;

    /** 같은 H2 를 다른 검사 클래스와 함께 쓰므로 이 검사의 사용자와 그 대화, 이 검사가 쓰는 profile 의 줄만 지운다. */
    @BeforeEach
    void 준비한다() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE_A, PROFILE_B));
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(user -> {
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
            users.delete(user);
        }));
        dad = users.save(AppUser.of(MY_EMAILS.get(0), "아빠", 1L, UserRole.MEMBER));
        kid = users.save(AppUser.of(MY_EMAILS.get(1), "아이", 1L, UserRole.MEMBER));
        root = newRoot();
        dadRun = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE_A, root);
    }

    @Test
    void 뿌리에서_도는_실행_아래_하위_에이전트는_그_실행을_origin_으로_등록한다() {
        String s1 = newChild();

        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_A, s1);
        assertThat(row.originExecutionId()).as("origin 실행").isEqualTo(dadRun.id());
        assertThat(row.userId()).as("사용자").isEqualTo(dad.id());
        assertThat(row.rootSessionId()).as("뿌리").isEqualTo(root);
        assertThat(row.parentSessionId()).as("부모").isEqualTo(root);
        assertThat(row.createdAt()).isNotNull();
    }

    @Test // 3
    void origin_실행이_끝난_뒤에도_하위_에이전트의_하위_에이전트는_그_origin_을_잇는다() {
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
    void 다른_에이전트에게_맡긴_FOS_실행_안의_하위_에이전트는_그_FOS_실행을_origin_으로_갖는다() {
        String delegatedRoot = newRoot();
        AgentExecution delegated = McpCallSigner.running(executions, kid.id(), CONVERSATION_ID, PROFILE_B, delegatedRoot);
        jdbc.update("UPDATE agent_execution SET parent_execution_id = ?, root_execution_id = ? WHERE id = ?",
                dadRun.id(), dadRun.id(), delegated.id());
        String s3 = newChild();

        assertThat(registrar.register(PROFILE_B, delegatedRoot, delegatedRoot, s3))
                .isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_B, s3);
        assertThat(row.originExecutionId()).as("origin 실행").isEqualTo(delegated.id());
        assertThat(row.userId()).as("사용자").isEqualTo(kid.id());
    }

    @Test // 8
    void 다른_profile_의_부모_등록이나_도는_실행으로는_등록하지_못한다() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        String underBinding = newChild();
        String underRoot = newChild();

        assertRejected(PROFILE_B, root, s1, underBinding);
        assertRejected(PROFILE_B, root, root, underRoot);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_B, underBinding)).isEmpty();
        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_B, underRoot)).isEmpty();
    }

    @Test // 9
    void 같은_네_값으로_두_번_오면_두_번째는_그대로_두고_줄은_하나다() {
        String s1 = newChild();

        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.CREATED);
        assertThat(registrar.register(PROFILE_A, root, root, s1)).isEqualTo(SubagentRegistrationResult.EXISTS);

        assertThat(rowCount(PROFILE_A, s1)).isEqualTo(1);
    }

    @Test // 9
    void 같은_네_값이_동시에_와도_줄은_하나이고_나머지는_그대로_둔다() throws Exception {
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

            assertThat(results).as("결과 %s", results)
                    .containsOnlyOnce(SubagentRegistrationResult.CREATED)
                    .filteredOn(result -> result == SubagentRegistrationResult.EXISTS)
                    .hasSize(threads - 1);
        }
        assertThat(rowCount(PROFILE_A, s1)).isEqualTo(1);
        assertThat(binding(PROFILE_A, s1).originExecutionId()).isEqualTo(dadRun.id());
    }

    @Test // 10
    void 이미_등록한_session_이_다른_origin_으로_오면_거절하고_덮어쓰지_않는다() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        String otherRoot = newRoot();
        AgentExecution otherRun = McpCallSigner.running(executions, kid.id(), CONVERSATION_ID, PROFILE_A, otherRoot);

        assertThatThrownBy(() -> registrar.register(PROFILE_A, otherRoot, otherRoot, s1))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_CONFLICT));

        HermesSessionBinding row = binding(PROFILE_A, s1);
        assertThat(row.originExecutionId()).as("origin 은 %d 그대로", dadRun.id()).isEqualTo(dadRun.id())
                .isNotEqualTo(otherRun.id());
        assertThat(row.userId()).isEqualTo(dad.id());
    }

    @Test
    void 최상위_부모_실행이_끝난_뒤_같은_네_값의_재전송은_그대로_두고_다른_부모로_오면_거절한다() {
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
    void 대화가_보낼_session_이나_뿌리_session_으로_쓰는_값은_하위_에이전트로_등록하지_못한다() {
        String compacted = newRoot();
        String conversationRoot = newRoot();
        Conversation conversation = Conversation.startedBy(dad.id(), "압축된 대화", null);
        conversation.adoptSessions(compacted, conversationRoot);
        conversations.save(conversation);

        assertRejected(PROFILE_A, root, root, compacted);
        assertRejected(PROFILE_A, root, root, conversationRoot);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, compacted)).isEmpty();
        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, conversationRoot)).isEmpty();
    }

    @Test
    void 압축_교체된_최상위_session_이_만든_하위_에이전트는_뿌리에서_도는_실행으로_풀린다() {
        String compacted = newRoot();
        String child = newChild();

        assertThat(registrar.register(PROFILE_A, root, compacted, child)).isEqualTo(SubagentRegistrationResult.CREATED);

        HermesSessionBinding row = binding(PROFILE_A, child);
        assertThat(row.originExecutionId()).isEqualTo(dadRun.id());
        assertThat(row.parentSessionId()).isEqualTo(compacted);
    }

    @Test
    void 최상위_session_은_하위_에이전트로_등록하지_못한다() {
        AgentExecution earlierTurn =
                McpCallSigner.save(executions, dad.id(), CONVERSATION_ID, PROFILE_A, newRoot(), ExecutionStatus.SUCCEEDED);

        assertRejected(PROFILE_A, root, root, root);
        assertRejected(PROFILE_A, root, root, earlierTurn.hermesSessionId());

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, root)).isEmpty();
        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, earlierTurn.hermesSessionId())).isEmpty();
    }

    @Test
    void 부모_등록이_없고_뿌리에서_도는_실행도_없으면_거절한다() {
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);
        String child = newChild();

        assertRejected(PROFILE_A, root, root, child);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, child)).isEmpty();
    }

    @Test
    void 부모_등록의_뿌리가_서명한_뿌리와_다르면_거절한다() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        String otherRoot = newRoot();
        McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE_A, otherRoot);
        String child = newChild();

        assertRejected(PROFILE_A, otherRoot, s1, child);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, child)).isEmpty();
    }

    @Test
    void 부모_등록의_origin_실행_줄이_없으면_거절한다() {
        String s1 = newChild();
        registrar.register(PROFILE_A, root, root, s1);
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", dadRun.id());
        String child = newChild();

        assertRejected(PROFILE_A, root, s1, child);

        assertThat(bindings.findByProfileNameAndSessionId(PROFILE_A, child)).isEmpty();
    }

    @Test
    void 비었거나_칸_길이를_넘는_값은_거절하고_칸_길이까지는_받는다() {
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
                .filter(row -> row.profileName().equals(profileName) && row.sessionId().equals(sessionId))
                .count();
    }

    private void setStatus(AgentExecution execution, ExecutionStatus status) {
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", status.name(), execution.id());
    }

    private void assertRejected(String profileName, String parentRoot, String parent, String child) {
        assertThatThrownBy(() -> registrar.register(profileName, parentRoot, parent, child))
                .as("profile=%s", profileName)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_REJECTED));
    }

    private static String newRoot() {
        return "fos-" + UUID.randomUUID();
    }

    private static String newChild() {
        return "하위-" + UUID.randomUUID();
    }
}
