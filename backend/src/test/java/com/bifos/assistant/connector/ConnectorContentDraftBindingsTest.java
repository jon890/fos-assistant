package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.contentdraft.domain.ContentDraftBindingChoiceException;
import com.bifos.assistant.chat.contentdraft.domain.ContentDraftException;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.contentdraft.application.ConnectorContentDraftBindings;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorSnapshotDeclaration;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ErrorCode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** bindingId 를 생략한 preview 가 요청자 범위의 snapshot 바인딩 하나만 고르는지 본다. */
class ConnectorContentDraftBindingsTest {
    private static final CurrentUser OWNER = new CurrentUser(7L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final ConnectorBindingRepository bindings = mock(ConnectorBindingRepository.class);
    private final ConnectorCatalogCache catalog = mock(ConnectorCatalogCache.class);
    private final UserAccessPolicy users = mock(UserAccessPolicy.class);
    private final ConnectorContentDraftBindings access = new ConnectorContentDraftBindings(bindings, catalog, users);


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


    @Test
    @DisplayName("준비되지 않은 snapshot 바인딩은 세지 않아 준비된 하나를 고른다")
    void ignoresBindingsThatAreNotReady() {
        snapshotConnector("naver", true);
        snapshotConnector("blog-b", true);
        var rows = List.of(binding(1L, 7L, "naver"), binding(4L, 7L, "blog-b", BindingStatus.PENDING));
        when(bindings.findByAgentId(5L)).thenReturn(rows);

        assertThat(access.soleSnapshotBinding(OWNER, 5L)).isEqualTo(1L);
    }


    @Test
    @DisplayName("쓸 수 없는 바인딩은 같은 오류로 거절하되 어느 조건에서 막혔는지 경고로 남긴다")
    void logsWhichBindingConditionRefused() {
        var row = binding(1L, 7L, "naver", BindingStatus.PENDING);
        var agent = mock(Agent.class);
        when(agent.enabled()).thenReturn(true);
        when(agent.isReadableBy(7L)).thenReturn(true);
        when(row.agent()).thenReturn(agent);
        when(users.allowed(7L)).thenReturn(true);
        when(bindings.findByAgentId(5L)).thenReturn(List.of(row));
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        var root = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            assertThatThrownBy(() -> access.requireCurrent(OWNER, 5L, 1L)).isInstanceOfSatisfying(ContentDraftException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_UNAVAILABLE));
            when(row.status()).thenReturn(BindingStatus.READY);
            when(row.restartRequired()).thenReturn(true);
            assertThatThrownBy(() -> access.requireCurrent(OWNER, 5L, 1L)).isInstanceOf(ContentDraftException.class);
            assertThatThrownBy(() -> access.requireCurrent(OWNER, 5L, 2L)).isInstanceOf(ContentDraftException.class);
        } finally {
            root.detachAppender(logs);
        }
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
            "원고 snapshot 을 쓸 수 없다 stage=binding:binding-PENDING",
            "원고 snapshot 을 쓸 수 없다 stage=binding:restart-required",
            "원고 snapshot 을 쓸 수 없다 stage=binding:not-found");
    }


    private void snapshotConnector(String id, boolean declared) {
        var manifest = mock(ConnectorManifest.class);
        when(manifest.snapshot()).thenReturn(declared ? mock(ConnectorSnapshotDeclaration.class) : null);
        when(catalog.find(id)).thenReturn(Optional.of(manifest));
    }


    private static ConnectorBinding binding(Long id, Long userId, String connectorId) {
        return binding(id, userId, connectorId, BindingStatus.READY);
    }


    private static ConnectorBinding binding(Long id, Long userId, String connectorId, BindingStatus status) {
        var connection = mock(ConnectorConnection.class);
        when(connection.userId()).thenReturn(userId);
        when(connection.connectorId()).thenReturn(connectorId);
        when(connection.status()).thenReturn(ConnectionStatus.READY);
        var binding = mock(ConnectorBinding.class);
        when(binding.id()).thenReturn(id);
        when(binding.connection()).thenReturn(connection);
        when(binding.status()).thenReturn(status);
        return binding;
    }
}
