package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentPendingToolsets;
import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 켜지 못한 기본 도구를 Control Plane 이 요청 밖에서 다시 켜는지, 관리자가 끈 뒤에는 다시 켜지 않는지 본다(스펙 generated-image
 * REQ11, REQ13).
 */
@BackendIntegrationTest
class AgentPendingToolsetsTest {

    private static final String PROFILE = "pending-tools";
    private static final long GROUP = 7_301L;

    @Autowired
    AgentPendingToolsets pending;

    @Autowired
    AgentToolService tools;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser owner;
    private AppUser admin;
    private Agent agent;


    @BeforeEach
    void setUp() {
        reset(toolsets);
        jdbc.update("DELETE FROM notification WHERE user_id IN (SELECT id FROM app_user WHERE group_id = ?)", GROUP);
        jdbc.update("DELETE FROM agent WHERE hermes_profile = ?", PROFILE);
        jdbc.update("DELETE FROM app_user WHERE group_id = ?", GROUP);
        // 다른 검사가 남긴 기다리는 줄이 이 검사의 차례에 섞이지 않게 한다.
        jdbc.update("UPDATE agent SET pending_default_toolsets = NULL");
        owner = users.save(AppUser.of("pending-owner@example.com", "지윤", GROUP, UserRole.MEMBER));
        admin = users.save(AppUser.of("pending-admin@example.com", "관리자", GROUP, UserRole.ADMIN));
        agent = agents.save(Agent.of(
            PROFILE,
            "비서",
            PROFILE,
            "http://listener.test/p/" + PROFILE,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
        pending.mark(agent.id(), List.of("image_gen"));
    }


    @Test
    @DisplayName("실행 공간이 준비된 기본 비서는 지금 도구에 「그림 만들기」 를 더해 켜고 더 기다리지 않는다")
    void enablesPendingToolsetInSandboxAndStopsWaiting() {
        when(toolsets.readEnabled(anyString(), eq(PROFILE)))
            .thenReturn(List.of("web", "fos-attachments"))
            .thenReturn(List.of("web", "fos-attachments", "image_gen"));

        pending.attemptAll();

        verify(toolsets)
            .writeApiServerInSandbox(
                PROFILE, List.of("web", "image_gen", "fos-assistant", "fos-attachments"), "u" + owner.id());
        assertThat(waiting()).isEmpty();
        assertThat(notices()).isEmpty();
    }


    @Test
    @DisplayName("실행 공간이 없으면 관리자에게 사용자와 까닭을 한 번 알리고, 준비된 뒤의 차례에 스스로 켠다")
    void noticesAdminOnceAndEnablesAfterSandboxIsReady() {
        when(toolsets.readEnabled(anyString(), eq(PROFILE))).thenReturn(List.of("web"));
        doThrow(new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "no sandbox"))
            .when(toolsets)
            .writeApiServerInSandbox(anyString(), anyList(), anyString());

        pending.attemptAll();
        pending.attemptAll();

        assertThat(waiting()).containsExactly("image_gen");
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
        assertThat(notices()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(NotificationKind.DEFAULT_TOOLS_PENDING);
            assertThat(notice.body()).contains("지윤", "그림 만들기", "실행 공간");
            assertThat(notice.unread()).isTrue();
        });
        assertThat(notifications.countByUserIdAndReadAtIsNull(owner.id())).as("주인에게는 알리지 않는다").isZero();

        reset(toolsets);
        when(toolsets.readEnabled(anyString(), eq(PROFILE)))
            .thenReturn(List.of("web"))
            .thenReturn(List.of("web", "image_gen"));
        pending.attemptAll();

        assertThat(waiting()).isEmpty();
        assertThat(notices()).singleElement().satisfies(notice -> assertThat(notice.unread()).isFalse());
    }


    @Test
    @DisplayName("이미지 서비스 설정을 쓰지 못한 실패가 이어지면 관리자에게 알리고 계속 기다린다")
    void noticesAdminWhenWriteKeepsFailing() {
        when(toolsets.readEnabled(anyString(), eq(PROFILE))).thenReturn(List.of("web"));
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not write Hermes toolsets"))
            .when(toolsets)
            .writeApiServerInSandbox(anyString(), anyList(), anyString());

        pending.attemptAll();
        pending.attemptAll();
        assertThat(notices()).as("한두 번의 실패는 알리지 않는다").isEmpty();
        pending.attemptAll();
        pending.attemptAll();

        assertThat(waiting()).containsExactly("image_gen");
        assertThat(notices()).singleElement().satisfies(notice -> assertThat(notice.body()).contains("지윤", "Hermes"));
    }


    @Test
    @DisplayName("관리자가 켰다가 끈 도구는 기다리는 목록에서 빠져 다음 차례에도 다시 켜지 않는다")
    void doesNotReenableAfterAdminTurnedItOff() {
        Agent loaded = agents.findById(agent.id()).orElseThrow();

        pending.settle(loaded, List.of("web", "image_gen"), List.of("web"));
        pending.attemptAll();

        assertThat(waiting()).isEmpty();
        verify(toolsets, never()).writeApiServerInSandbox(anyString(), anyList(), anyString());
    }


