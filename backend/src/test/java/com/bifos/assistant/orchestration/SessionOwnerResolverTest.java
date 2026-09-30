package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
    private static final List<String> MY_EMAILS = List.of("session-owner-dad@example.com", "session-owner-kid@example.com");
    private static final Long CONVERSATION_ID = 1L;

    @Autowired SessionOwnerResolver owners;
    @Autowired SubagentSessionRegistrar registrar;
    @Autowired AgentExecutionRepository executions;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    private AppUser dad;
    private AppUser kid;
    private String root;
    private AgentExecution dadRun;

    /** 같은 H2 를 다른 검사 클래스와 함께 쓰므로 이 검사의 사용자와 이 검사가 쓰는 profile 의 줄만 지운다. */
    @BeforeEach
    void 준비한다() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE, OTHER_PROFILE));
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(users::delete));
        dad = users.save(AppUser.of(MY_EMAILS.get(0), "아빠", 1L, UserRole.MEMBER));
        kid = users.save(AppUser.of(MY_EMAILS.get(1), "아이", 1L, UserRole.MEMBER));
        root = newRoot();
        dadRun = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, root);
    }

    @Test // 1
    void 등록한_하위_에이전트는_origin_실행이_끝난_뒤에도_그_실행으로_정한다() {
        String s1 = register(root, root);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
    }

    @Test // 3
    void 하위_에이전트의_하위_에이전트도_끝난_origin_실행으로_정한다() {
        String s1 = register(root, root);
        String s2 = register(root, s1);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertOrigin(owners.resolve(PROFILE, root, s2), dadRun, dad);
    }

    @Test // 5
    void 같은_뿌리로_다음_turn_이_돌아도_등록한_session_은_처음_origin_에_남는다() {
        String s1 = register(root, root);
        setStatus(dadRun, ExecutionStatus.SUCCEEDED);
        AgentExecution nextTurn = McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, root);

        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
        assertOrigin(owners.resolve(PROFILE, root, root), nextTurn, dad);
    }

    @Test // 6
    void 같은_profile_을_두_사용자가_써도_부모가_모두_끝난_뒤_각자의_origin_으로_정한다() {
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
    void 앞_프로세스가_적은_등록_줄만으로_실패한_origin_실행을_정한다() {
        setStatus(dadRun, ExecutionStatus.FAILED);
        jdbc.update("UPDATE agent_execution SET error_code = ? WHERE id = ?", "ORPHANED", dadRun.id());
        String s1 = newChild();
        jdbc.update("INSERT INTO hermes_session_binding "
                        + "(profile_name, session_id, user_id, origin_execution_id, root_session_id, parent_session_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                PROFILE, s1, dad.id(), dadRun.id(), root, root, Timestamp.from(Instant.now()));

        AgentExecution origin = owners.resolve(PROFILE, root, s1);

        assertOrigin(origin, dadRun, dad);
        assertThat(origin.status()).as("origin 실행의 상태").isEqualTo(ExecutionStatus.FAILED);
        assertThat(origin.errorCode()).as("origin 실행의 오류 코드").isEqualTo("ORPHANED");
    }

    @Test
    void 등록한_하위_에이전트는_origin_실행이_취소되면_거절한다() {
        String s1 = register(root, root);
        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);

        setStatus(dadRun, ExecutionStatus.CANCELLED);

        assertRejected(PROFILE, root, s1);
    }

    @Test
    void 하위_에이전트의_하위_에이전트도_origin_실행이_취소되면_거절한다() {
        String s1 = register(root, root);
        String s2 = register(root, s1);
        setStatus(dadRun, ExecutionStatus.CANCELLED);

        assertRejected(PROFILE, root, s1);
        assertRejected(PROFILE, root, s2);
    }

    @Test
    void origin_실행이_실패로_끝나도_등록한_하위_에이전트는_그대로_정한다() {
        String s1 = register(root, root);
        setStatus(dadRun, ExecutionStatus.FAILED);

        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
    }

    @Test // 8
    void 다른_profile_의_등록으로는_정하지_못한다() {
        String s1 = register(root, root);

        assertRejected(OTHER_PROFILE, root, s1);
    }

    @Test // 11
    void 등록이_없는_하위_session_은_뿌리에서_실행이_돌아도_거절한다() {
        String s9 = newChild();

        assertRejected(PROFILE, root, s9);
    }

    @Test
    void 등록이_없고_session_이_뿌리와_같으면_도는_실행을_찾고_없으면_거절한다() {
        assertOrigin(owners.resolve(PROFILE, root, root), dadRun, dad);

        setStatus(dadRun, ExecutionStatus.SUCCEEDED);

        assertRejected(PROFILE, root, root);
    }

    @Test
    void 압축_교체된_최상위_session_의_호출은_거절하고_그_session_이_만든_하위_에이전트는_origin_으로_정한다() {
        String compacted = newRoot();
        String s1 = register(root, compacted);

        assertRejected(PROFILE, root, compacted);
        assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad);
    }

    @Test
    void 등록의_뿌리와_서명한_뿌리가_다르면_거절한다() {
        String s1 = register(root, root);
        String otherRoot = newRoot();
        McpCallSigner.running(executions, dad.id(), CONVERSATION_ID, PROFILE, otherRoot);

        assertRejected(PROFILE, otherRoot, s1);
    }

    @Test
    void 등록의_origin_실행_줄이_없으면_거절한다() {
        String s1 = register(root, root);
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", dadRun.id());

        assertRejected(PROFILE, root, s1);
    }

    @Test
    void 셋_가운데_하나라도_비었으면_거절한다() {
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
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
    }

    private static String newRoot() {
        return "fos-" + UUID.randomUUID();
    }

    private static String newChild() {
        return "하위-" + UUID.randomUUID();
    }
}
