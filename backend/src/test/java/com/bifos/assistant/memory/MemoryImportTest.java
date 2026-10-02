package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryImportService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.presentation.MemoryController;
import com.bifos.assistant.memory.presentation.MemoryDocumentController;
import com.bifos.assistant.memory.presentation.MemoryDtos.CreateDocumentRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportItemBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportOutcomeView;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportResponse;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.memory.presentation.MemoryImportController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 묶음을 대조하고 들이는 API 를 확인한다(ADR-058). */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MemoryImportTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    private static final CurrentUser KID = new CurrentUser(2L, "kid@example.com", "kid", 1L, UserRole.MEMBER);
    private static final String MARK = "평문-표식-7391";
    private static final LocalDate DATE = LocalDate.of(2026, 1, 2);

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryImportService imports;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    ContextAssembler context;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MemoryImportController controller;

    @BeforeEach
    void setUp() {
        revisionRepository.deleteAll();
        repository.deleteAll();
        controller = new MemoryImportController(imports, currentUser);
        as(DAD);
    }

    private void as(CurrentUser user) {
        when(currentUser.require()).thenReturn(user);
    }

    private static ImportItemBody memoryItem(String ref, String title, String retrieval, boolean sensitive) {
        return new ImportItemBody(ref, DATE, "core", "MEMORY", null, title, "기억 본문 " + ref, sensitive, retrieval);
    }

    private static ImportItemBody documentItem(String ref, String collection, String key, boolean sensitive) {
        return new ImportItemBody(ref, DATE, collection, "DOCUMENT", key, "문서 " + key, MARK, sensitive, "SEARCH");
    }

    private static ImportItemBody sourceItem(String ref) {
        return new ImportItemBody(ref, DATE, "health", "SOURCE", null, "원문 " + ref, "원문 본문", false, "ARCHIVE");
    }

    private static List<ImportItemBody> threeItems() {
        return List.of(
                memoryItem("private/wiki/sample/note-a.md", "일하는 방식", "ALWAYS", false),
                documentItem("private/wiki/sample/note-b.md", "career", "position-notes", true),
                sourceItem("private/raw/sample/note-c.md"));
    }

    private ImportResponse preview(List<ImportItemBody> items) {
        return controller.preview(new ImportRequest(1, items)).getBody();
    }

    private ImportResponse commit(List<ImportItemBody> items) {
        return controller.commit(new ImportRequest(1, items)).getBody();
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory", Long.class);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static void assertOutcome(ImportOutcomeView view, String status, String reason) {
        assertThat(view.status()).isEqualTo(status);
        assertThat(view.reason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("미리보기는 항목마다 NEW 로 답하고 아무것도 저장하지 않는다")
    void previewSavesNothing() {
        ImportResponse response = preview(threeItems());

        assertThat(response.newCount()).isEqualTo(3);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("들이면 요청자가 주인인 승인된 줄이 출처와 함께 생기고 민감 본문은 암호문이다")
    void commitSavesAcceptedRowsWithSource() {
        ImportResponse response = commit(threeItems());

        assertThat(response.newCount()).isEqualTo(3);
        assertThat(response.items())
                .allSatisfy(view -> assertThat(view.memoryId()).isNotNull());
        assertThat(rowCount()).isEqualTo(3);
        List<ImportItemBody> sent = threeItems();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM memory WHERE id = ?", response.items().get(i).memoryId());
            assertThat(row.get("SCOPE")).isEqualTo("USER");
            assertThat(row.get("OWNER_USER_ID")).isEqualTo(1L);
            assertThat(row.get("STATUS")).isEqualTo("ACCEPTED");
            assertThat(row.get("ACCEPTED_BY_USER_ID")).isEqualTo(1L);
            assertThat(row.get("REVISION")).isEqualTo(1);
            assertThat(row.get("SOURCE_TYPE")).isEqualTo("brain");
            assertThat(row.get("SOURCE_REF")).isEqualTo(sent.get(i).sourceRef());
            assertThat(row.get("SOURCE_DATE").toString()).isEqualTo(DATE.toString());
            assertThat(row.get("ENTRY_TYPE")).isEqualTo(sent.get(i).entryType());
            assertThat(row.get("RETRIEVAL")).isEqualTo(sent.get(i).retrieval());
        }
        Map<String, Object> sensitive = jdbc.queryForMap(
                "SELECT * FROM memory WHERE id = ?", response.items().get(1).memoryId());
        assertThat((String) sensitive.get("CONTENT")).startsWith("v1.").doesNotContain(MARK);
        assertThat(sensitive.get("CONTENT_KEY_ID")).isNotNull();
    }

    @Test
    @DisplayName("같은 묶음을 다시 올리면 모두 DUPLICATE 이고 줄이 늘지 않는다")
    void secondCommitIsDuplicate() {
        commit(threeItems());

        ImportResponse again = commit(threeItems());

        assertThat(again.duplicateCount()).isEqualTo(3);
        assertThat(again.newCount()).isZero();
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 사용자는 같은 묶음을 따로 들인다")
    void otherOwnerImportsSeparately() {
        commit(threeItems());

        as(KID);
        ImportResponse response = commit(threeItems());

        assertThat(response.newCount()).isEqualTo(3);
        assertThat(rowCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("같은 이름의 문서가 이미 있으면 CONFLICT 이고 기존 문서는 그대로다")
    void documentKeyConflict() {
        MemoryDocumentController documents = new MemoryDocumentController(memories, currentUser);
        Long id = documents
                .create(new CreateDocumentRequest("career", "position-notes", "직무 메모", "기존 본문", false))
                .id();

        ImportResponse response =
                commit(List.of(documentItem("private/wiki/sample/note-b.md", "career", "position-notes", false)));

        assertOutcome(response.items().get(0), "CONFLICT", "DOCUMENT_KEY_TAKEN");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM memory WHERE id = ?", id);
        assertThat(row.get("CONTENT")).isEqualTo("기존 본문");
        assertThat(row.get("REVISION")).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 제목의 기억이 이미 있으면 CONFLICT 다")
    void titleConflict() {
        memories.create(DAD, MemoryScope.USER, "일하는 방식", "기존", false);

        ImportResponse response =
                preview(List.of(memoryItem("private/wiki/sample/note-a.md", "일하는 방식", "SEARCH", false)));

        assertOutcome(response.items().get(0), "CONFLICT", "TITLE_TAKEN");
    }

    @Test
    @DisplayName("신원 항목은 아직 거절하고 저장하지 않는다")
    void identityIsHeld() {
        ImportResponse response =
                commit(List.of(documentItem("private/wiki/sample/id.md", "identity", "profile", true)));

        assertOutcome(response.items().get(0), "REJECTED", "IDENTITY_HELD");
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("없는 collection 과 긴 본문은 항목마다 REJECTED 로 답한다")
    void rejectsUnknownCollectionAndLongContent() {
        ImportItemBody unknown = new ImportItemBody(
                "private/wiki/sample/a.md", DATE, "no-such-area", "MEMORY", null, "제목", "본문", false, "SEARCH");
        ImportItemBody longBody = new ImportItemBody(
                "private/wiki/sample/b.md", DATE, "core", "MEMORY", null, "제목2", "가".repeat(12001), false, "SEARCH");

        ImportResponse response = preview(List.of(unknown, longBody));

        assertOutcome(response.items().get(0), "REJECTED", "UNKNOWN_COLLECTION");
        assertOutcome(response.items().get(1), "REJECTED", "CONTENT_TOO_LONG");
    }

    @Test
    @DisplayName("종류에 맞지 않는 꺼내는 방식은 RETRIEVAL_NOT_ALLOWED 다")
    void rejectsRetrievalMismatch() {
        ImportItemBody alwaysSensitive = memoryItem("private/wiki/sample/a.md", "민감 기억", "ALWAYS", true);
        ImportItemBody searchSource = new ImportItemBody(
                "private/raw/sample/b.md", DATE, "health", "SOURCE", null, "원문", "본문", false, "SEARCH");
        ImportItemBody archiveDocument = new ImportItemBody(
                "private/wiki/sample/c.md", DATE, "career", "DOCUMENT", "doc-c", "문서", "본문", false, "ARCHIVE");
        ImportItemBody archiveMemory = memoryItem("private/wiki/sample/d.md", "보관 기억", "ARCHIVE", false);

        ImportResponse response = preview(List.of(alwaysSensitive, searchSource, archiveDocument, archiveMemory));

        assertThat(response.items()).allSatisfy(view -> assertOutcome(view, "REJECTED", "RETRIEVAL_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("틀린 종류와 문서 이름은 INVALID_FIELD 다")
    void rejectsInvalidFields() {
        ImportItemBody badType =
                new ImportItemBody("private/wiki/sample/a.md", DATE, "core", "NOTE", null, "제목", "본문", false, "SEARCH");
        ImportItemBody badKey = documentItem("private/wiki/sample/b.md", "career", "Position_Notes", false);
        ImportItemBody keyOnMemory = new ImportItemBody(
                "private/wiki/sample/c.md", DATE, "core", "MEMORY", "has-key", "제목", "본문", false, "SEARCH");

        ImportResponse response = preview(List.of(badType, badKey, keyOnMemory));

        assertThat(response.items()).allSatisfy(view -> assertOutcome(view, "REJECTED", "INVALID_FIELD"));
    }

    @Test
    @DisplayName("한 묶음에 같은 출처가 둘이면 둘째는 거절하고 한 줄만 저장한다")
    void duplicateSourceInBundle() {
        ImportResponse response = commit(List.of(
                memoryItem("private/wiki/sample/note-a.md", "첫째", "SEARCH", false),
                memoryItem("private/wiki/sample/note-a.md", "둘째", "SEARCH", false)));

        assertOutcome(response.items().get(0), "NEW", null);
        assertOutcome(response.items().get(1), "REJECTED", "DUPLICATE_IN_BUNDLE");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 묶음에 같은 문서 이름이 둘이면 둘째는 거절한다")
    void duplicateDocumentKeyInBundle() {
        ImportResponse response = commit(List.of(
                documentItem("private/wiki/sample/a.md", "career", "same-name", false),
                documentItem("private/wiki/sample/b.md", "career", "same-name", false)));

        assertOutcome(response.items().get(1), "REJECTED", "DUPLICATE_IN_BUNDLE");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("NEW 와 REJECTED 가 섞여도 NEW 만 저장하고 예외가 없다")
    void mixedBundleSavesOnlyNew() {
        ImportResponse response = commit(List.of(
                memoryItem("private/wiki/sample/a.md", "새 기억", "SEARCH", false),
                documentItem("private/wiki/sample/b.md", "identity", "profile", true)));

        assertThat(response.newCount()).isEqualTo(1);
        assertThat(response.rejectedCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("항목이 101개거나 schemaVersion 이 1 이 아니면 요청 전체를 거절한다")
    void rejectsWholeRequest() {
        List<ImportItemBody> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(memoryItem("private/wiki/sample/n" + i + ".md", "기억 " + i, "SEARCH", false));
        }

        assertCode(() -> controller.commit(new ImportRequest(1, tooMany)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.preview(new ImportRequest(2, threeItems())), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.commit(new ImportRequest(2, threeItems())), ErrorCode.VALIDATION_FAILED);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("들인 문서와 원문은 Memory 목록에 없고 들인 기억은 있다")
    void importedRowsInMemoryList() {
        commit(threeItems());
        MemoryController memoryController = new MemoryController(memories, context, currentUser);

        assertThat(memoryController.readable()).extracting(MemoryView::title).containsExactly("일하는 방식");
    }

    @Test
    @DisplayName("들인 ALWAYS 기억은 문맥에 실리고 원문은 실리지 않는다")
    void importedAlwaysMemoryIsAssembled() {
        commit(threeItems());

        String instructions = context.assembleForOwner(DAD).instructions();

        assertThat(instructions).contains("기억 본문 private/wiki/sample/note-a.md");
        assertThat(instructions).doesNotContain("원문 본문").doesNotContain("원문 private/raw/sample/note-c.md");
    }

    @Test
    @DisplayName("들이는 로그에 본문과 출처를 남기지 않는다")
    void logDoesNotLeakContent(CapturedOutput output) {
        commit(threeItems());

        assertThat(output.getAll())
                .contains("memory import userId=1 new=3")
                .doesNotContain(MARK)
                .doesNotContain("note-a");
    }
}
