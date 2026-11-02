package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.purge.application.ConversationPurger;
import com.bifos.assistant.hermes.application.port.HermesDashboardClient;
import com.bifos.assistant.people.application.UserPurger;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 정리 작업이 예약한 지 30일이 지난 사용자의 데이터를 순서대로 지우고, 그룹 공개 에이전트를 넘기는지 확인한다(ADR-20261101 / user-purge).
 *
 * <p>모든 표를 지우는지와 외래 키는 {@code PurgeCoverageMysqlTest} 가 실제 MySQL 에서 본다. 이 검사는 순서, 넘기기, 다시 하기를 본다.
 * 시각을 아주 옛날로 두어 다른 검사가 실제 시각으로 예약한 사람이 후보에 들지 않게 한다.
 */
@BackendIntegrationTest
class UserPurgerTest {

    static final Instant NOW = Instant.parse("2000-03-01T00:00:00Z");

    /** 다른 검사의 그룹과 겹치지 않는 그룹 번호다. */
    static final AtomicLong GROUPS = new AtomicLong(800_000_000L + (System.nanoTime() % 1_000_000L) * 10);

    @Autowired
    UserPurger purger;

    @Autowired
    ConversationPurger conversationPurger;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ChatArtifactRepository artifacts;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    HermesDashboardClient dashboard;

    @Autowired
    JdbcTemplate jdbc;

    long group;


    @BeforeEach
    void setUp() {
        group = GROUPS.getAndIncrement();
    }


