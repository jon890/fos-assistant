package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.artifact.application.ArtifactPutFiles;
import com.bifos.assistant.chat.artifact.application.ArtifactService;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.artifact.application.ArtifactWriteService;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactKept;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteRequest;
import com.bifos.assistant.chat.artifact.application.dto.PutFileDetail;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 넣기 도구가 넣은 파일이 turn 끝에 답에 묶여 메시지 응답의 {@code FILE} 항목이 되고, 실행 입력에 파일 공간 단락이 드는 것을 확인한다
 * (스펙 workspace REQ3, REQ6, REQ7, REQ8).
 *
 * <p>메시지 응답은 {@link ArtifactPutFiles#byMessage} 가 준 값을 그대로 {@code FILE} 항목으로 옮긴다.
 */
@BackendIntegrationTest
class FileSpaceTurnTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(1);

    @Autowired
    ArtifactPutFiles putFiles;

    @Autowired
    ArtifactService artifactService;

    @Autowired
    ArtifactVaultService vault;

    @Autowired
    ArtifactWriteService writes;

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
    DataKeyService dataKeys;

    private AppUser owner;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        String email = "file-space-turn-" + UUID.randomUUID() + "@example.test";
        owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        conversation = conversations.save(Conversation.startedBy(owner.id(), "파일", null, NOW));
        // 디스크의 결과물 폴더는 다른 검사의 같은 번호 대화가 남겼을 수 있어 비운다.
        store.deleteFolder(conversation.id());
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> conversation.id().equals(row.conversationId())).toList());
        store.deleteFolder(conversation.id());
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("넣은 파일은 바뀐 HTML 이 없는 turn 에서도 답에 묶이고 제목과 보관본 번호가 응답에 든다")
    void putFileIsBoundToAnswerWithoutChangedHtml() throws IOException {
        writeFolderFile("q3.xlsx", xlsx(1));
        ArtifactKept kept = putFiles.putFromFolder(owner.id(), conversation.id(), "q3.xlsx", "3분기 표", "매출 정리", WAIT);
        ChatMessage answer = answer();

        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        List<PutFileDetail> files = putFiles.byMessage(List.of(answer.id())).get(answer.id());
        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.title()).isEqualTo("3분기 표");
            assertThat(file.vaultItemId()).isEqualTo(kept.item().id());
            assertThat(file.path()).isEqualTo("q3.xlsx");
            assertThat(file.byteSize()).isEqualTo(64);
            assertThat(file.deleted()).isFalse();
        });
        assertThat(rows.findByMessageId(answer.id())).as("대화 폴더의 결과물 줄은 만들지 않는다").isEmpty();
    }


    @Test
    @DisplayName("결과물 폴더를 읽을 수 없는 turn 에서도 넣은 파일이 묶인다")
    void putFileIsBoundWhenFolderCannotBeScanned() throws IOException {
        writeFolderFile("a.csv", "a,b\n".getBytes(StandardCharsets.UTF_8));
        putFiles.putFromFolder(owner.id(), conversation.id(), "a.csv", null, null, WAIT);
        // 폴더를 지우고 같은 이름의 파일로 바꿔 훑기가 아무것도 찾지 못하게 한다.
        store.deleteFolder(conversation.id());
        Path folder = store.ensureFolder(conversation.id());
        Files.delete(folder);
        Files.writeString(folder, "not a folder");
        ChatMessage answer = answer();

        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        assertThat(putFiles.byMessage(List.of(answer.id())).get(answer.id())).hasSize(1);
        Files.delete(folder);
    }


    @Test
    @DisplayName("내 파일에서 지우면 답 아래 줄은 지운 파일로 남는다")
    void deletedPutFileStaysAsDeleted() throws IOException {
        writeFolderFile("memo.md", "# 메모".getBytes(StandardCharsets.UTF_8));
        ArtifactKept kept = putFiles.putFromFolder(owner.id(), conversation.id(), "memo.md", "메모", null, WAIT);
        ChatMessage answer = answer();
        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        vault.delete(owner.id(), kept.item().id());

        assertThat(putFiles.byMessage(List.of(answer.id())).get(answer.id())).singleElement().satisfies(file -> {
            assertThat(file.deleted()).isTrue();
            assertThat(file.path()).isEmpty();
            assertThat(file.title()).isNull();
        });
    }


    @Test
    @DisplayName("같은 답에 같은 경로의 HTML 결과물이 있으면 파일 줄을 겹쳐 내지 않는다")
    void htmlArtifactOfSamePathHidesPutFile() {
        store.write(conversation.id(), "page.html", "<h1>보고서</h1>".getBytes(StandardCharsets.UTF_8));
        putFiles.putFromFolder(owner.id(), conversation.id(), "page.html", "보고서", null, WAIT);
        ChatMessage answer = answer();

        artifactService.recordTurn(conversation.id(), answer.id(), Instant.EPOCH);

        assertThat(rows.findByMessageId(answer.id())).extracting(row -> row.path()).containsExactly("page.html");
        assertThat(putFiles.byMessage(List.of(answer.id()))).doesNotContainKey(answer.id());
        assertThat(vault.list(owner.id())).as("보관본은 그대로 내 파일에 있다").hasSize(1);
    }


    @Test
    @DisplayName("넣기 도구 없이 대화 폴더에 쓴 엑셀은 답 아래에 없다")
    void folderFileWithoutPutIsNotListed() throws IOException {
        writeFolderFile("raw.xlsx", xlsx(2));
        ChatMessage answer = answer();

        artifactService.recordTurn(conversation.id(), answer.id(), NOW);

        assertThat(putFiles.byMessage(List.of(answer.id()))).isEmpty();
    }


    @Test
    @DisplayName("다시 넣어 이미 있으면 다음 답으로 옮겨 묶인다")
    void alreadyPutFileMovesToNextAnswer() throws IOException {
        writeFolderFile("same.csv", "a,b\n".getBytes(StandardCharsets.UTF_8));
        putFiles.putFromFolder(owner.id(), conversation.id(), "same.csv", null, null, WAIT);
        ChatMessage first = answer();
        artifactService.recordTurn(conversation.id(), first.id(), NOW);

        ArtifactKept again = putFiles.putFromFolder(owner.id(), conversation.id(), "same.csv", null, null, WAIT);
        ChatMessage second = answer();
        artifactService.recordTurn(conversation.id(), second.id(), NOW);

        assertThat(again.already()).isTrue();
        assertThat(putFiles.byMessage(List.of(first.id(), second.id())))
            .doesNotContainKey(first.id())
            .containsKey(second.id());
    }


    @Test
    @DisplayName("artifact_write 에 준 제목이 자동 보관본의 목록 제목이 된다")
    void artifactWriteTitleBecomesAutoVaultTitle() {
        CurrentUser user = new CurrentUser(owner.id(), owner.email(), "사용자", 1L, UserRole.MEMBER);
        writes.write(user, new ArtifactWriteRequest(
            conversation.publicId(), "report/index.html", "<h1>여행 계획</h1>", null, "제주 여행 계획", "3박 4일 일정"));
        ChatMessage answer = answer();
        artifactService.recordTurn(conversation.id(), answer.id(), Instant.EPOCH);

        ArtifactVaultItem kept = vault.vaultAutomatically(owner.id(), conversation.id(), "report/index.html").orElseThrow();

        assertThat(rows.findByMessageId(answer.id())).as("제목을 둔 줄이 답에 묶인다").hasSize(1);
        assertThat(kept.title()).isEqualTo("제주 여행 계획");
        assertThat(kept.description()).isEqualTo("3박 4일 일정");
    }


    @Test
    @DisplayName("실행 입력에 파일 공간 단락과 실패 규칙이 들어 있다")
    void preambleHasFileSpaceRules() {
        String preamble = artifactService.agentPreamble(conversation);

        assertThat(preamble)
            .contains("[파일 공간]", "file_space_put 도구가 있을 때만", "넣었다고 답하지 않고 까닭을 알린다", "already");
    }


    private void writeFolderFile(String name, byte[] bytes) throws IOException {
        Path folder = store.ensureFolder(conversation.id());
        Files.write(folder.resolve(name), bytes);
    }


    private ChatMessage answer() {
        return messages.save(ChatMessage.fromAssistant(conversation.id(), "넣었어요", null, NOW));
    }


    private static byte[] xlsx(int filler) {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte)filler);
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        return bytes;
    }
}
