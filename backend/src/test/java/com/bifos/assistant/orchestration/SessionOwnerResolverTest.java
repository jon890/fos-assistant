package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * MCP 호출의 origin 실행을 등록으로 먼저 정하고, 등록이 없으면 최상위 session 일 때만 도는 실행을 찾는 것을
 * 고정한다(ADR-037 「판정 순서」).
 *
 * <p>번호가 붙은 검사는 리뷰가 요구한 필수 검사의 번호다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionOwnerResolverTest {

    private static final String PROFILE = "session-owner";
    private static final String OTHER_PROFILE = "session-owner-other";
    private static final List<String> MY_EMAILS =
            List.of("session-owner-dad@example.com", "session-owner-kid@example.com");
    private static final Long CONVERSATION_ID = 1L;

    @Autowired
    SessionOwnerResolver owners;

    @Autowired
    SubagentSessionRegistrar registrar;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser dad;
    private AppUser kid;
    private String root;
    private AgentExecution dadRun;

    /** 같은 H2 를 다른 검사 클래스와 함께 쓰므로 이 검사의 사용자와 이 검사가 쓰는 profile 의 줄만 지운다. */
    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE, OTHER_PROFILE));
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(users::delete));
        dad = users.save(AppUser.of(MY_EMAILS.get(0), "아빠", 1L, UserRole.MEMBER, Instant.now()));
        kid = users.save(AppUser.of(MY_EMAILS.get(1), "아이", 1L, UserRole.MEMBER, Instant.now()));
        root = newRoot();
        dadRun = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, root);
    }

    @Test // 1
    @DisplayName("등록한 하위 에이전트는 origin 실행이 끝난 뒤에도 그 실행으로 정한다")
    void registeredSubagentIsDecidedByOriginRunEvenAfterItEnds() {
        String s1 = register(root, root);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
    }

    @Test // 3
    @DisplayName("하위 에이전트의 하위 에이전트도 끝난 origin 실행으로 정한다")
    void subagentOfSubagentIsDecidedByEndedOriginRunToo() {
        String s1 = register(root, root);
        String s2 = register(root, s1);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertOrigin(owners.resolve(PROFILE, root, s2), dadRun, dad);
    }

    @Test // 5
    @DisplayName("같은 루트로 다음 turn 이 돌아도 등록한 session 은 처음 origin 에 남는다")
    void registeredSessionStaysWithFirstOriginEvenIfNextTurnRunsOnSameRoot() {
        String s1 = register(root, root);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);
        AgentExecution nextTurn = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, root);

        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
        assertOrigin(owners.resolve(PROFILE, root, root), nextTurn, dad);
    }

    @Test // 6
    @DisplayName("같은 profile 을 두 사용자가 써도 부모가 모두 끝난 뒤 각자의 origin 으로 정한다")
    void twoUsersOnSameProfileAreDecidedByOwnOriginAfterParentsEnd() {
        String rootA = root;
        String rootB = newRoot();
        AgentExecution kidRun = McpCallSigner.running(executions, kid.id(), CONVERSATION_ID, PROFILE, rootB);
        String sa = register(rootA, rootA);
        String sb = register(rootB, rootB);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);
        setStatus(kidRun, ExecutionStatus.SUCCEEDED);

        assertOrigin(owners.resolve(PROFILE, rootA, sa), dadRun, dad);
        assertOrigin(owners.resolve(PROFILE, rootB, sb), kidRun, kid);
    }

    @Test // 7
    @DisplayName("앞 프로세스가 적은 등록 줄만으로 실패한 origin 실행을 정한다")
    void decidesFailedOriginRunFromRegistrationRowWrittenByPreviousProcess() {
        setStatus(dadRun, ExecutionStatus.FAILED);
        jdbc.update("UPDATE agent_execution SET error_code = ? WHERE id = ?", "ORPHANED", dadRun.id());
        String s1 = newChild();
        jdbc.update(
                "INSERT INTO hermes_session_binding "
                        + "(profile_name, session_id, user_id, origin_execution_id, root_session_id, parent_session_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                PROFILE,
                s1,
                dad.id(),
                dadRun.id(),
                root,
                root,
                Timestamp.from(Instant.now()));

        AgentExecution origin = owners.resolve(PROFILE, root, s1);

        assertOrigin(origin, dadRun, dad);
        assertThat(origin.status()).as("origin 실행의 상태").isEqualTo(ExecutionStatus.FAILED);
        assertThat(origin.errorCode()).as("origin 실행의 오류 코드").isEqualTo("ORPHANED");
    }

    @Test
    @DisplayName("등록한 하위 에이전트는 origin 실행이 취소되면 거절한다")
    void rejectsRegisteredSubagentWhenOriginRunIsCancelled() {
        String s1 = register(root, root);
        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);

        setStatus(dadRun, ExecutionStatus.CANCELLED);

        assertRejected(PROFILE, root, s1);
    }

    @Test
    @DisplayName("하위 에이전트의 하위 에이전트도 origin 실행이 취소되면 거절한다")
    void rejectsSubagentOfSubagentWhenOriginRunIsCancelled() {
        String s1 = register(root, root);
        String s2 = register(root, s1);
        setStatus(dadRun, ExecutionStatus.CANCELLED);

        assertRejected(PROFILE, root, s1);
        assertRejected(PROFILE, root, s2);
    }

    @Test
    @DisplayName("흐름을 중지하면 이미 끝난 자식 실행에서 만든 하위 에이전트도 거절한다")
    void stoppingFlowRejectsSubagentMadeInAlreadyEndedChildRun() {
        // 흐름의 자식 실행은 제 session 으로 돌아 그 안의 하위 에이전트는 자식 실행을 origin 으로 갖는다.
        String childRoot = newRoot();
        AgentExecution researched = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, childRoot);
        jdbc.update(
                "UPDATE agent_execution SET parent_execution_id = ?, root_execution_id = ? WHERE id = ?",
                dadRun.id(),
                dadRun.id(),
                researched.id());
        String s1 = register(childRoot, childRoot);
        setStatus(researched, ExecutionStatus.SUCCEEDED);
        assertOrigin(owners.resolve(PROFILE, childRoot, s1), researched, dad);

        setStatus(dadRun, ExecutionStatus.CANCELLED);

        assertRejected(PROFILE, childRoot, s1);
    }

    @Test
    @DisplayName("루트가 끝났어도 취소가 아니면 자식 실행의 하위 에이전트는 그대로 정한다")
    void decidesChildRunSubagentAsIsWhenRootEndedButNotCancelled() {
        String childRoot = newRoot();
        AgentExecution researched = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, childRoot);
        jdbc.update(
                "UPDATE agent_execution SET parent_execution_id = ?, root_execution_id = ? WHERE id = ?",
                dadRun.id(),
                dadRun.id(),
                researched.id());
        String s1 = register(childRoot, childRoot);
        setStatus(researched, ExecutionStatus.SUCCEEDED);
        setStatus(dadRun, ExecutionStatus.FAILED);

        assertOrigin(owners.resolve(PROFILE, childRoot, s1), researched, dad);
    }

    @Test
    @DisplayName("origin 실행이 실패로 끝나도 등록한 하위 에이전트는 그대로 정한다")
    void decidesRegisteredSubagentAsIsWhenOriginRunEndedInFailure() {
        String s1 = register(root, root);
        setStatus(dadRun, ExecutionStatus.FAILED);

        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
    }

    @Test // 8
    @DisplayName("다른 profile 의 등록으로는 정하지 못한다")
    void cannotDecideByRegistrationOfOtherProfile() {
        String s1 = register(root, root);

        assertRejected(OTHER_PROFILE, root, s1);
    }

    @Test // 11
    @DisplayName("등록이 없는 하위 session 은 루트에서 실행이 돌아도 거절한다")
    void rejectsSubSessionWithoutRegistrationEvenIfRunRunsOnRoot() {
        String s9 = newChild();

        assertRejected(PROFILE, root, s9);
    }

    @Test
    @DisplayName("등록이 없고 session 이 루트와 같으면 도는 실행을 찾고 없으면 거절한다")
    void findsRunningRunWhenNoRegistrationAndSessionEqualsRootElseRejects() {
        assertOrigin(owners.resolve(PROFILE, root, root), dadRun, dad);

        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertRejected(PROFILE, root, root);
    }

    @Test
    @DisplayName("압축 교체된 최상위 session 의 호출은 거절하고 그 session 이 만든 하위 에이전트는 origin 으로 정한다")
    void rejectsCallOfCompactedTopSessionAndDecidesItsSubagentByOrigin() {
        String compacted = newRoot();
        String s1 = register(root, compacted);

        assertRejected(PROFILE, root, compacted);
        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
    }

    @Test
    @DisplayName("등록의 루트와 서명한 루트가 다르면 거절한다")
    void rejectsWhenRegistrationRootDiffersFromSignedRoot() {
        String s1 = register(root, root);
        String otherRoot = newRoot();
        McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, otherRoot);

        assertRejected(PROFILE, otherRoot, s1);
    }

    @Test
    @DisplayName("등록의 origin 실행 줄이 없으면 거절한다")
    void rejectsWhenRegistrationOriginRunRowIsMissing() {
        String s1 = register(root, root);
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", dadRun.id());

        assertRejected(PROFILE, root, s1);
    }

    @Test
    @DisplayName("셋 가운데 하나라도 비었으면 거절한다")
    void rejectsWhenAnyOfThreeIsBlank() {
        String s1 = register(root, root);

        assertRejected(null, root, s1);
        assertRejected(" ", root, s1);
        assertRejected(PROFILE, null, s1);
        assertRejected(PROFILE, "", s1);
        assertRejected(PROFILE, root, null);
        assertRejected(PROFILE, root, " ");
    }

    /** 새 하위 에이전트 session 을 등록하고 그 session 을 돌려준다. */
    private String register(String parentRoot, String parent) {
        String child = newChild();
        registrar.register(PROFILE, parentRoot, parent, child);
        return child;
    }

    private void setStatus(AgentExecution execution, ExecutionStatus status) {
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", status.name(), execution.id());
    }

    private static void assertOrigin(AgentExecution actual, AgentExecution expected, AppUser user) {
        assertThat(actual.id()).as("origin 실행 번호").isEqualTo(expected.id());
        assertThat(actual.userId()).as("origin 실행의 사용자").isEqualTo(user.id());
    }

    private void assertRejected(String profileName, String rootSessionId, String sessionId) {
        assertThatThrownBy(() -> owners.resolve(profileName, rootSessionId, sessionId))
                .as("profile=%s", profileName)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
    }

    private static String newRoot() {
        return "fos-" + UUID.randomUUID();
    }

    private static String newChild() {
        return "하위-" + UUID.randomUUID();
    }
}
