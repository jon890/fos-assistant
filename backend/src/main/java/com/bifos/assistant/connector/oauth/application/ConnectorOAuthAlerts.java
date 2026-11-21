package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.application.port.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 단추 연결이 끊겼을 때 연결 상태를 바꾸고 알린다(스펙 REQ7, 설계 「끊김과 알림」).
 *
 * <p>연결과 앱 줄에 공개 번호가 없어 알림의 원인은 이름에서 만든 UUID 로 가리킨다. 본문에는 플러그인 이름만 쓰고 토큰과 서비스 응답은 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorOAuthAlerts {
    private final ConnectorConnectionRepository connections;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final SignInRevocation access;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. */
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * 서비스가 갱신을 거절했다. 사용자 행을 잠그고 연결을 다시 읽어, 아직 표시가 없을 때만 다시 연결 필요로 바꾸고 알림 하나를 남긴다.
     * 갱신하는 사이 연결이 바뀌었으면(다시 연결, 붙여 넣기, 해제) 옛 토큰의 거절이라 아무것도 하지 않는다.
     *
     * @param observed 갱신을 시작할 때 읽은 연결
     * @param title 알림에 쓸 플러그인 이름
     */
    public void reconnectRequired(ConnectorConnection observed, String title) {
        long userId = observed.userId();
        long connectionId = observed.id();
        transactions.executeWithoutResult(status -> {
            if (users.findByIdForUpdate(userId).isEmpty()) {
                return;
            }
            ConnectorConnection connection = connections.findById(connectionId).orElse(null);
            if (connection == null || !connection.updatedAt().equals(observed.updatedAt())
                || connection.status() == ConnectionStatus.DISCONNECTED
                || !connection.fields().auth().usesOAuth() || connection.fields().auth().reconnectRequired()) {
                return;
            }
            connection.reconnectRequired(clock.instant());
            connections.save(connection);
            notifications.notify(
                userId, NotificationKind.CONNECTOR_RECONNECT_REQUIRED, "다시 연결이 필요해요",
                title + " 연결이 끊겼어요. 「외부 서비스 연결」에서 다시 연결해 주세요.",
                new NotificationTarget(NotificationTargetType.CONNECTIONS, null), connectionSource(connectionId));
        });
    }


    /**
     * 다시 연결 필요였던 연결이 새 토큰을 받았다. 표시를 지우고 끊김 알림을 읽음으로 바꾼다. 갱신 잠금 안에서 부른다. 다음 갱신의 거절이 이
     * 복구보다 먼저 반영되지 않게 하려는 것이다. 갱신하는 사이 연결이 바뀌었으면 아무것도 하지 않는다. 연결의 revision 은 바꾸지 않는다. 연결
     * 확인의 뒤 트랜잭션이 앞에서 읽은 revision 과 대조한다.
     *
     * @param observed 갱신을 시작할 때 읽은 연결
     */
    public void recovered(ConnectorConnection observed) {
        long userId = observed.userId();
        long connectionId = observed.id();
        transactions.executeWithoutResult(status -> {
            if (users.findByIdForUpdate(userId).isEmpty()) {
                return;
            }
            ConnectorConnection connection = connections.findById(connectionId).orElse(null);
            if (connection == null || !connection.updatedAt().equals(observed.updatedAt())
                || !connection.fields().auth().usesOAuth() || !connection.fields().auth().reconnectRequired()) {
                return;
            }
            connection.reconnectResolved();
            connections.save(connection);
            reconnected(userId, connectionId);
        });
    }


    /** 다시 연결했거나 해제했거나 붙여 넣기로 바꿨다. 그 연결의 끊김 알림을 읽음으로 바꾼다. 부르는 쪽의 트랜잭션에 참여한다. */
    public void reconnected(long userId, long connectionId) {
        notifications.resolve(userId, NotificationKind.CONNECTOR_RECONNECT_REQUIRED, connectionSource(connectionId));
    }


    /**
     * 서비스가 앱 자격을 거절했다. 연결 주인 그룹의 차단되지 않은 관리자마다, 같은 앱의 읽지 않은 알림이 없을 때만 하나를 남긴다.
     * 같은 앱을 쓰는 두 연결이 함께 거절돼도 한 번만 남기도록 확인과 저장, 커밋을 이 객체에서 직렬화한다(서버 한 대 전제).
     *
     * @param secretName 그 앱의 서버 비밀값 줄 이름
     */
    public synchronized void appRejected(long ownerId, String secretName, String title) {
        transactions.executeWithoutResult(status -> {
            AppUser owner = users.findById(ownerId).orElse(null);
            if (owner == null) {
                return;
            }
            NotificationTarget target = new NotificationTarget(NotificationTargetType.ADMIN_SECRETS, null);
            for (AppUser admin : users.findByGroupIdAndRole(owner.groupId(), UserRole.ADMIN)) {
                if (!access.revoked(admin.email())) {
                    notifications.notifyUnlessUnread(
                        admin.id(), NotificationKind.CONNECTOR_OAUTH_APP_REJECTED, "OAuth 앱 설정을 확인해 주세요",
                        title + " 의 OAuth 앱 자격을 서비스가 받지 않았어요. 「서버 비밀값」에서 client ID 와 client secret 을 확인해 주세요.",
                        target, appSource(secretName));
                }
            }
        });
    }


    /** 관리자가 그 앱 줄을 바꿨다. 그 앱의 거절 알림을 읽음으로 바꾼다. */
    public void appChanged(String secretName) {
        transactions.executeWithoutResult(status -> notifications.resolveForAll(
            NotificationKind.CONNECTOR_OAUTH_APP_REJECTED, appSource(secretName)));
    }


    private static UUID connectionSource(long connectionId) {
        return source("connector-oauth:connection:" + connectionId);
    }


    private static UUID appSource(String secretName) {
        return source("connector-oauth:app:" + secretName);
    }


    private static UUID source(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
