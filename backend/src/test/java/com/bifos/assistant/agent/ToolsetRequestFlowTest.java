package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.ToolsetRequestService;
import com.bifos.assistant.agent.application.ToolsetRequestView;
import com.bifos.assistant.agent.application.ToolsetVisibilityService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolsetRequest;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.domain.type.ToolsetRequestStatus;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentToolsetRequestRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.SkillStore;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 트랜잭션과 알림을 쓰고 Hermes 반영과 실패만 대역으로 바꾼다. */
@BackendIntegrationTest
class ToolsetRequestFlowTest {
    @Autowired
    ToolsetRequestService service;

    @Autowired
    AgentToolsetRequestRepository requests;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    NotificationService notifications;

    @Autowired
    ToolsetVisibilityService visibility;

    @Autowired
    HermesToolsetClient hermes;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SkillStore skillStore;

    private CurrentUser owner;
    private CurrentUser admin;
    private Agent agent;
    private Long group;
    private final AtomicReference<List<String>> enabled = new AtomicReference<>(List.of("web"));

    @BeforeEach
    void setUp() {
        group = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 1000);
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        owner = user("owner-" + suffix, UserRole.MEMBER, group);
        admin = user("admin-" + suffix, UserRole.ADMIN, group);
        agent = transactions.execute(status -> agents.saveAndFlush(Agent.of(
                "request-" + suffix,
                "요청 비서",
                "request-" + suffix,
                "http://hermes.test",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now())));
        when(hermes.readCatalog())
                .thenReturn(List.of(
                        new ToolsetCatalogEntry("web", "검색", "검색"),
                        new ToolsetCatalogEntry("image_gen", "그림", "그림"),
                        new ToolsetCatalogEntry("session_search", "대화", "대화")));
        when(hermes.readEnabled(anyString(), anyString())).thenAnswer(call -> enabled.get());
        doAnswer(call -> {
                    enabled.set(call.getArgument(1));
                    return null;
                })
                .when(hermes)
                .writeApiServer(anyString(), anyList(), anyString());
    }

    @AfterEach
    void cleanUp() {
        skillStore.deleteAll(agent.hermesProfile());
        jdbc.update("delete from notification where user_id in (select id from app_user where group_id = ?)", group);
        jdbc.update("delete from agent_toolset_request where group_id = ?", group);
        jdbc.update("delete from toolset_hidden where group_id = ?", group);
        jdbc.update("delete from agent_memory_collection where agent_id = ?", agent.id());
        jdbc.update("delete from agent where id = ?", agent.id());
        jdbc.update("delete from allowed_person where email in (select email from app_user where group_id = ?)", group);
        jdbc.update("delete from app_user where group_id = ?", group);
    }

    @Test
    @DisplayName("중복 요청은 같은 번호와 한 알림을 남기고 승인은 기존 도구를 보존하며 결과를 한 번 알린다")
    void deduplicatesAndApprovesWithoutLosingExistingTools() {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        assertThat(service.request(owner, agent.code(), "image_gen").id()).isEqualTo(row.id());
        assertThat(notifications.page(admin, null, 100).items()).hasSize(1);
        assertThat(service.decide(admin, row.id(), true, null).status()).isEqualTo(ToolsetRequestStatus.APPROVED);
        assertThat(enabled.get()).containsExactly("web", "image_gen", "fos-assistant");
        service.decide(admin, row.id(), true, null);
        assertThat(notifications.page(owner, null, 100).items())
                .hasSize(1)
                .allMatch(item -> item.kind() == NotificationKind.TOOLSET_REQUEST_DECIDED);
    }

    @Test
    @DisplayName("같은 도구를 동시에 요청해도 에이전트 잠금 뒤 대기 요청과 알림은 하나만 남는다")
    void deduplicatesConcurrentRequests() {
        CompletableFuture<ToolsetRequestView> first =
                CompletableFuture.supplyAsync(() -> service.request(owner, agent.code(), "image_gen"));
        CompletableFuture<ToolsetRequestView> second =
                CompletableFuture.supplyAsync(() -> service.request(owner, agent.code(), "image_gen"));
        assertThat(first.orTimeout(10, TimeUnit.SECONDS).join().id())
                .isEqualTo(second.orTimeout(10, TimeUnit.SECONDS).join().id());
        assertThat(service.list(owner, agent.code(), false)).hasSize(1);
        assertThat(notifications.page(admin, null, 100).items()).hasSize(1);
    }

    @Test
    @DisplayName("Hermes 실패와 미반영은 승인을 기록하지 않고 실패가 끝나면 같은 요청을 다시 승인한다")
    void keepsPendingOnHermesFailureAndAllowsRetry() {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "unavailable"))
                .when(hermes)
                .writeApiServer(anyString(), anyList(), anyString());
        assertError(() -> service.decide(admin, row.id(), true, null), ErrorCode.HERMES_UNAVAILABLE);
        doAnswer(call -> null).when(hermes).writeApiServer(anyString(), anyList(), anyString());
        assertError(() -> service.decide(admin, row.id(), true, null), ErrorCode.AGENT_TOOLS_NOT_APPLIED);
        assertThat(service.read(owner, row.id(), false).status()).isEqualTo(ToolsetRequestStatus.PENDING);
        assertThat(notifications.page(owner, null, 100).items()).isEmpty();
        doAnswer(call -> {
                    enabled.set(call.getArgument(1));
                    return null;
                })
                .when(hermes)
                .writeApiServer(anyString(), anyList(), anyString());
        assertThat(service.decide(admin, row.id(), true, null).status()).isEqualTo(ToolsetRequestStatus.APPROVED);
    }

    @Test
    @DisplayName("Hermes 반영 뒤 DB 저장이 되돌아가도 재승인은 같은 활성 도구를 확인하고 완료한다")
    void recoversWhenDatabaseRollsBackAfterHermesApplies() {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        transactions.executeWithoutResult(status -> {
            service.decide(admin, row.id(), true, null);
            status.setRollbackOnly();
        });
        assertThat(enabled.get()).contains("image_gen");
        assertThat(service.read(owner, row.id(), false).status()).isEqualTo(ToolsetRequestStatus.PENDING);
        assertThat(notifications.page(owner, null, 100).items()).isEmpty();
        service.decide(admin, row.id(), true, null);
        assertThat(enabled.get().stream().filter("image_gen"::equals)).hasSize(1);
        assertThat(notifications.page(owner, null, 100).items()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "deleted", "owner", "group", "revoked", "public"})
    @DisplayName("요청 뒤 숨김·삭제·주인·그룹·사용권한·공개 범위가 바뀌면 승인 없이 만료하고 알린다")
    void expiresWhenEligibilityChanges(String change) {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        switch (change) {
            case "hidden" -> visibility.save(admin, List.of("image_gen"));
            case "deleted" -> changeAgent(true, owner.id(), AgentVisibility.PRIVATE);
            case "owner" -> changeAgent(false, admin.id(), AgentVisibility.PRIVATE);
            case "public" -> changeAgent(false, owner.id(), AgentVisibility.GROUP);
            case "group" -> jdbc.update("update app_user set group_id = ? where id = ?", group + 1, owner.id());
            case "revoked" ->
                transactions.executeWithoutResult(status -> {
                    AllowedPerson person = people.save(
                            AllowedPerson.of(owner.email(), "사용자", "revoked-" + agent.code(), Instant.now()));
                    person.disable();
                    people.saveAndFlush(person);
                });
            default -> throw new IllegalArgumentException(change);
        }
        try {
            assertThat(service.decide(admin, row.id(), true, null).status()).isEqualTo(ToolsetRequestStatus.EXPIRED);
            assertThat(enabled.get()).doesNotContain("image_gen");
            assertThat(notifications.unreadCount(owner.id())).isEqualTo(1);
        } finally {
            if (change.equals("group")) {
                jdbc.update("update app_user set group_id = ? where id = ?", group, owner.id());
            }
        }
    }

    @Test
    @DisplayName("다른 주인과 그룹은 읽거나 결정하지 못하고 관리자의 현재 역할도 다시 확인한다")
    void rejectsOtherOwnersGroupsAndLostAdminRole() {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        assertError(() -> service.request(admin, agent.code(), "image_gen"), ErrorCode.TOOLSET_REQUEST_NOT_FOUND);
        assertError(() -> service.read(admin, row.id(), false), ErrorCode.TOOLSET_REQUEST_NOT_FOUND);
        CurrentUser outsider = user("outside-" + agent.code(), UserRole.ADMIN, group + 1);
        try {
            assertError(() -> service.decide(outsider, row.id(), true, null), ErrorCode.TOOLSET_REQUEST_NOT_FOUND);
        } finally {
            users.deleteById(outsider.id());
        }
        jdbc.update("update app_user set role = 'MEMBER' where id = ?", admin.id());
        assertError(() -> service.decide(admin, row.id(), true, null), ErrorCode.FORBIDDEN);
        assertError(() -> service.decide(owner, row.id(), true, null), ErrorCode.FORBIDDEN);
        assertThat(service.read(owner, row.id(), false).status()).isEqualTo(ToolsetRequestStatus.PENDING);
    }

    @Test
    @DisplayName("거절 사유는 한 줄이어야 하고 취소와 거절 뒤에는 새 요청을 만들 수 있다")
    void requiresOneLineReasonAndReleasesPendingSlot() {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        assertError(() -> service.decide(admin, row.id(), false, " "), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.decide(admin, row.id(), false, "다음\n번"), ErrorCode.VALIDATION_FAILED);
        assertThat(service.decide(admin, row.id(), false, " 다음에 켜 드릴게요. ").reason())
                .isEqualTo("다음에 켜 드릴게요.");
        ToolsetRequestView second = service.request(owner, agent.code(), "image_gen");
        assertThat(second.id()).isNotEqualTo(row.id());
        assertThat(service.cancel(owner, second.id()).status()).isEqualTo(ToolsetRequestStatus.CANCELLED);
        assertThat(service.decide(admin, second.id(), true, null).status()).isEqualTo(ToolsetRequestStatus.CANCELLED);
        assertThat(service.request(owner, agent.code(), "image_gen").id()).isNotEqualTo(second.id());
        assertThat(enabled.get()).doesNotContain("image_gen");
    }

    @Test
    @DisplayName("DB 유일 제약도 대기 요청 중복을 막는다")
    void enforcesPendingUniquenessInDatabase() {
        service.request(owner, agent.code(), "image_gen");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> requests.saveAndFlush(
                        AgentToolsetRequest.of(group, agent.id(), owner.id(), "image_gen", Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("숨긴 도구·주인 등급·이미 켜진 도구·그룹 공개 제약은 새 요청에서 거절한다")
    void rejectsInvalidNewRequests() {
        assertError(() -> service.request(owner, agent.code(), "web"), ErrorCode.TOOLSET_REQUEST_UNAVAILABLE);
        assertError(() -> service.request(owner, agent.code(), "unknown"), ErrorCode.TOOLSET_REQUEST_UNAVAILABLE);
        visibility.save(admin, List.of("image_gen"));
        assertError(() -> service.request(owner, agent.code(), "image_gen"), ErrorCode.TOOLSET_REQUEST_UNAVAILABLE);
        visibility.save(admin, List.of());
        enabled.set(List.of("image_gen"));
        assertError(() -> service.request(owner, agent.code(), "image_gen"), ErrorCode.TOOLSET_REQUEST_UNAVAILABLE);
        enabled.set(List.of());
        changeAgent(false, owner.id(), AgentVisibility.GROUP);
        assertError(() -> service.request(owner, agent.code(), "image_gen"), ErrorCode.TOOLSET_REQUEST_UNAVAILABLE);
    }

    @Test
    @DisplayName("요청 뒤 올린 비밀 요청 스킬과 실행 공간 실패도 승인 때 다시 검사한다")
    void rechecksUploadedSecretsAndSandboxOnApproval() {
        ToolsetRequestView row = service.request(owner, agent.code(), "image_gen");
        String content = """
                ---
                name: secret-test
                description: 검사
                required_environment_variables:
                  - EXAMPLE_SECRET
                ---
                검사
                """;
        String version = skillStore.writeVersion(
                agent.hermesProfile(), Map.of("secret-test", new SkillBundle("secret-test", content, List.of())));
        skillStore.markPublished(agent.hermesProfile(), version);
        enabled.set(List.of("web", "skills"));
        when(hermes.readCatalog())
                .thenReturn(List.of(
                        new ToolsetCatalogEntry("web", "검색", "검색"),
                        new ToolsetCatalogEntry("skills", "스킬", "스킬"),
                        new ToolsetCatalogEntry("image_gen", "그림", "그림")));
        assertError(() -> service.decide(admin, row.id(), true, null), ErrorCode.AGENT_SKILL_REQUESTS_SECRETS);
        skillStore.deleteAll(agent.hermesProfile());
        doThrow(new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "sandbox unavailable"))
                .when(hermes)
                .writeApiServer(anyString(), anyList(), anyString());
        assertError(() -> service.decide(admin, row.id(), true, null), ErrorCode.AGENT_SANDBOX_UNAVAILABLE);
        assertThat(service.read(owner, row.id(), false).status()).isEqualTo(ToolsetRequestStatus.PENDING);
        assertThat(notifications.page(owner, null, 100).items()).isEmpty();
    }

    private CurrentUser user(String local, UserRole role, Long userGroup) {
        AppUser row = users.saveAndFlush(AppUser.of(local + "@example.com", "검사 사용자", userGroup, role, Instant.now()));
        return new CurrentUser(row.id(), row.email(), row.displayName(), row.groupId(), row.role());
    }

    private void changeAgent(boolean deleted, Long ownerId, AgentVisibility scope) {
        transactions.executeWithoutResult(status -> {
            Agent row = agents.findByIdForUpdate(agent.id()).orElseThrow();
            if (deleted) {
                row.markDeleted(Instant.now());
            } else {
                row.changeAccess(true, scope, ownerId);
            }
            agents.saveAndFlush(row);
        });
    }

    private static void assertError(ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
