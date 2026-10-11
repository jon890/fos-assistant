package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ContentDraftBindingAccess;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import java.util.Comparator;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** chat이 커넥터 엔티티와 카탈로그를 알지 않도록 권한을 stamp로 전달한다. */
@Component
@RequiredArgsConstructor
public class ConnectorContentDraftBindings implements ContentDraftBindingAccess {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorBindingRepository bindings;
    private final ConnectorCatalogCache catalog;
    private final UserAccessPolicy users;

    @Override
    public ContentDraftBindingStamp prepare(CurrentUser user, Long agentId, Long bindingId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("snapshot 선언 준비는 트랜잭션 밖에서 시작합니다.");
        }
        var stamp = requireCurrent(user, agentId, bindingId);
        try {
        var manifest = catalog.find(stamp.connectorId()).orElseThrow(ContentDraftSnapshotHash::unavailable);
        var declaration = manifest.snapshot();
        if (declaration == null) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        var tuple = JSON.createArrayNode().add(declaration.protocol()).add(declaration.compileTool())
            .add(declaration.startTool()).add(declaration.statusTool()).add(true).add(false).add(false);
        tuple.add(JSON.valueToTree(manifest.tools().stream().sorted(Comparator.comparing(tool -> tool.name())).toList()));
        return new ContentDraftBindingStamp(stamp.agentId(), stamp.connectionId(), stamp.bindingId(),
            stamp.connectionUpdatedAt(), stamp.bindingUpdatedAt(), stamp.profile(), stamp.connectorId(),
            ContentDraftSnapshotHash.hash(ContentDraftSnapshotHash.bytes(tuple)));
        } catch (RuntimeException ignored) {
            throw ContentDraftSnapshotHash.unavailable();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ContentDraftBindingStamp requireCurrent(CurrentUser user, Long agentId, Long bindingId) {
        var binding = bindings.findByAgentId(agentId).stream().filter(row -> row.id().equals(bindingId))
            .findFirst().orElseThrow(ContentDraftSnapshotHash::unavailable);
        var agent = binding.agent();
        var connection = binding.connection();
        if (!users.allowed(user.id()) || !agent.enabled() || !agent.isReadableBy(user.id())
            || !user.id().equals(connection.userId()) || binding.status() != BindingStatus.READY
            || binding.restartRequired() || !binding.desiredEnabled() || connection.status() != ConnectionStatus.READY) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        return new ContentDraftBindingStamp(agent.id(), connection.id(), binding.id(), connection.updatedAt(),
            binding.updatedAt(), agent.hermesProfile(), connection.connectorId(), null);
    }

    @Override
    public void requireUnchanged(CurrentUser user, ContentDraftBindingStamp stamp) {
        var current = requireCurrent(user, stamp.agentId(), stamp.bindingId());
        if (!Objects.equals(current.connectionId(), stamp.connectionId())
            || !current.connectionUpdatedAt().equals(stamp.connectionUpdatedAt())
            || !current.bindingUpdatedAt().equals(stamp.bindingUpdatedAt())
            || !current.profile().equals(stamp.profile()) || !current.connectorId().equals(stamp.connectorId())) {
            throw ContentDraftSnapshotHash.unavailable();
        }
    }
}