    @Test
    @DisplayName("정리 뒤 그 사용자의 보관본과 묶음 안 파일, 지우기 이력과 key 와 사용자 줄이 없고 다른 사용자의 보관본은 남는다")
    void deletesVaultKeyAndUserButKeepsOtherUsersVault() {
        Member target = member("target", UserRole.MEMBER);
        Member other = member("other", UserRole.MEMBER);
        schedule(target, NOW.minus(Duration.ofDays(31)));
        long root = vault(target, null);
        vault(target, root);
        long forgotten = vault(target, null);
        jdbc.update("UPDATE chat_artifact SET vault_body = NULL, vault_key_id = NULL, deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
            forgotten);
        long othersRoot = vault(other, null);
        dataKey(target);
        dataKey(other);

        assertThat(purger.purgeDue(NOW)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_artifact WHERE vault_user_id = ?", Long.class, target.user().id()))
            .isZero();
        assertThat(artifacts.findById(othersRoot)).isPresent();
        assertThat(keys(target)).isZero();
        assertThat(keys(other)).isEqualTo(1);
        assertThat(users.findById(target.user().id())).isEmpty();
        assertThat(users.findById(other.user().id())).isPresent();
        AllowedPerson after = people.findById(target.person().id()).orElseThrow();
        assertThat(after.enabled()).isFalse();
        assertThat(after.purgeScheduledAt()).isNull();
        assertThat(purger.purgeDue(NOW)).as("예약을 비웠으므로 다시 고르지 않는다").isZero();
    }


    @Test
    @DisplayName("코드가 읽고 쓰지 않는 옛 허락 테이블도 정리하는 사용자의 행만 지우고 다른 사용자의 행은 남긴다")
    void deletesOnlyPurgedUsersLegacyGrantRows() {
        Member target = member("target", UserRole.MEMBER);
        Member other = member("other", UserRole.MEMBER);
        schedule(target, NOW.minus(Duration.ofDays(31)));
        legacyGrants(target);
        legacyGrants(other);

        assertThat(purger.purgeDue(NOW)).isEqualTo(1);

        assertThat(legacyGrantRows(target)).containsExactly(0L, 0L);
        assertThat(legacyGrantRows(other)).containsExactly(1L, 1L);
    }


    @Test
    @DisplayName("그룹 공개 에이전트는 정리 대상을 뺀 켜진 관리자 가운데 번호가 가장 작은 사람에게 넘기고 비공개 에이전트는 지운 것으로 적는다")
    void transfersGroupAgentsToSmallestEnabledAdmin() {
        Member disabledAdmin = member("disabled-admin", UserRole.ADMIN);
        people.save(disable(disabledAdmin.person()));
        Member target = member("target-admin", UserRole.ADMIN);
        Member admin = member("admin", UserRole.ADMIN);
        member("later-admin", UserRole.ADMIN);
        schedule(target, NOW.minus(Duration.ofDays(31)));
        Agent shared = agent(target, AgentVisibility.GROUP, false);
        Agent own = agent(target, AgentVisibility.PRIVATE, false);

        assertThat(purger.purgeDue(NOW)).isEqualTo(1);

        Agent transferred = agents.findById(shared.id()).orElseThrow();
        assertThat(transferred.ownerUserId()).isEqualTo(admin.user().id());
        assertThat(transferred.visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(transferred.isDeleted()).isFalse();
        assertThat(agents.findById(own.id()).orElseThrow().isDeleted()).isTrue();
    }


    @Test
    @DisplayName("넘겨받을 켜진 관리자가 없으면 아무것도 지우지 않고 미루며, 관리자를 켜면 간격이 지난 뒤 끝낸다")
    void defersWithoutAdminAndFinishesAfterAdminIsEnabled() {
        Member target = member("lonely", UserRole.MEMBER);
        schedule(target, NOW.minus(Duration.ofDays(31)));
        Agent shared = agent(target, AgentVisibility.GROUP, false);
        long root = vault(target, null);
        dataKey(target);

        assertThat(purger.purgeDue(NOW)).isZero();

        assertThat(artifacts.findById(root)).as("넘기기 전에는 아무것도 지우지 않는다").isPresent();
        assertThat(keys(target)).isEqualTo(1);
        assertThat(users.findById(target.user().id())).isPresent();

        Member admin = member("new-admin", UserRole.ADMIN);
        assertThat(purger.purgeDue(NOW.plusSeconds(30))).as("기다리는 간격 안에는 다시 보지 않는다").isZero();
        assertThat(purger.purgeDue(NOW.plusSeconds(61))).isEqualTo(1);

        assertThat(agents.findById(shared.id()).orElseThrow().ownerUserId()).isEqualTo(admin.user().id());
        assertThat(artifacts.findById(root)).isEmpty();
        assertThat(users.findById(target.user().id())).isEmpty();
    }


    @Test
    @DisplayName("예약한 지 30일이 지나지 않았거나 그 안에 다시 켜면 아무것도 지우지 않는다")
    void keepsEverythingWithinGraceOrAfterReEnable() {
        Member recent = member("recent", UserRole.MEMBER);
        schedule(recent, NOW.minus(Duration.ofDays(29)));
        Member reEnabled = member("re-enabled", UserRole.MEMBER);
        schedule(reEnabled, NOW.minus(Duration.ofDays(31)));
        AllowedPerson person = people.findById(reEnabled.person().id()).orElseThrow();
        person.enable();
        people.save(person);
        long recentRoot = vault(recent, null);
        long reEnabledRoot = vault(reEnabled, null);

        assertThat(purger.purgeDue(NOW)).isZero();

        assertThat(artifacts.findById(recentRoot)).isPresent();
        assertThat(artifacts.findById(reEnabledRoot)).isPresent();
        assertThat(users.findById(recent.user().id())).isPresent();
        assertThat(users.findById(reEnabled.user().id())).isPresent();
        assertThat(people.findById(recent.person().id()).orElseThrow().purgeScheduledAt()).isNotNull();
    }


    @Test
    @DisplayName("중간 단계가 실패해도 key 와 사용자 줄은 남고, 간격이 지난 뒤 다시 돌리면 끝난다")
    void keepsKeyWhenMiddleStepFailsAndFinishesOnRetry() {
        Member target = member("flaky", UserRole.MEMBER);
        schedule(target, NOW.minus(Duration.ofDays(31)));
        Agent own = agent(target, AgentVisibility.PRIVATE, true);
        long root = vault(target, null);
        dataKey(target);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down")).when(dashboard).deleteProfile(own.hermesProfile());

        assertThat(purger.purgeDue(NOW)).isZero();

        assertThat(artifacts.findById(root)).as("앞 단계는 이미 끝났다").isEmpty();
        assertThat(keys(target)).as("실패한 단계 뒤의 key 는 남는다").isEqualTo(1);
        assertThat(users.findById(target.user().id())).isPresent();
        assertThat(agents.findById(own.id()).orElseThrow().isDeleted()).isFalse();

        doNothing().when(dashboard).deleteProfile(own.hermesProfile());
        assertThat(purger.purgeDue(NOW.plusSeconds(61))).isEqualTo(1);

        assertThat(keys(target)).isZero();
        assertThat(users.findById(target.user().id())).isEmpty();
        assertThat(agents.findById(own.id()).orElseThrow().isDeleted()).isTrue();
    }


    @Test
    @DisplayName("대화 정리가 끝나기 전에는 key 와 사용자 줄을 지우지 않고 기다린다")
    void waitsForConversationPurgeBeforeDeletingKey() {
        Member target = member("talker", UserRole.MEMBER);
        schedule(target, NOW.minus(Duration.ofDays(31)));
        Agent own = agent(target, AgentVisibility.PRIVATE, false);
        Conversation conversation = conversations.saveAndFlush(Conversation.startedBy(target.user().id(), "대화", own.id(), NOW));
        dataKey(target);

        assertThat(purger.purgeDue(NOW)).isZero();

        assertThat(conversations.findById(conversation.id()).orElseThrow().deletedAt()).isNotNull();
        assertThat(keys(target)).isEqualTo(1);
        assertThat(users.findById(target.user().id())).isPresent();

        conversationPurger.purgeDue(NOW);
        assertThat(conversations.findById(conversation.id()).orElseThrow().purgedAt()).isNotNull();
        assertThat(purger.purgeDue(NOW.plusSeconds(60))).as("대화 정리를 기다리는 사람은 5분 동안 다시 보지 않는다").isZero();
        assertThat(keys(target)).isEqualTo(1);
        assertThat(purger.purgeDue(NOW.plusSeconds(301))).isEqualTo(1);

        assertThat(keys(target)).isZero();
        assertThat(users.findById(target.user().id())).isEmpty();
    }


    private Member member(String name, UserRole role) {
        String email = name + "-" + UUID.randomUUID() + "@example.test";
        AllowedPerson person = people.save(AllowedPerson.of(email, name, "p" + UUID.randomUUID().toString().substring(0, 12)));
        AppUser user = users.save(AppUser.of(email, name, group, role));
        return new Member(person, user);
    }


    private static AllowedPerson disable(AllowedPerson person) {
        person.disable();
        return person;
    }


    /** 엔티티로 적어 읽는 쪽과 같은 UTC 로 저장한다. JdbcTemplate 으로 적으면 JVM 시간대가 섞인다. */
    private void schedule(Member member, Instant at) {
        AllowedPerson person = people.findById(member.person().id()).orElseThrow();
        person.disable();
        person.schedulePurge(at);
        people.save(person);
    }


    private Agent agent(Member owner, AgentVisibility visibility, boolean managed) {
        String code = "upurge-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = Agent.of(code, "정리할 사용자의 에이전트", code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, visibility, owner.user().id());
        if (managed) {
            agent.markManagedProfile();
        }
        return agents.saveAndFlush(agent);
    }


    /** 보관본 행 하나를 넣는다. {@code bundleId} 가 있으면 그 묶음 안 파일이다. 본문은 암호문 대역이다. */
    private long vault(Member owner, Long bundleId) {
        String fingerprint = UUID.randomUUID().toString();
        ChatArtifact row = bundleId == null
            ? ChatArtifact.vaulted(1L, "report.md", 3, owner.user().id(), 1L, "암호문 대역", fingerprint)
            : ChatArtifact.vaultedPart(1L, "part.md", 3, owner.user().id(), 1L, "암호문 대역", fingerprint, bundleId);
        return artifacts.saveAndFlush(row).id();
    }


    private void dataKey(Member owner) {
        jdbc.update("INSERT INTO user_data_key (user_id, kek_id, wrapped_key, created_at) VALUES (?, 'test', 'wrapped', CURRENT_TIMESTAMP)",
            owner.user().id());
    }


    private long keys(Member owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_data_key WHERE user_id = ?", Long.class, owner.user().id());
    }


    /** 직전 이미지가 쓴 것처럼 옛 허락 두 테이블에 한 줄씩 넣는다(ADR-20261102 / table-reduce-r2). */
    private void legacyGrants(Member owner) {
        jdbc.update("""
                INSERT INTO connector_tool_grant (user_id, connector_id, tool_name, expires_at, created_at)
                VALUES (?, 'legacy', 'legacy_tool', NULL, CURRENT_TIMESTAMP)
                """, owner.user().id());
        jdbc.update("""
                INSERT INTO connector_conversation_grant
                    (user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, created_at)
                VALUES (?, 1, 1, 'legacy', 'legacy_tool', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, owner.user().id());
    }


    private List<Long> legacyGrantRows(Member owner) {
        return List.of(
            jdbc.queryForObject("SELECT COUNT(*) FROM connector_tool_grant WHERE user_id = ?", Long.class, owner.user().id()),
            jdbc.queryForObject("SELECT COUNT(*) FROM connector_conversation_grant WHERE user_id = ?", Long.class,
                owner.user().id()));
    }


    private record Member(AllowedPerson person, AppUser user) {
    }
}
