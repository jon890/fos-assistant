package com.bifos.assistant.connector.application.execution;

import static com.bifos.assistant.connector.application.execution.ConnectorExecutionCurrent.require;

import com.bifos.assistant.connector.application.model.ConnectorExecutionTicketPayload;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 운영 승인에 연결하지 않은 일회성 발급 함수와 서명 인증이다. */
@Service
@RequiredArgsConstructor
public class ConnectorExecutionTicket {
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private final ConnectorExecutionCurrent current;
    private final ConnectorExecutionClaimCodec codec;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final AppUserRepository users;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final HermesProperties properties;

    public String issue(UUID actionPublicId) {
        ConnectorExecutionCurrent.requireNoTransaction();
        var context = current.readContext(actionPublicId);
        var catalog = current.readCatalog(context);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                users.findByIdForUpdate(context.userId()).orElseThrow(ConnectorExecutionCurrent::rejected);
                var action = actions.findByPublicIdForUpdate(actionPublicId)
                        .orElseThrow(ConnectorExecutionCurrent::rejected);
                var row =
                        contents.findByActionIdForUpdate(action.id()).orElseThrow(ConnectorExecutionCurrent::rejected);
                var snapshot = current.validateLocked(context, catalog, action, row);
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                requireExecutable(action, now);
                Instant expiry = action.expiresAt().truncatedTo(ChronoUnit.MICROS);
                if (expiry.isAfter(now.plusSeconds(60))) {
                    expiry = now.plusSeconds(60);
                }
                require(now.isBefore(expiry));
                UUID id = UUID.randomUUID();
                var payload = new ConnectorExecutionTicketPayload(
                        1,
                        id,
                        action.publicId(),
                        action.userId(),
                        action.agentId(),
                        row.connectionId(),
                        row.bindingId(),
                        context.profile(),
                        action.connectorId(),
                        action.toolName(),
                        snapshot.executionArgsSha256(),
                        snapshot.scopeSha256(),
                        now,
                        expiry);
                String ticket = sign(payload);
                row.issueTicket(id, expiry);
                beforeCommit(action, payload);
                return ticket;
            });
        } catch (RuntimeException ignored) {
            throw ConnectorExecutionCurrent.rejected();
        }
    }

    public ConnectorExecutionTicketPayload authenticate(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            throw unauthenticated();
        }
        if (ticket.length() > 4096 || !ticket.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "financial execution request rejected");
        }
        String[] parts = ticket.split("\\.");
        byte[] raw = decode(parts[0]);
        byte[] signature = decode(parts[1]);
        var payload = codec.decodePayload(raw);
        if (signature.length != 32 || !MessageDigest.isEqual(signature, signature(parts[0]))) {
            throw unauthenticated();
        }
        return payload;
    }

    void requireExecutable(ConnectorAction action, Instant now) {
        require(action.status() == ActionStatus.EXECUTING
                && action.expiresAt() != null
                && now.isBefore(action.expiresAt()));
    }

    void requireTime(ConnectorExecutionTicketPayload payload, Instant now) {
        require(!now.isBefore(payload.issuedAt())
                && now.isBefore(payload.expiresAt())
                && payload.expiresAt().isAfter(payload.issuedAt())
                && !payload.expiresAt().isAfter(payload.issuedAt().plusSeconds(60)));
    }

    void beforeCommit(ConnectorAction action, ConnectorExecutionTicketPayload payload) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                Instant now = clock.instant();
                requireExecutable(action, now);
                requireTime(payload, now);
            }
        });
    }

    private String sign(ConnectorExecutionTicketPayload payload) {
        String body = ENCODE.encodeToString(codec.encodePayload(payload));
        String ticket = body + "." + ENCODE.encodeToString(signature(body));
        require(ticket.length() <= 4096);
        return ticket;
    }

    private byte[] signature(String body) {
        try {
            String token = properties.dashboardToken();
            if (token == null || token.isBlank()) {
                throw unauthenticated();
            }
            byte[] key = hmac(token.getBytes(StandardCharsets.UTF_8), "fos-approval-signing-key-v1");
            return hmac(key, "fos-approval-claim-v1" + body);
        } catch (GeneralSecurityException ignored) {
            throw unauthenticated();
        }
    }

    private static byte[] hmac(byte[] key, String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] decode(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (!ENCODE.encodeToString(raw).equals(encoded)) {
                throw new IllegalArgumentException();
            }
            return raw;
        } catch (IllegalArgumentException ignored) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "financial execution request rejected");
        }
    }

    private static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "financial execution authentication required");
    }
}