    @Test
    @DisplayName("관리자가 도구 화면에서 「그림 만들기」 를 끄면 다음 차례가 그 비서를 다시 켜지 않는다")
    void adminSaveTurningItOffStopsTheRetry() {
        when(toolsets.readCatalog()).thenReturn(List.of(
            new ToolsetCatalogEntry("web", "웹", "웹 검색"), new ToolsetCatalogEntry("image_gen", "그림 만들기", "그림")));
        when(toolsets.readEnabled(anyString(), eq(PROFILE)))
            .thenReturn(List.of("web", "image_gen"))
            .thenReturn(List.of("web"));
        CurrentUser adminUser = new CurrentUser(admin.id(), admin.email(), "관리자", GROUP, UserRole.ADMIN);

        tools.writeAsAdmin(adminUser, PROFILE, List.of("web"));
        pending.attemptAll();

        assertThat(waiting()).isEmpty();
        verify(toolsets).writeApiServer(eq(PROFILE), eq(List.of("web", "fos-assistant")), anyString());
        verify(toolsets, never()).writeApiServerInSandbox(anyString(), anyList(), anyString());
    }


    @Test
    @DisplayName("이미 켜져 있던 도구도 한 번은 다시 써서 이미지 서비스 설정이 들어가게 한다")
    void rewritesAlreadyEnabledToolsetOnce() {
        when(toolsets.readEnabled(anyString(), eq(PROFILE))).thenReturn(List.of("web", "image_gen"));

        pending.attemptAll();
        pending.attemptAll();

        verify(toolsets).writeApiServerInSandbox(PROFILE, List.of("web", "image_gen", "fos-assistant"), "u" + owner.id());
        assertThat(waiting()).isEmpty();
    }


    @Test
    @DisplayName("꺼진 채 그대로인 저장은 계속 기다리고, 사람이 켠 저장은 더 기다리지 않는다")
    void keepsWaitingUntilSomeoneDecides() {
        Agent loaded = agents.findById(agent.id()).orElseThrow();

        pending.settle(loaded, List.of("web"), List.of("web", "todo"));
        assertThat(waiting()).containsExactly("image_gen");

        pending.settle(agents.findById(agent.id()).orElseThrow(), List.of("web"), List.of("web", "image_gen"));
        assertThat(waiting()).isEmpty();
    }


    @Test
    @DisplayName("그룹에 공개한 에이전트는 Hermes 가 답하지 않아도 켜지 않고 더 기다리지 않는다")
    void dropsPendingForGroupAgent() {
        jdbc.update("UPDATE agent SET visibility = 'GROUP' WHERE id = ?", agent.id());
        when(toolsets.readEnabled(anyString(), eq(PROFILE))).thenThrow(new IllegalStateException("Hermes 응답 없음"));

        pending.attemptAll();

        assertThat(waiting()).isEmpty();
        verify(toolsets, never()).readEnabled(anyString(), anyString());
        verify(toolsets, never()).writeApiServerInSandbox(anyString(), anyList(), anyString());
        assertThat(agents.findIdsWithPendingDefaultToolsets(0L, PageRequest.of(0, 10))).doesNotContain(agent.id());
    }


    @Test
    @DisplayName("기다리는 에이전트 번호는 마지막으로 본 번호 뒤부터 읽어 다음 쪽으로 넘어간다")
    void readsPendingIdsAfterTheLastSeenId() {
        assertThat(agents.findIdsWithPendingDefaultToolsets(agent.id() - 1, PageRequest.of(0, 10))).contains(agent.id());
        assertThat(agents.findIdsWithPendingDefaultToolsets(agent.id(), PageRequest.of(0, 10))).doesNotContain(agent.id());
    }


    @Test
    @DisplayName("배포 때 한 번 도는 마이그레이션은 기본 비서만 기다리는 목록에 넣는다")
    void backfillMarksOnlyPrimaryAgents() throws IOException {
        jdbc.update("DELETE FROM allowed_person WHERE email = ?", owner.email());
        jdbc.update(
            "INSERT INTO allowed_person (email, display_name, hermes_profile, enabled, created_at) VALUES (?, ?, ?, true, ?)",
            owner.email(), "지윤", PROFILE, Timestamp.from(Instant.parse("2026-11-19T00:00:00Z")));
        Agent custom = agents.save(Agent.of(
            PROFILE + "-custom",
            "글쓰기",
            PROFILE + "-custom",
            "http://listener.test/p/" + PROFILE + "-custom",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
        jdbc.update("UPDATE agent SET pending_default_toolsets = NULL");
        String sql = new ClassPathResource("db/migration/V20261105074418__agent_pending_default_toolsets_backfill.sql")
            .getContentAsString(StandardCharsets.UTF_8)
            .lines()
            .filter(line -> !line.startsWith("--"))
            .collect(Collectors.joining("\n"))
            .replace(";", "");

        jdbc.update(sql);

        assertThat(waiting()).containsExactly("image_gen");
        assertThat(agents.findById(custom.id()).orElseThrow().pendingToolsets()).isEmpty();
        jdbc.update("DELETE FROM agent WHERE id = ?", custom.id());
        jdbc.update("DELETE FROM allowed_person WHERE email = ?", owner.email());
    }


    private List<String> waiting() {
        return agents.findById(agent.id()).orElseThrow().pendingToolsets();
    }


    private List<Notification> notices() {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(admin.id(), PageRequest.of(0, 10));
    }
}
