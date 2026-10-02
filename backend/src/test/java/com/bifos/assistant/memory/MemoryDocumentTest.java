package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.presentation.MemoryController;
import com.bifos.assistant.memory.presentation.MemoryDocumentController;
import com.bifos.assistant.memory.presentation.MemoryDtos.CollectionView;
import com.bifos.assistant.memory.presentation.MemoryDtos.CreateDocumentRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.DocumentSummaryView;
import com.bifos.assistant.memory.presentation.MemoryDtos.DocumentView;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.memory.presentation.MemoryDtos.UpdateDocumentRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 사용자가 문서를 쓰고 고치는 API 와 문서를 기존 Memory 경로에서 떼어 두는 것을 확인한다(ADR-057). */
@SpringBootTest
@ActiveProfiles("test")
class MemoryDocumentTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    private static final CurrentUser KID = new CurrentUser(2L, "kid@example.com", "kid", 1L, UserRole.MEMBER);
    private static final String MARK = "평문-표식-7391";
    private static final String NAME = "application-profile";

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    ContextAssembler context;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MemoryDocumentController controller;

    @BeforeEach
    void setUp() {
        revisionRepository.deleteAll();
        repository.deleteAll();
        controller = new MemoryDocumentController(memories, currentUser);
        as(DAD);
    }

    private void as(CurrentUser user) {
        when(currentUser.require()).thenReturn(user);
    }

    private DocumentView createSensitive() {
        return controller.create(new CreateDocumentRequest("identity", NAME, "지원서 공통 프로필", MARK, true));
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT * FROM memory WHERE id = ?", id);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("민감 문서를 만들면 곧 승인되고 본문은 암호문으로 저장된다")
    void createsAcceptedSensitiveDocument() {
        DocumentView created = createSensitive();

        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.sensitive()).isTrue();
        assertThat(created.content()).isEqualTo(MARK);
        Map<String, Object> stored = row(created.id());
        assertThat(stored.get("ENTRY_TYPE")).isEqualTo("DOCUMENT");
        assertThat(stored.get("STATUS")).isEqualTo("ACCEPTED");
        assertThat(stored.get("RETRIEVAL")).isEqualTo("SEARCH");
        assertThat(stored.get("SCOPE")).isEqualTo("USER");
        assertThat((String) stored.get("CONTENT")).doesNotContain(MARK);
    }

    @Test
    @DisplayName("같은 이름으로 한 번 더 만들면 거절하고 다른 주인은 같은 이름을 쓴다")
    void rejectsDuplicateNameButAllowsOtherOwner() {
        createSensitive();

        assertCode(this::createSensitive, ErrorCode.MEMORY_DOCUMENT_EXISTS);

        as(KID);
        assertThat(createSensitive().id()).isNotNull();
    }

    @Test
    @DisplayName("남의 문서는 읽지도 고치지도 못한다")
    void hidesOthersDocument() {
        Long id = createSensitive().id();

        as(KID);
        assertCode(() -> controller.get(id), ErrorCode.MEMORY_NOT_FOUND);
        assertCode(() -> controller.update(id, new UpdateDocumentRequest("x", true, 1)), ErrorCode.MEMORY_NOT_FOUND);
    }

    @Test
    @DisplayName("문서 목록은 자기 문서만 내고 본문 칸이 없다")
    void listsOwnDocumentsWithoutContent() {
        createSensitive();
        as(KID);
        createSensitive();
        as(DAD);

        List<DocumentSummaryView> list = controller.list();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).documentKey()).isEqualTo(NAME);
        assertThat(DocumentSummaryView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("content");
    }

    @Test
    @DisplayName("판 번호를 견줘 고치고 낡은 판 번호는 거절한다")
    void revisesWithExpectedRevision() {
        Long id = createSensitive().id();

        DocumentView revised = controller.update(id, new UpdateDocumentRequest("평문-표식-8802", true, 1));

        assertThat(revised.revision()).isEqualTo(2);
        assertThat(controller.get(id).content()).isEqualTo("평문-표식-8802");
        assertThat(jdbc.queryForObject(
                        "SELECT change_type FROM memory_revision WHERE memory_id = ? AND revision = 1",
                        String.class,
                        id))
                .isEqualTo("UPDATED");

        long before = revisionRepository.count();
        assertCode(
                () -> controller.update(id, new UpdateDocumentRequest("평문-표식-9913", true, 1)),
                ErrorCode.MEMORY_REVISION_CONFLICT);
        assertThat(revisionRepository.count()).isEqualTo(before);
        assertThat(controller.get(id).content()).isEqualTo("평문-표식-8802");
    }

    @Test
    @DisplayName("이름과 collection 의 모양이 틀리면 거절한다")
    void validatesNameAndCollection() {
        assertCode(
                () -> controller.create(new CreateDocumentRequest("identity", "Application_Profile", "t", "c", false)),
                ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> controller.create(new CreateDocumentRequest("no-such-area", NAME, "t", "c", false)),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("문서는 Memory 목록에 나오지 않는다")
    void documentIsNotListedAsMemory() {
        Long id = createSensitive().id();
        MemoryController memoryController = new MemoryController(memories, context, currentUser);

        assertThat(memoryController.readable()).extracting(MemoryView::id).doesNotContain(id);
    }

    @Test
    @DisplayName("Memory 의 수정과 승인과 거절은 문서를 찾지 못한다")
    void memoryPathsDoNotTouchDocuments() {
        Long sensitiveId = createSensitive().id();
        Long plainId = controller
                .create(new CreateDocumentRequest("career", "position-notes", "직무 메모", "일반 글", false))
                .id();

        for (Long id : List.of(sensitiveId, plainId)) {
            assertCode(() -> memories.update(DAD, id, "x", true), ErrorCode.MEMORY_NOT_FOUND);
            assertCode(() -> memories.reject(DAD, id), ErrorCode.MEMORY_NOT_FOUND);
            assertCode(() -> memories.accept(DAD, id), ErrorCode.MEMORY_NOT_FOUND);
            Map<String, Object> stored = row(id);
            assertThat(stored.get("RETRIEVAL")).isEqualTo("SEARCH");
            assertThat(stored.get("STATUS")).isEqualTo("ACCEPTED");
        }
    }

    @Test
    @DisplayName("문서를 지운 뒤 같은 이름으로 다시 만들 수 있고 지운 판의 본문은 암호문이다")
    void deleteThenRecreate() {
        Long id = createSensitive().id();

        memories.delete(DAD, id);

        assertThat(createSensitive().id()).isNotEqualTo(id);
        String deleted = jdbc.queryForObject(
                "SELECT content FROM memory_revision WHERE memory_id = ? AND change_type = 'DELETED'",
                String.class,
                id);
        assertThat(deleted).doesNotContain(MARK);
    }

    @Test
    @DisplayName("collection 목록에 identity 가 들어 있다")
    void collectionsIncludeIdentity() {
        assertThat(controller.collections()).extracting(CollectionView::key).contains("identity");
    }

    @Test
    @DisplayName("민감 문서의 꺼내는 방식은 요청으로 정하지 못하고 늘 SEARCH 다")
    void documentRetrievalIsAlwaysSearch() {
        Memory memory = memories.createDocument(DAD, "identity", NAME, "제목", MARK, MemorySensitivity.SENSITIVE);

        assertThat(memory.retrieval().name()).isEqualTo("SEARCH");
        assertThat(memory.scope()).isEqualTo(MemoryScope.USER);
    }
}
