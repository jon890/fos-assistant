package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.EventWatchBinding;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.domain.EventWatchTarget;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorEventWatch;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 바인딩 줄의 사건 감지 두 컬럼을 맡는다(ADR-20261105 / event-watch). 다른 패키지는 그 컬럼을 직접 쓰지 않는다.
 *
 * <p>상태 갱신은 줄을 잠그고 다시 읽은 뒤 쓴다. 확인 스레드와 실행이 끝난 스레드가 같은 줄을 고칠 수 있어서다. 잠그는 동안 Hermes 를
 * 부르지 않는다. 판정에 쓰는 카탈로그는 도구 호출 판정과 같은 {@link ConnectorCatalogCache} 다.
 */
@Service
@RequiredArgsConstructor
public class ConnectorEventWatches {

    private final ConnectorBindingRepository bindings;
    private final ConnectorCatalogCache catalog;


    /**
     * 사건 감지를 켠다. 같은 연결의 바인딩을 모두 잠근 뒤 다른 바인딩이 켜져 있거나, 바인딩이 쓸 수 없거나, 조회 도구가 승인 없이 불리지 않으면
     * {@code PROACTIVE_CHECK_UNAVAILABLE} 로 거절한다. 이미 켜져 있으면 시간대만 바꾸고 지점과 셈은 그대로 둔다.
     *
     * <p>막는 toolset 판정은 {@code EventWatchService} 가 트랜잭션 밖에서 이 메서드 앞에 한다.
     */
    @Transactional
    public EventWatchState enable(Long userId, Long agentId, String connectorId, ZoneId zone, Instant now) {
        ConnectorBinding binding = requireBinding(userId, agentId, connectorId);
        List<ConnectorBinding> locked = bindings.lockByConnectionId(binding.connection().id());
        boolean elsewhere = locked.stream().anyMatch(other -> !other.id().equals(binding.id()) && other.eventWatchEnabled());
        boolean allowed = declaring(connectorId).filter(ConnectorEventWatches::toolAllowed).isPresent();
        if (elsewhere || !usable(binding) || !allowed) {
            throw new ApiException(ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "event watch cannot be enabled on this binding");
        }
        EventWatchState current = binding.eventWatchEnabled() ? EventWatchState.fromJson(binding.eventWatchState()) : null;
        EventWatchState next = current == null
            ? EventWatchState.fresh(zone, now)
            : current.inZone(zone);
        bindings.switchEventWatch(binding.id(), true, next.toJson());
        return next;
    }


    /** 사건 감지를 끈다. 상태도 함께 지워 다시 켜면 켠 시각부터 본다. 꺼져 있으면 아무것도 하지 않는다. */
    @Transactional
    public void disable(Long userId, Long agentId, String connectorId) {
        ConnectorBinding binding = requireBinding(userId, agentId, connectorId);
        bindings.switchEventWatch(binding.id(), false, null);
    }


    /** 그 에이전트에 붙은 그 사용자의 연결마다 사건 감지 사실을 읽는다. 다른 사용자의 연결은 싣지 않는다. */
    @Transactional(readOnly = true)
    public List<EventWatchBinding> bindingsOf(Long userId, Long agentId) {
        return bindings.findByAgentId(agentId).stream()
            .filter(binding -> binding.connection().userId().equals(userId))
            .map(binding -> new EventWatchBinding(
                binding.id(),
                binding.connection().connectorId(),
                usable(binding),
                binding.eventWatchEnabled(),
                bindings.findByConnectionId(binding.connection().id()).stream()
                    .anyMatch(other -> !other.id().equals(binding.id()) && other.eventWatchEnabled()),
                binding.eventWatchState()))
            .toList();
    }


    /** 사건 감지를 켠 바인딩 전부다. */
    @Transactional(readOnly = true)
    public List<EventWatchTarget> targets() {
        return bindings.findEventWatchTargets();
    }


    /**
     * 줄을 잠그고 상태를 다시 읽어 바꾼 뒤 쓴다. 부르는 쪽의 트랜잭션이 있으면 거기에 참여한다.
     *
     * @return 바꾼 상태. 그 사이 꺼졌거나 바인딩을 뗐으면 빈 값이고 아무것도 쓰지 않는다
     */
    @Transactional
    public Optional<EventWatchState> update(Long bindingId, UnaryOperator<EventWatchState> change) {
        Optional<EventWatchState> current = bindings.lockEventWatchState(bindingId).map(EventWatchState::fromJson);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        EventWatchState next = change.apply(current.get());
        bindings.writeEventWatchState(bindingId, next.toJson());
        return Optional.of(next);
    }


    /**
     * 그 커넥터의 사건 감지 선언과 제목이다. 선언하지 않았거나 카탈로그에 없으면 빈 값이다.
     *
     * @throws ApiException {@code CONNECTOR_UNAVAILABLE}. 카탈로그를 읽지 못했을 때
     */
    public Optional<ConnectorManifest> declaring(String connectorId) {
        try {
            return catalog.find(connectorId).filter(manifest -> manifest.eventWatch() != null);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ConnectorErrors.unavailable();
        }
    }


    /** 조회 도구가 살펴보기 트리에서 승인 없이 불리는 도구인가. 선언이 {@code READ} 이고 승인 {@code none} 이어야 한다(REQ10). */
    public static boolean toolAllowed(ConnectorManifest manifest) {
        ConnectorEventWatch watch = manifest.eventWatch();
        return watch != null && ConnectorToolPolicies.find(manifest, watch.tool())
            .filter(policy -> policy.risk() == ToolRisk.READ && policy.approval() == ToolApproval.NONE)
            .isPresent();
    }


    /** 연결과 바인딩이 {@code READY} 이고 켜려는 의도가 있다. */
    private static boolean usable(ConnectorBinding binding) {
        return binding.status() == BindingStatus.READY && binding.desiredEnabled()
            && binding.connection().status() == ConnectionStatus.READY;
    }


    private ConnectorBinding requireBinding(Long userId, Long agentId, String connectorId) {
        return bindings.findByAgentId(agentId).stream()
            .filter(binding -> binding.connection().userId().equals(userId)
                && binding.connection().connectorId().equals(connectorId))
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "no such binding on this agent"));
    }

}
