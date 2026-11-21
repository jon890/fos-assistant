package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.ArtifactAutoVault;
import com.bifos.assistant.chat.artifact.application.ArtifactCleaner;
import com.bifos.assistant.chat.artifact.application.ArtifactService;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageDetail;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 비서가 만든 그림이 답에 묶이고, 응답에 지시문과 보관본 번호가 들어가며, 기존 자동 보관과 보관 기간 정리를 따르는 것을 확인한다
 * (스펙 generated-image REQ1, REQ5, REQ7, REQ9).
 */
@BackendIntegrationTest
class GeneratedImageTurnTest {
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private static final String PROMPT = "창가에 앉은 고양이를 수채화로, 부드러운 오후 햇살, 배경은 흐리게";

    @Autowired
    GeneratedImageService images;

    @Autowired
    ArtifactService artifactService;

    @Autowired
    ArtifactAutoVault autoVault;

    @Autowired
    ArtifactVaultService vault;

    @Autowired
    ArtifactCleaner cleaner;

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

    @Autowired
    ConversationEventHub hub;

    private final List<Long> folders = new ArrayList<>();
    private AppUser owner;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        String email = "image-turn-" + UUID.randomUUID() + "@example.test";
        owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        String code = "image-" + UUID.randomUUID();
        Agent agent = agents.save(Agent.of(
            code, "그림", code, "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id()));
        conversation = conversations.save(Conversation.startedBy(owner.id(), "그림", agent.id(), NOW));
        folders.add(conversation.id());
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> conversation.id().equals(row.conversationId())).toList());
        folders.forEach(store::deleteFolder);
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("turn 이 끝나면 그림 두 장이 만든 순서로 답에 묶이고 응답에 지시문이 든다")
    void bindsImagesToAnswerInOrder() {
        save("call_1", "수채화 고양이", png(1));
        save("call_2", "수채화 강아지", png(2));
        ChatMessage answer = answer("만들었어요");

        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        List<ChatArtifact> bound = bound(answer);
        assertThat(bound).extracting(ChatArtifact::path)
            .containsExactly("generated/수채화 고양이.png", "generated/수채화 강아지.png");
        assertThat(bound).allSatisfy(row -> assertThat(row.generationPrompt()).as("지시문은 평문으로 두지 않는다").isNotEqualTo(PROMPT));
        Map<Long, GeneratedImageDetail> details = details(answer);
        assertThat(details.get(bound.getFirst().id()).prompt()).isEqualTo(PROMPT);
        assertThat(details.get(bound.getFirst().id()).vaultItemId()).as("아직 보관본이 없다").isNull();
    }


    @Test
    @DisplayName("새로 저장한 그림은 답이 끝나기 전에 대화 단위 SSE 로 경로를 알리고 되풀이한 올리기는 다시 알리지 않는다")
    void publishesImageEventWhenStored() {
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(conversation.id(), received::add);
        try {
            save("call_1", "수채화 고양이", png(1));
            save("call_1", "수채화 고양이", png(1));
            save("call_2", "수채화 고양이", png(2));
        } finally {
            unsubscribe.run();
        }

        assertThat(received).extracting(ChatEvent::type).containsExactly("image", "image");
        assertThat(received).extracting(ChatEvent::detail)
            .containsExactly("generated/수채화 고양이.png", "generated/수채화 고양이 2.png");
        assertThat(received).extracting(ChatEvent::conversationId).containsOnly(conversation.publicId());
        assertThat(rows.findAll().stream().filter(row -> conversation.id().equals(row.conversationId())))
            .allSatisfy(row -> assertThat(row.messageId()).isNull());
    }


    @Test
    @DisplayName("참고한 링크는 암호화해 두고 응답에 올린 순서대로 들며 링크 없는 그림은 빈 목록이다")
    void keepsSourceLinksInOrderAndEncrypted() {
        List<String> links = List.of("https://shop.example.com/goods/1", "https://img.example.com/p.png");
        images.save(conversation.id(), owner.id(), "key-" + conversation.id() + "-links", "양말 신은 아이", PROMPT, links, png(3));
        save("call_plain", "그냥 고양이", png(4));
        ChatMessage answer = answer("만들었어요");

        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        List<ChatArtifact> bound = bound(answer);
        ChatArtifact linked = bound.getFirst();
        assertThat(linked.generationSourceLinks()).as("링크는 평문으로 두지 않는다").isNotNull().doesNotContain("shop.example.com");
        assertThat(linked.generationSourceLinksKeyId()).isNotNull();
        assertThat(bound.get(1).generationSourceLinks()).isNull();
        Map<Long, GeneratedImageDetail> details = details(answer);
        assertThat(details.get(linked.id()).sourceLinks()).containsExactlyElementsOf(links);
        assertThat(details.get(bound.get(1).id()).sourceLinks()).isEmpty();
    }


