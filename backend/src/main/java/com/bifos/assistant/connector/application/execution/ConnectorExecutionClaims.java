package com.bifos.assistant.connector.application.execution;

import static com.bifos.assistant.connector.application.execution.ConnectorExecutionCurrent.require;

import com.bifos.assistant.connector.application.model.ConnectorExecutionClaimInput;
import com.bifos.assistant.connector.application.model.ConnectorExecutionClaimResult;
import com.bifos.assistant.connector.application.model.ConnectorExecutionReadContext;
import com.bifos.assistant.connector.application.model.ConnectorExecutionTicketPayload;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 서명을 먼저 인증하고 현재 권한 맥락을 확인한 뒤 DB에서 영구 소비한다. */
@Service
@RequiredArgsConstructor
public class ConnectorExecutionClaims {
    private final ConnectorExecutionTicket tickets;
    private final ConnectorExecutionClaimCodec codec;
    private final ConnectorExecutionCurrent current;
    private final AppUserRepository users;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    public ConnectorExecutionClaimResult claim(ConnectorExecutionClaimInput input) {
        ConnectorExecutionCurrent.requireNoTransaction();
        var payload = tickets.authenticate(input.ticket());
        // 서명한 도구와 실행 원문 해시는 요청 값과 같아야 한다.
        require(input.tool().equals(payload.tool()) && input.argsSha256().equals(payload.argsSha256()));
        var context = current.readContext(payload.actionId());
        requirePayload(payload, context);
        var catalog = current.readCatalog(context);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                users.findByIdForUpdate(payload.userId()).orElseThrow(ConnectorExecutionCurrent::rejected);
                var action = actions.findByPublicIdForUpdate(payload.actionId())
                        .orElseThrow(ConnectorExecutionCurrent::rejected);
                var row =
                        contents.findByActionIdForUpdate(action.id()).orElseThrow(ConnectorExecutionCurrent::rejected);
                var snapshot = current.validateLocked(context, catalog, action, row);
                requirePayload(payload, context);
                var now = clock.instant();
                tickets.requireExecutable(action, now);
                tickets.requireTime(payload, now);
                require(payload.ticketId().equals(row.ticketId())
                        && payload.expiresAt().equals(row.ticketExpiresAt())
                        && !payload.expiresAt().isAfter(action.expiresAt())
                        && payload.argsSha256().equals(snapshot.executionArgsSha256())
                        && payload.scopeSha256().equals(snapshot.scopeSha256())
                        && codec.readScope(snapshot.scopeJson()).equals(input.scope()));
                row.consumeTicket(payload.ticketId(), now.truncatedTo(ChronoUnit.MICROS));
                tickets.beforeCommit(action, payload);
                return new ConnectorExecutionClaimResult(1, true, payload.ticketId(), payload.expiresAt());
            });
        } catch (RuntimeException ignored) {
            throw ConnectorExecutionCurrent.rejected();
        }
    }

    private static void requirePayload(ConnectorExecutionTicketPayload payload, ConnectorExecutionReadContext context) {
        require(payload.actionId().equals(context.actionPublicId())
                && payload.userId().equals(context.userId())
                && payload.agentId().equals(context.agentId())
                && payload.connectionId().equals(context.connectionId())
                && payload.bindingId().equals(context.bindingId())
                && payload.profile().equals(context.profile())
                && payload.connectorId().equals(context.connectorId())
                && payload.tool().equals(context.tool()));
    }
}
