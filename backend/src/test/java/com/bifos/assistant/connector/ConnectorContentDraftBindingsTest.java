package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.ContentDraftBindingChoiceException;
import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.application.ConnectorContentDraftBindings;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorSnapshotDeclaration;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** bindingId 를 생략한 preview 가 요청자 범위의 snapshot 바인딩 하나만 고르는지 본다. */
class ConnectorContentDraftBindingsTest {
    private static final CurrentUser OWNER = new CurrentUser(7L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final ConnectorBindingRepository bindings = mock(ConnectorBindingRepository.class);
    private final ConnectorCatalogCache catalog = mock(ConnectorCatalogCache.class);
    private final ConnectorContentDraftBindings access = new ConnectorContentDraftBindings(bindings, catalog, mock(UserAccessPolicy.class));


    @Test
    @DisplayName("요청자 연결의 snapshot 바인딩이 하나면 그 번호이고 다른 사용자와 snapshot 없는 커넥터는 세지 않는다")
    void choosesOnlyOwnersSnapshotBinding() {
        snapshotConnector("naver", true);
        snapshotConnector("notes", false);
        var rows = List.of(binding(1L, 7L, "naver"), binding(2L, 8L, "naver"), binding(3L, 7L, "notes"));
        when(bindings.findByAgentId(5L)).thenReturn(rows);

        assertThat(access.soleSnapshotBinding(OWNER, 5L)).isEqualTo(1L);
    }


    @Test
    @DisplayName("snapshot 바인딩이 없거나 여럿이면 각각의 고정 까닭으로 거절한다")
    void rejectsNoneOrMany() {
        snapshotConnector("naver", true);
        when(bindings.findByAgentId(5L)).thenReturn(List.of());
        assertThatThrownBy(() -> access.soleSnapshotBinding(OWNER, 5L)).isInstanceOf(ContentDraftBindingChoiceException.class)
            .hasMessage(ContentDraftBindingChoiceException.NONE);
        var rows = List.of(binding(1L, 7L, "naver"), binding(4L, 7L, "naver"));
        when(bindings.findByAgentId(5L)).thenReturn(rows);
        assertThatThrownBy(() -> access.soleSnapshotBinding(OWNER, 5L)).isInstanceOf(ContentDraftBindingChoiceException.class)
            .hasMessage(ContentDraftBindingChoiceException.MANY);
    }


    private void snapshotConnector(String id, boolean declared) {
        var manifest = mock(ConnectorManifest.class);
        when(manifest.snapshot()).thenReturn(declared ? mock(ConnectorSnapshotDeclaration.class) : null);
        when(catalog.find(id)).thenReturn(Optional.of(manifest));
    }


    private static ConnectorBinding binding(Long id, Long userId, String connectorId) {
        var connection = mock(ConnectorConnection.class);
        when(connection.userId()).thenReturn(userId);
        when(connection.connectorId()).thenReturn(connectorId);
        var binding = mock(ConnectorBinding.class);
        when(binding.id()).thenReturn(id);
        when(binding.connection()).thenReturn(connection);
        return binding;
    }
}