    @Test
    @DisplayName("답 없이 끝난 turn 과 turn 사이에 올라온 그림은 그 대화의 다음 답에 묶인다")
    void bindsLeftoverImagesToNextAnswer() {
        save("call_1", "남은 그림", png(1));
        artifactService.recordTurn(conversation.id(), null, NOW);
        assertThat(rows.findGeneratedImagePaths(conversation.id())).hasSize(1);

        ChatMessage first = answer("첫 답");
        artifactService.recordTurn(conversation.id(), first.id(), NOW.plusSeconds(60));
        // 부모 turn 이 끝난 뒤 맡긴 실행이 올린 그림이다.
        save("call_2", "맡긴 그림", png(2));
        ChatMessage second = answer("결과를 전하는 답");
        artifactService.recordTurn(conversation.id(), second.id(), NOW.plusSeconds(600));

        assertThat(bound(first)).extracting(ChatArtifact::path).containsExactly("generated/남은 그림.png");
        assertThat(bound(second)).extracting(ChatArtifact::path).containsExactly("generated/맡긴 그림.png");
    }


    @Test
    @DisplayName("완료한 답의 그림은 원본 그대로 자동 보관되고 대화 쪽 행을 지워도 보관본이 남는다")
    void autoVaultKeepsOriginalBytes() throws Exception {
        byte[] original = png(3);
        save("call_1", "보관할 그림", original);
        ChatMessage answer = answer("만들었어요");
        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        autoVault.afterCompletedTurn(conversation.id(), answer.id());
        backgroundTasks.awaitIdle(Duration.ofSeconds(20));

        assertThat(bound(answer)).singleElement().extracting(ChatArtifact::autoVaultState).isEqualTo(AutoVaultState.DONE);
        ArtifactVaultItem kept = vault.list(owner.id()).stream()
            .filter(item -> item.path().equals("generated/보관할 그림.png"))
            .findFirst()
            .orElseThrow();
        assertThat(vault.read(owner.id(), kept.id()).body()).isEqualTo(original);
        assertThat(details(answer).values()).singleElement().extracting(GeneratedImageDetail::vaultItemId).isEqualTo(kept.id());

        // 대화 정리는 보관 사본이 아닌 행만 지운다.
        jdbc.update("DELETE FROM chat_artifact WHERE conversation_id = ? AND vault_user_id IS NULL", conversation.id());
        assertThat(vault.list(owner.id())).extracting(ArtifactVaultItem::id).contains(kept.id());
        vault.delete(owner.id(), kept.id());
    }


    @Test
    @DisplayName("중지한 답의 그림은 보관하지 않고 직접 보관하면 보관본이 생긴다")
    void skipsAutoVaultWhenStopped() {
        // 중지한 답은 ChatTurnLifecycle 이 자동 보관을 부르지 않는다. 묶기만 한다.
        save("call_2", "중지한 그림", png(5));
        ChatMessage stopped = answer("중지됨");
        artifactService.recordTurn(conversation.id(), stopped.id(), NOW.plusSeconds(60));
        assertThat(bound(stopped)).singleElement().extracting(ChatArtifact::autoVaultState).isNull();

        ArtifactVaultItem kept = vault.vault(owner.id(), conversation.id(), "generated/중지한 그림.png");
        assertThat(details(stopped).values()).singleElement().extracting(GeneratedImageDetail::vaultItemId).isEqualTo(kept.id());
        vault.delete(owner.id(), kept.id());
    }


    @Test
    @DisplayName("보관 기간 정리가 그림 파일을 지우면 행에 지운 시각이 적히고 지시문과 보관본 번호는 남는다")
    void cleanupMarksImageRowAndKeepsPrompt() {
        save("call_1", "오래된 그림", png(6));
        ChatMessage answer = answer("만들었어요");
        artifactService.recordTurn(conversation.id(), answer.id(), NOW);
        ArtifactVaultItem kept = vault.vault(owner.id(), conversation.id(), "generated/오래된 그림.png");

        cleaner.cleanExpired(Instant.now().plus(Duration.ofDays(60)));

        ChatArtifact row = bound(answer).getFirst();
        assertThat(row.isDeleted()).isTrue();
        GeneratedImageDetail detail = details(answer).get(row.id());
        assertThat(detail.prompt()).isEqualTo(PROMPT);
        assertThat(detail.vaultItemId()).isEqualTo(kept.id());

        vault.delete(owner.id(), kept.id());
        assertThat(details(answer).get(row.id()).vaultItemId()).as("보관본을 지우면 링크가 없다").isNull();

        save("call_2", "오래된 그림", png(7));
        assertThat(rows.findGeneratedImagePaths(conversation.id()))
            .as("지워진 그림의 경로를 다시 쓰지 않는다")
            .containsExactlyInAnyOrder("generated/오래된 그림.png", "generated/오래된 그림 2.png");
    }


    private void save(String call, String title, byte[] image) {
        images.save(conversation.id(), owner.id(), "key-" + conversation.id() + "-" + call, title, PROMPT, image);
    }


    private ChatMessage answer(String text) {
        return messages.save(ChatMessage.fromAssistant(conversation.id(), text, null, NOW));
    }


    private List<ChatArtifact> bound(ChatMessage answer) {
        return rows.findByMessageId(answer.id()).stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
    }


    private Map<Long, GeneratedImageDetail> details(ChatMessage answer) {
        return images.details(owner.id(), conversation.id(), List.of(bound(answer)));
    }


    private static byte[] png(int filler) {
        byte[] bytes = new byte[256];
        Arrays.fill(bytes, (byte)filler);
        byte[] magic = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }
}
