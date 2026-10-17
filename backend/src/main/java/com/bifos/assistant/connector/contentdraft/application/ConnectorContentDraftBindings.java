package com.bifos.assistant.connector.contentdraft.application;

import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.chat.contentdraft.application.port.ContentDraftBindingAccess;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftBindingStamp;
import com.bifos.assistant.chat.contentdraft.domain.ContentDraftBindingChoiceException;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
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
        var manifest = catalog.find(stamp.connectorId()).orElseThrow(() -> ContentDraftSnapshotHash.unavailable("binding:catalog-missing"));
        var declaration = manifest.snapshot();
        if (declaration == null) {
            throw ContentDraftSnapshotHash.unavailable("binding:snapshot-undeclared");
        }
        try {
            var tuple = JSON.createArrayNode().add(declaration.protocol()).add(declaration.compileTool())
                .add(declaration.startTool()).add(declaration.statusTool()).add(true)
                .add(declaration.capabilities().get("start")).add(declaration.capabilities().get("status"));
            tuple.add(JSON.valueToTree(manifest.tools().stream().sorted(Comparator.comparing(tool -> tool.name())).toList()));
            return new ContentDraftBindingStamp(stamp.agentId(), stamp.connectionId(), stamp.bindingId(),
                stamp.connectionUpdatedAt(), stamp.bindingUpdatedAt(), stamp.profile(), stamp.connectorId(),
                ContentDraftSnapshotHash.hash(ContentDraftSnapshotHash.bytes(tuple)));
        } catch (RuntimeException ex) {
            throw ContentDraftSnapshotHash.unavailable("binding:declaration", ex);
        }
    }


    @Override
    @Transactional(readOnly = true)
    public ContentDraftBindingStamp requireCurrent(CurrentUser user, Long agentId, Long bindingId) {
        var binding = bindings.findByAgentId(agentId).stream().filter(row -> row.id().equals(bindingId))
            .findFirst().orElseThrow(() -> ContentDraftSnapshotHash.unavailable("binding:not-found"));
        var agent = binding.agent();
        var connection = binding.connection();
        String refused = refusal(user, binding);
        if (refused != null) {
            throw ContentDraftSnapshotHash.unavailable("binding:" + refused);
        }
        return new ContentDraftBindingStamp(agent.id(), connection.id(), binding.id(), connection.updatedAt(),
            binding.updatedAt(), agent.hermesProfile(), connection.connectorId(), null);
    }


    /** 쓸 수 없는 첫 사유다. 상태 이름은 enum 값이라 그대로 남겨도 된다. 쓸 수 있으면 null 이다. */
    private String refusal(CurrentUser user, ConnectorBinding binding) {
        var agent = binding.agent();
        var connection = binding.connection();
        if (!users.allowed(user.id())) {
            return "user-blocked";
        }
        if (!agent.enabled()) {
            return "agent-disabled";
        }
        if (!agent.isReadableBy(user.id())) {
            return "agent-unreadable";
        }
        if (!user.id().equals(connection.userId())) {
            return "connection-owner";
        }
        if (binding.status() != BindingStatus.READY) {
            return "binding-" + binding.status();
        }
        if (binding.restartRequired()) {
            return "restart-required";
        }
        if (!binding.desiredEnabled()) {
            return "desired-disabled";
        }
        return connection.status() == ConnectionStatus.READY ? null : "connection-" + connection.status();
    }


    @Override
    public Long soleSnapshotBinding(CurrentUser user, Long agentId) {
        var ids = bindings.findByAgentId(agentId).stream()
            .filter(row -> user.id().equals(row.connection().userId()))
            // 준비되지 않은 바인딩은 preview 가 어차피 거절하므로 세지 않는다. 세면 쓸 수 있는 하나가 MANY 로 막힌다.
            .filter(row -> row.status() == BindingStatus.READY && row.connection().status() == ConnectionStatus.READY)
            .filter(row -> catalog.find(row.connection().connectorId()).map(manifest -> manifest.snapshot() != null).orElse(false))
            .map(ConnectorBinding::id).toList();
        if (ids.size() != 1) {
            throw new ContentDraftBindingChoiceException(ids.isEmpty() ? ContentDraftBindingChoiceException.NONE
                : ContentDraftBindingChoiceException.MANY);
        }
        return ids.getFirst();
    }


    @Override
    public void requireUnchanged(CurrentUser user, ContentDraftBindingStamp stamp) {
        var current = requireCurrent(user, stamp.agentId(), stamp.bindingId());
        if (!Objects.equals(current.connectionId(), stamp.connectionId())
            || !current.connectionUpdatedAt().equals(stamp.connectionUpdatedAt())
            || !current.bindingUpdatedAt().equals(stamp.bindingUpdatedAt())
            || !current.profile().equals(stamp.profile()) || !current.connectorId().equals(stamp.connectorId())) {
            throw ContentDraftSnapshotHash.unavailable("binding:changed");
        }
    }
}
