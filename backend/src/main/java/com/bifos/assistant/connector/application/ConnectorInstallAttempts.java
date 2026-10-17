package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.model.ConnectorBindingAttempt;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionGuard;
import com.bifos.assistant.connector.application.model.ConnectorInstallResult;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.port.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설치 시작을 커밋한 뒤 외부 호출을 보내고 현재 시도에만 결과를 적는다.
 *
 * <p>권한과 업무별 시작 조건은 호출자가 정한다. 시작과 결과 트랜잭션은 같은 소유 사용자 → 에이전트 잠금 순서를 따른다.
 * 결과 저장이 실패해도 시작의 PENDING과 시도 UUID는 남아 다음 연결 확인이 복구한다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConnectorInstallAttempts {
    private final ConnectorBindingRepository bindings;
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final ConnectorBindingInstalls installs;
    private final SignInRevocation access;
    private final TransactionTemplate transactions;
    private final ConnectorExecutionGuard guards;


    /** 호출자가 잠금과 권한 검사를 마친 뒤 불변 입력만 반환하도록 시작 트랜잭션을 연다. 상위 트랜잭션은 허용하지 않는다. */
    public <T> T start(Supplier<T> preparation) {
        requireOutsideTransaction();
        return transactions.execute(status -> preparation.get());
    }


    /** 소유자 행을 첫 읽기로 잠그고 현재 로그인 접근 권한을 확인한다. */
    public AppUser lockOwner(Long ownerId) {
        AppUser owner = users.findByIdForUpdate(ownerId)
            .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
        if (access.revoked(owner.email())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "sign-in access was revoked");
        }
        return owner;
    }


    /** 시작 트랜잭션 안에서 바인딩의 새 시도와 HTTP에 필요한 값을 함께 캡처한다. */
    public ConnectorBindingAttempt begin(
        ConnectorBinding binding, Optional<ConnectorManifest> manifest,
        boolean checkInstallation, boolean afterRestart, boolean replaceValues, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("installation preparation requires a transaction");
        }
        Agent agent = agents.findByIdForUpdate(binding.agent().id())
            .filter(candidate -> !candidate.isDeleted())
            .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        binding = bindings.findById(binding.id()).orElseThrow();
        ConnectorConnection connection = binding.connection();
        if (!Objects.equals(agent.ownerUserId(), connection.userId())) {
            throw new ApiException(ErrorCode.AGENT_BUSY, "the agent owner changed while preparing installation");
        }
        ConnectorManifest declared = manifest.orElse(null);
        String protocol = null;
        String manifestSha = null;
        if (declared != null && declared.executionGuard() != null && !declared.executionGuard().isNull()) {
            protocol = guards.read(declared).protocol();
            manifestSha = declared.executionGuardManifestSha256();
            if (manifestSha == null || !manifestSha.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid execution guard manifest hash");
            }
        }
        if (binding.mcpServer() == null && declared != null) {
            binding.recordServer(declared.mcpServer());
        }
        ResyncOutcome waiting = null;
        if (checkInstallation) {
            if (declared == null) {
                waiting = ResyncOutcome.CATALOG_MISSING;
            } else if (!afterRestart && binding.restartRequired()) {
                waiting = ResyncOutcome.RESTART_PENDING;
            } else if (binding.applyDueAt() != null && binding.applyDueAt().isAfter(now)) {
                waiting = ResyncOutcome.APPLY_SCHEDULED;
            }
        }
        UUID attemptId = UUID.randomUUID();
        if (waiting == null) {
            binding.beginInstall(attemptId, now);
        } else {
            binding.recordInstallAttempt(attemptId, now);
        }
        bindings.save(binding);
        return new ConnectorBindingAttempt(
            binding.id(), agent.id(), connection.userId(), connection.id(), connection.updatedAt(),
            connection.status(), connection.vaultStored(), attemptId, now,
            agent.hermesProfile(), agent.apiBaseUrl(), agent.credentialScope(), agent.sandboxOwner(),
            connection.connectorId(), protocol, manifestSha, connection.vault(),
            declared == null ? null : installs.ownerBrowser(binding, declared), binding.mcpServer(),
            binding.restartRequired(), checkInstallation, replaceValues, waiting,
            declared == null ? List.of() : declared.tools().stream().map(ConnectorTool::name).toList());
    }


    /** 커밋된 입력으로 HTTP를 보내고, 적용된 결과만 돌려준다. 버린 응답은 오류와 횟수에 넣지 않는다. */
    public Optional<ResyncOutcome> execute(ConnectorBindingAttempt attempt) {
        requireOutsideTransaction();
        ConnectorInstallResult result = installs.execute(attempt);
        Optional<ResyncOutcome> applied = complete(attempt, result);
        applied.filter(outcome -> attempt.checkInstallation()
            && outcome != ResyncOutcome.READY && outcome != ResyncOutcome.CALL_FAILED)
            .ifPresent(outcome -> log.info("connector {} not applied: {}", attempt.connectorId(), outcome));
        return applied;
    }


    /** 결과 트랜잭션에서 현재 설치 맥락과 UUID를 대조하고 한 번만 적용한다. 연결 revision은 변경하지 않는다. */
    public Optional<ResyncOutcome> complete(ConnectorBindingAttempt attempt, ConnectorInstallResult result) {
        requireOutsideTransaction();
        return transactions.execute(status -> {
            ConnectorBinding current = current(attempt);
            if (current == null || !current.consumeInstall(attempt.attemptId())) {
                return Optional.empty();
            }
            installs.record(current, attempt, result);
            bindings.save(current);
            return Optional.of(result.outcome());
        });
    }


    /** 새 붙이기가 아무것도 설치하지 않고 거절됐을 때 그 시도의 행만 지운다. 새 시도와 다른 바인딩은 건드리지 않는다. */
    public boolean reject(ConnectorBindingAttempt attempt) {
        requireOutsideTransaction();
        return Boolean.TRUE.equals(transactions.execute(status -> {
            ConnectorBinding current = current(attempt);
            if (current == null || !current.consumeInstall(attempt.attemptId())) {
                return false;
            }
            bindings.delete(current);
            return true;
        }));
    }


    /** 첫 DB 읽기는 소유자 잠금이다. MySQL에서 잠금을 기다린 뒤의 값을 읽도록 엔티티를 먼저 찾지 않는다. */
    private ConnectorBinding current(ConnectorBindingAttempt attempt) {
        AppUser owner = users.findByIdForUpdate(attempt.ownerId()).orElse(null);
        if (owner == null) {
            return null;
        }
        if (access.revoked(owner.email())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "sign-in access was revoked");
        }
        Agent agent = agents.findByIdForUpdate(attempt.agentId()).orElse(null);
        if (agent == null || agent.isDeleted()
            || !Objects.equals(agent.ownerUserId(), attempt.ownerId())
            || !Objects.equals(agent.hermesProfile(), attempt.profile())
            || !Objects.equals(agent.apiBaseUrl(), attempt.apiBaseUrl())
            || !Objects.equals(agent.sandboxOwner(), attempt.sandboxOwner())
            || agent.credentialScope() != attempt.credentialScope()
            || agent.visibility() != AgentVisibility.PRIVATE) {
            return null;
        }
        ConnectorBinding binding = bindings.findById(attempt.bindingId()).orElse(null);
        if (binding == null || !Objects.equals(binding.agent().id(), attempt.agentId())) {
            return null;
        }
        ConnectorConnection connection = binding.connection();
        return Objects.equals(connection.id(), attempt.connectionId())
            && Objects.equals(connection.userId(), attempt.ownerId())
            && Objects.equals(connection.updatedAt(), attempt.connectionRevision())
            && connection.status() == attempt.connectionStatus()
            && connection.vaultStored() == attempt.vaultStored()
            && Objects.equals(binding.installAttemptId(), attempt.attemptId())
                ? binding : null;
    }


    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("installation HTTP must not retain an outer transaction");
        }
    }
}
