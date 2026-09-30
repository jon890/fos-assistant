package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.connector.domain.AccountbookConnection;
import com.bifos.assistant.connector.domain.ConnectionStatus;
import com.bifos.assistant.connector.infra.AccountbookConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountbookConnectionService {
    private static final String TOKEN_KEY = "ACCOUNTBOOK_API_TOKEN";
    private static final String FAMILY_KEY = "ACCOUNTBOOK_FAMILY_UUID";
    private static final String BASE_URL_KEY = "ACCOUNTBOOK_API_BASE_URL";
    private final AccountbookConnectionRepository connections;
    private final AppUserRepository users;
    private final AgentLifecycleService lifecycle;
    private final AccountbookTokenVerifier verifier;
    private final HermesConnectorClient connector;
    private final HermesToolsetClient toolsets;
    private final AccountbookProperties properties;

    public List<AccountbookTokenVerifier.FamilyOption> availableFamilies(CurrentUser user, String token) {
        requireToken(token);
        return verifier.readFamilies(token);
    }

    @Transactional(readOnly = true)
    public ConnectionSnapshot read(CurrentUser user) {
        return connections.findById(user.id()).map(this::snapshot)
                .orElse(new ConnectionSnapshot(ConnectionStatus.DISCONNECTED, null, null, false, null, null));
    }

    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot register(CurrentUser user, String token, String familyUuid) {
        requireToken(token); UUID family = parseFamily(familyUuid);
        AppUser owner = users.findByIdForUpdate(user.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
        verifier.verify(token, family);
        AccountbookConnection connection = connections.findById(user.id()).orElseGet(() -> {
            Agent agent = lifecycle.create(user, "가계부", AgentVisibility.PRIVATE); agent.markConnectorManaged();
            return connections.save(AccountbookConnection.pending(owner.id(), agent));
        });
        connection.beginRegister();
        connections.save(connection);
        final boolean tokenRestart;
        final boolean familyRestart;
        final boolean baseUrlRestart;
        final boolean installRestart;
        try {
            tokenRestart = connector.putEnv(connection.getAgent().hermesProfile(), TOKEN_KEY, token); connection.markRestartRequired(tokenRestart);
            familyRestart = family == null
                    ? connector.deleteEnv(connection.getAgent().hermesProfile(), FAMILY_KEY)
                    : connector.putEnv(connection.getAgent().hermesProfile(), FAMILY_KEY, family.toString());
            connection.markRestartRequired(familyRestart);
            baseUrlRestart = connector.putEnv(connection.getAgent().hermesProfile(), BASE_URL_KEY, properties.apiBaseUrl()); connection.markRestartRequired(baseUrlRestart);
            installRestart = connector.putConnector(connection.getAgent().hermesProfile(), true); connection.markRestartRequired(installRestart);
        } catch (RuntimeException ex) {
            connection.pending(); throw new ConnectorOperationFailure();
        }
        connection.registered(token.substring(0, 8), family, tokenRestart || familyRestart || baseUrlRestart || installRestart);
        return snapshot(connections.save(connection));
    }

    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot disconnect(CurrentUser user) {
        users.findByIdForUpdate(user.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
        AccountbookConnection connection = connections.findById(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "no accountbook connection"));
        connection.beginDisconnect();
        final boolean tokenRestart;
        final boolean familyRestart;
        final boolean pluginRestart;
        try {
            tokenRestart = connector.deleteEnv(connection.getAgent().hermesProfile(), TOKEN_KEY); connection.markRestartRequired(tokenRestart);
            familyRestart = connector.deleteEnv(connection.getAgent().hermesProfile(), FAMILY_KEY); connection.markRestartRequired(familyRestart);
            pluginRestart = connector.putConnector(connection.getAgent().hermesProfile(), false); connection.markRestartRequired(pluginRestart);
        } catch (RuntimeException ex) { throw new ConnectorOperationFailure(); }
        connection.disconnected(tokenRestart || familyRestart || pluginRestart);
        return snapshot(connections.save(connection));
    }

    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot check(CurrentUser user) {
        users.findByIdForUpdate(user.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
        AccountbookConnection connection = connections.findById(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "no accountbook connection"));
        final HermesConnectorClient.ConnectorState state;
        try { state = connector.readConnector(connection.getAgent().hermesProfile()); }
        catch (RuntimeException ex) { connection.pending(); throw new ConnectorOperationFailure(); }
        if (!connection.isDesiredEnabled()) {
            if (state.enabled()) { connection.pending(); return snapshot(connections.save(connection)); }
            connection.disconnected(connection.isRestartRequired());
            return snapshot(connections.save(connection));
        }
        if (connection.isRestartRequired() || !state.enabled() || !state.configured()) {
            connection.pending(); return snapshot(connections.save(connection));
        }
        final boolean usable;
        try {
            HermesConnectorClient.ProbeResult probe = connector.probeAccountbook(connection.getAgent().hermesProfile());
            usable = probe.ok() && !probe.tools().isEmpty()
                    && toolsets.readEnabled(connection.getAgent().apiBaseUrl(), connection.getAgent().hermesProfile()).isEmpty();
        } catch (RuntimeException ex) { connection.pending(); throw new ConnectorOperationFailure(); }
        if (usable) connection.ready(); else connection.pending();
        return snapshot(connections.save(connection));
    }

    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot confirmApplied(CurrentUser admin, Long userId) {
        if (!admin.isAdmin()) throw new ApiException(ErrorCode.FORBIDDEN, "administrator access is required");
        AppUser target = users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no such user"));
        if (!target.groupId().equals(admin.groupId())) throw new ApiException(ErrorCode.FORBIDDEN, "no such user");
        AccountbookConnection connection = connections.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "no accountbook connection"));
        final boolean disconnected;
        try {
            HermesConnectorClient.ConnectorState state = connector.readConnector(connection.getAgent().hermesProfile());
            if (!connection.isDesiredEnabled()) {
                if (state.enabled()) throw new IllegalStateException();
                disconnected = true;
            } else {
                HermesConnectorClient.ProbeResult probe = connector.probeAccountbook(connection.getAgent().hermesProfile());
                if (!state.enabled() || !state.configured() || !probe.ok() || probe.tools().isEmpty()
                        || !toolsets.readEnabled(connection.getAgent().apiBaseUrl(), connection.getAgent().hermesProfile()).isEmpty()) throw new IllegalStateException();
                disconnected = false;
            }
        } catch (RuntimeException ex) { connection.pending(); throw new ConnectorOperationFailure(); }
        if (disconnected) connection.confirmDisconnected(); else connection.ready();
        return snapshot(connections.save(connection));
    }

    @Transactional(readOnly = true)
    public List<AdminConnectionSnapshot> listForAdmin(CurrentUser admin) {
        if (!admin.isAdmin()) throw new ApiException(ErrorCode.FORBIDDEN, "administrator access is required");
        List<Long> ids = users.findAll().stream().filter(user -> user.groupId().equals(admin.groupId())).map(AppUser::id).toList();
        return connections.findByUserIdIn(ids).stream().map(connection -> {
            AppUser user = users.findById(connection.getUserId()).orElseThrow();
            return new AdminConnectionSnapshot(user.id(), user.displayName(), connection.getStatus(), connection.getAgent().code(), connection.isRestartRequired());
        }).toList();
    }
    private ConnectionSnapshot snapshot(AccountbookConnection value) { return new ConnectionSnapshot(value.getStatus(), value.getTokenPrefix(), value.getFamilyUuid(), value.isRestartRequired(), value.getCheckedAt(), value.getAgent().code()); }
    private static void requireToken(String value) { if (value == null || !value.matches("fab_[A-Za-z0-9_-]{43}")) throw new ApiException(ErrorCode.ACCOUNTBOOK_TOKEN_REJECTED, "accountbook token has an invalid format"); }
    private static UUID parseFamily(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException ex) { throw new ApiException(ErrorCode.VALIDATION_FAILED, "familyUuid has an invalid format"); }
    }
}
