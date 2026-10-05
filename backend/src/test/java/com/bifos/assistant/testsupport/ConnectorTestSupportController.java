package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 검사에서 예전 방식의 연결 에이전트를 만든다.
 *
 * <p>연결 등록은 더는 커넥터마다 에이전트를 만들지 않는다(ADR-083). 옛 커넥터 에이전트는 운영의 마이그레이션이 남긴 것뿐이라
 * 브라우저 검사가 만들 길이 없어, 운영 코드에 문을 더하지 않고 여기서 저장소로 직접 넣는다. 모양은 마이그레이션이 옛 연결마다
 * 채운 것과 같다. 에이전트는 Control Plane 이 profile 을 만든 비공개 에이전트이고, 연결은 값이 보관 파일에 있는 {@code READY}
 * 이며, 그 둘의 바인딩은 {@code READY} 이고 서버 이름이 비어 있다.
 */
@RestController
@RequestMapping("/api/v1/test-support/connector")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class ConnectorTestSupportController {

    private final AppUserRepository users;
    private final AgentLifecycleService lifecycle;
    private final AgentRepository agents;
    private final ConnectorConnectionRepository connections;
    private final ConnectorBindingRepository bindings;
    private final HermesConnectorClient connector;
    private final Clock clock;

    /**
     * 그 사용자의 옛 커넥터 에이전트를 하나 만들고 에이전트 번호를 돌려준다. 에이전트 번호는 화면 주소에 쓰는 코드다.
     *
     * <p>그 사용자에게 그 커넥터의 연결이 이미 있으면 그 연결을 다시 {@code READY} 로 두고 쓴다. 연결은 사용자와 커넥터마다
     * 하나다. 없는 사용자면 404 다. 에이전트 이름은 카탈로그의 커넥터 이름이다.
     */
    @PostMapping("/legacy-agent")
    @Transactional
    public ResponseEntity<LegacyAgent> legacyAgent(@RequestBody LegacyAgentRequest request) {
        String connectorId = Objects.requireNonNull(request.connectorId(), "connectorId");
        AppUser owner = users.findByEmail(Objects.requireNonNull(request.email(), "email"))
                .orElse(null);
        if (owner == null) {
            return ResponseEntity.notFound().build();
        }
        String title = connector.readCatalog().stream()
                .filter(manifest -> manifest.id().equals(connectorId))
                .map(ConnectorManifest::title)
                .findFirst()
                .orElse(connectorId);
        CurrentUser user =
                new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
        Agent agent = lifecycle.create(user, title, AgentVisibility.PRIVATE);
        agent.markConnectorManaged();
        Instant now = clock.instant();
        ConnectorConnection connection = connections
                .findByUserIdAndConnectorId(owner.id(), connectorId)
                .orElseGet(() -> ConnectorConnection.pending(owner.id(), connectorId, now));
        connection.connected(ConnectionFields.empty(), now);
        connection = connections.save(connection);
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, null, now);
        binding.installed(false, now);
        // 옛 에이전트는 바인딩이 쓸 수 있을 때만 켜진다. READY 로 두어 지금처럼 도는 옛 에이전트를 만든다.
        binding.ready(now);
        bindings.save(binding);
        agents.save(agent);
        return ResponseEntity.ok(new LegacyAgent(agent.code()));
    }

    /** 옛 에이전트의 주인 메일과 그 에이전트가 쓰던 커넥터다. */
    public record LegacyAgentRequest(String email, String connectorId) {}

    /** 만든 옛 에이전트의 코드다. */
    public record LegacyAgent(String code) {}
}
