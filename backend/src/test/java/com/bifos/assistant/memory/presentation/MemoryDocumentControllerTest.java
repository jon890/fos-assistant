package com.bifos.assistant.memory.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.presentation.MemoryDtos.CollectionView;
import com.bifos.assistant.memory.presentation.MemoryDtos.DocumentSummaryView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 문서 API 의 응답 모양을 Spring 과 DB 없이 확인한다(ADR-057). */
class MemoryDocumentControllerTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN);


    @Test
    @DisplayName("collection 목록은 요청자 그룹의 목록을 key 와 이름으로 그대로 낸다")
    void collectionsFollowTheRequestersGroupList() {
        MemoryService memories = mock(MemoryService.class);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.require()).thenReturn(DAD);
        when(memories.collectionsFor(DAD)).thenReturn(MemoryCollection.DEFAULTS);

        assertThat(new MemoryDocumentController(memories, currentUser).collections())
            .extracting(CollectionView::key, CollectionView::displayName)
            .contains(tuple("identity", "신원"))
            .hasSize(MemoryCollection.DEFAULTS.size());
    }


    @Test
    @DisplayName("문서 목록의 항목에는 본문 칸이 없다")
    void documentSummaryHasNoContentComponent() {
        assertThat(DocumentSummaryView.class.getRecordComponents())
            .extracting(component -> component.getName())
            .doesNotContain("content");
    }
}
