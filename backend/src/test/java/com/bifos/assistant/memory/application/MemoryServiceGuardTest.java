package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 저장소에 닿기 전에 거절하는 입력 검증과 권한 판정을 Spring 과 DB 없이 확인한다. 저장소를 mock 으로 두고, 거절한 요청이 저장소에
 * 쓰지 않았는지도 본다.
 */
class MemoryServiceGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final CurrentUser ADMIN = user(1L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, UserRole.MEMBER);

    private final MemoryRepository memories = mock(MemoryRepository.class);
    private final MemoryRevisionWriter revisionWriter = mock(MemoryRevisionWriter.class);
    private final MemoryTexts texts = mock(MemoryTexts.class);
    private final MemoryCollectionService collections = mock(MemoryCollectionService.class);
    private final MemoryService service = new MemoryService(
        memories,
        mock(MemoryRevisionRepository.class),
        mock(AgentMemoryCollectionService.class),
        collections,
        texts,
        revisionWriter,
        mock(MemoryRelevanceSearch.class),
        mock(DecisionFeedbackRecorder.class),
        Clock.fixed(NOW, ZoneOffset.UTC));


    @Test
    @DisplayName("scope가 없으면 거절하고 저장하지 않는다")
    void rejectsMissingScope() {
        assertCode(() -> service.create(ADMIN, null, "제목", "내용", false), ErrorCode.MEMORY_SCOPE_REQUIRED);

        verifyNoInteractions(texts, memories);
    }


    @ParameterizedTest
    @ValueSource(strings = {"", "Core", "커리어", "a b", "-core"})
    @DisplayName("collection key 가 형식에 맞지 않으면 만들지 못한다")
    void rejectsMalformedCollectionKeys(String key) {
        assertCode(
            () -> service.create(ADMIN, MemoryScope.USER, "제목", "내용", key, MemoryRetrieval.SEARCH),
            ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(texts);
    }


    @Test
    @DisplayName("MEMBER 역할은 그룹 항목을 만들거나 고치거나 지우지 못한다")
    void memberCannotCreateEditOrDeleteGroupItems() {
        Memory group = Memory.accepted(
            MemoryScope.GROUP, null, MEMBER.groupId(), "그룹", "내용", MemoryPlacement.core(MemoryRetrieval.SEARCH), ADMIN.id(), NOW);
        when(memories.findByIdForUpdate(7L)).thenReturn(Optional.of(group));

        assertCode(() -> service.create(MEMBER, MemoryScope.GROUP, "새 그룹", "내용", false), ErrorCode.FORBIDDEN);
        assertCode(() -> service.update(MEMBER, 7L, "수정", true, null), ErrorCode.FORBIDDEN);
        assertCode(() -> service.delete(MEMBER, 7L), ErrorCode.FORBIDDEN);

        verifyNoInteractions(texts, revisionWriter);
        verify(memories, never()).delete(any(Memory.class));
    }


    @ParameterizedTest
    @ValueSource(strings = {"", "Application_Profile", "-profile", "한글"})
    @DisplayName("문서 이름의 모양이 틀리면 거절하고 저장하지 않는다")
    void rejectsMalformedDocumentKeys(String documentKey) {
        assertCode(() -> service.createDocument(ADMIN, "identity", documentKey, "제목", "본문"), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(texts);
    }


    @Test
    @DisplayName("문서 이름은 128자까지다")
    void rejectsDocumentKeysLongerThan128() {
        assertCode(
            () -> service.createDocument(ADMIN, "identity", "a".repeat(129), "제목", "본문"), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(texts);
    }


    @Test
    @DisplayName("문서의 collection 이 그룹 목록에 없거나 그룹이 없으면 거절하고 저장하지 않는다")
    void rejectsDocumentOutsideTheGroupCollections() {
        when(collections.collectionsOf(MEMBER.groupId())).thenReturn(MemoryCollection.DEFAULTS);

        assertCode(() -> service.createDocument(MEMBER, "no-such-area", "profile", "제목", "본문"), ErrorCode.VALIDATION_FAILED);
        assertCode(
            () -> service.createDocument(user(9L, null, UserRole.MEMBER), "identity", "profile", "제목", "본문"),
            ErrorCode.VALIDATION_FAILED);

        assertThat(service.collectionsFor(user(9L, null, UserRole.MEMBER))).isEqualTo(List.of());
        verifyNoInteractions(texts);
        verify(memories, never()).findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(any(), any(), any(), any());
    }


    private static CurrentUser user(Long id, UserRole role) {
        return user(id, 10L, role);
    }


    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
    }


    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
