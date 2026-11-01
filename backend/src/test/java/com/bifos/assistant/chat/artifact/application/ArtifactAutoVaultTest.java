package com.bifos.assistant.chat.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactAutoVaultStatus;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.application.ArtifactAutoVaultSetting;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * turn 이 끝날 때 결과물을 요청자의 보관함에 자동으로 보관하는지 확인한다(#530 PR 3).
 *
 * <p>같은 완료가 두 번 와도 한 건인지, 재시작 뒤 이어서 끝내는지, 지운 보관본을 다시 만들지 않는지, 그룹 공개 에이전트에서도
 * 요청자에게만 보관하는지, 실패해도 답과 원본이 남는지를 본다.
 */
@BackendIntegrationTest
class ArtifactAutoVaultTest {

    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");
    private static final String HTML = "<!doctype html><title>완성</title><p>자동 보관</p>";

    @Autowired
    ArtifactAutoVault autoVault;

    @Autowired
    ArtifactVaultService vault;

    @Autowired
    ArtifactAutoVaultSetting setting;

    @Autowired
    ArtifactStore store;

    @Autowired
    ChatArtifactRepository rows;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    TrackingBackgroundTasks backgroundTasks;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser mom;
    private AppUser dad;
    private Conversation momConversation;
    private final List<Long> folders = new ArrayList<>();


    @BeforeEach
    void setUp() {
        mom = member("auto-mom-" + UUID.randomUUID());
        dad = member("auto-dad-" + UUID.randomUUID());
        // 아빠가 그룹에 공개한 에이전트를 엄마가 쓴다. 보관 주인은 대화를 연 엄마다.
        String code = "shared-" + UUID.randomUUID();
        Agent shared = agents.save(Agent.of(
            code, "공용", code, "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, dad.id()));
        momConversation = conversation(mom, shared.id());
        setting.change(mom.id(), true);
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> row.isVaulted()).toList());
        folders.forEach(store::deleteFolder);
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("고른 적이 없는 사용자도 자동 보관이 켜져 있고 끈 사용자의 결과물은 보관하지 않는다")
    void onByDefaultAndSkipsWhenTurnedOff() throws Exception {
        assertThat(setting.isEnabled(dad.id())).isEqualTo(ArtifactAutoVaultSetting.DEFAULT_ENABLED).isTrue();
        assertThat(jdbc.queryForObject("select artifact_auto_vault from app_user where id = ?", Boolean.class, dad.id()))
            .as("새 사용자의 칸은 DB 기본값으로 켜짐이다")
            .isTrue();
        Conversation dadConversation = conversation(dad, null);
        ChatArtifact kept = answerWithFile(dadConversation, "kept.html");
        autoVault.afterCompletedTurn(dadConversation.id(), kept.messageId());
        awaitBackground();
        assertThat(state(kept)).isEqualTo(AutoVaultState.DONE);

        setting.change(dad.id(), false);
        ChatArtifact skipped = answerWithFile(dadConversation, "skipped.html");
        autoVault.afterCompletedTurn(dadConversation.id(), skipped.messageId());
        awaitBackground();

        assertThat(state(skipped)).isNull();
        assertThat(vault.list(dad.id())).extracting(ArtifactVaultItem::path).containsExactly("kept.html");
    }


    @Test
    @DisplayName("같은 turn 의 완료가 두 번 오고 훑기와 겹쳐도 보관본은 하나다")
    void completingTwiceKeepsOneCopy() throws Exception {
        ChatArtifact row = answerWithFile(momConversation, "page.html");

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> callers = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            callers.add(Thread.ofPlatform().start(() -> {
                await(start);
                autoVault.afterCompletedTurn(momConversation.id(), row.messageId());
                autoVault.sweep();
            }));
        }
        start.countDown();
        for (Thread caller : callers) {
            caller.join();
        }
        awaitBackground();

        assertThat(state(row)).isEqualTo(AutoVaultState.DONE);
        assertThat(vault.list(mom.id())).singleElement().extracting(ArtifactVaultItem::path).isEqualTo("page.html");
    }


    @Test
    @DisplayName("재시작으로 끊긴 보관은 다시 차례에 두고 이어서 끝낸다")
    void resumesAfterRestart() throws Exception {
        ChatArtifact running = answerWithFile(momConversation, "running.html");
        ChatArtifact pending = answerWithFile(momConversation, "pending.html");
        // 한 줄은 맡은 채로, 한 줄은 차례를 기다리는 채로 프로세스가 멈췄다.
        setState(running, AutoVaultState.RUNNING);
        setState(pending, AutoVaultState.PENDING);

        autoVault.resumeAfterRestart();
        awaitBackground();

        assertThat(state(running)).isEqualTo(AutoVaultState.DONE);
        assertThat(state(pending)).isEqualTo(AutoVaultState.DONE);
        assertThat(vault.list(mom.id())).extracting(ArtifactVaultItem::path)
            .containsExactlyInAnyOrder("running.html", "pending.html");
    }


    @Test
    @DisplayName("사용자가 지운 보관본은 다른 답이 같은 파일을 묶어도 자동으로 다시 만들지 않고 직접 보관하면 다시 생긴다")
    void doesNotRecreateDeletedCopy() throws Exception {
        ChatArtifact first = answerWithFile(momConversation, "page.html");
        autoVault.afterCompletedTurn(momConversation.id(), first.messageId());
        awaitBackground();
        Long kept = vault.list(mom.id()).getFirst().id();

        vault.delete(mom.id(), kept);
        assertThat(jdbc.queryForObject(
            "select count(*) from chat_artifact where id = ? and vault_body is null and path = ''", Integer.class, kept))
                .as("지운 보관본은 본문과 경로가 빈 이력으로 남는다")
                .isEqualTo(1);
        ChatMessage again = messages.save(ChatMessage.fromAssistant(momConversation.id(), "다시 묶음", null, NOW));
        ChatArtifact second = rows.save(ChatArtifact.of(momConversation.id(), again.id(), "page.html", HTML.length()));
        autoVault.afterCompletedTurn(momConversation.id(), again.id());
        awaitBackground();

        assertThat(state(second)).isEqualTo(AutoVaultState.SKIPPED);
        assertThat(vault.list(mom.id())).isEmpty();
        assertThat(rows.sumVaultBytes(mom.id())).isZero();

        assertThat(vault.vault(mom.id(), momConversation.id(), "page.html").path()).isEqualTo("page.html");
        assertThat(vault.list(mom.id())).hasSize(1);
    }


    @Test
    @DisplayName("그룹 공개 에이전트를 쓴 대화의 결과물은 요청자에게만 보관한다")
    void keepsOnlyForRequester() throws Exception {
        setting.change(dad.id(), true);
        ChatArtifact row = answerWithFile(momConversation, "page.html");

        autoVault.afterCompletedTurn(momConversation.id(), row.messageId());
        awaitBackground();

        assertThat(vault.list(mom.id())).hasSize(1);
        assertThat(vault.list(dad.id())).as("에이전트 주인에게는 보관하지 않는다").isEmpty();
    }


    @Test
    @DisplayName("보관하지 못하면 실패와 까닭이 남고 답과 원본은 그대로이며 다시 보관하면 끝난다")
    void failureKeepsAnswerAndRetries() throws Exception {
        // 대화 폴더 밖을 부르는 HTML 은 묶음 전체를 거절한다.
        write(momConversation, "page.html", "<!doctype html><img src=\"https://example.test/a.png\">");
        ChatMessage answer = messages.save(ChatMessage.fromAssistant(momConversation.id(), "만들었어요", null, NOW));
        ChatArtifact row = rows.save(ChatArtifact.of(momConversation.id(), answer.id(), "page.html", 10));

        autoVault.afterCompletedTurn(momConversation.id(), answer.id());
        awaitBackground();

        ChatArtifact failed = rows.findById(row.id()).orElseThrow();
        assertThat(failed.autoVaultState()).isEqualTo(AutoVaultState.FAILED);
        assertThat(failed.autoVaultError()).isEqualTo(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED.name());
        assertThat(messages.findById(answer.id())).isPresent();
        assertThat(store.resolveInside(momConversation.id(), "page.html")).isPresent();

        write(momConversation, "page.html", HTML);
        ArtifactAutoVaultStatus retried = autoVault.retry(momConversation.id(), answer.id(), "page.html");

        assertThat(retried.state()).isEqualTo(AutoVaultState.DONE);
        assertThat(retried.error()).isNull();
        assertThat(vault.list(mom.id())).hasSize(1);
    }


    private ChatArtifact answerWithFile(Conversation conversation, String path) {
        write(conversation, path, HTML + path);
        ChatMessage answer = messages.save(ChatMessage.fromAssistant(conversation.id(), "만들었어요", null, NOW));
        return rows.save(ChatArtifact.of(conversation.id(), answer.id(), path, HTML.length()));
    }


    private AutoVaultState state(ChatArtifact row) {
        return rows.findById(row.id()).orElseThrow().autoVaultState();
    }


    private void setState(ChatArtifact row, AutoVaultState state) {
        jdbc.update("update chat_artifact set auto_vault_state = ? where id = ?", state.name(), row.id());
    }


    private void awaitBackground() throws InterruptedException {
        backgroundTasks.awaitIdle(Duration.ofSeconds(20));
    }


    private Conversation conversation(AppUser owner, Long agentId) {
        Conversation conversation = conversations.save(Conversation.startedBy(owner.id(), "결과물", agentId, NOW));
        folders.add(conversation.id());
        return conversation;
    }


    private void write(Conversation conversation, String path, String content) {
        store.write(conversation.id(), path, content.getBytes(StandardCharsets.UTF_8));
    }


    private AppUser member(String name) {
        String email = name + "@example.test";
        return users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
