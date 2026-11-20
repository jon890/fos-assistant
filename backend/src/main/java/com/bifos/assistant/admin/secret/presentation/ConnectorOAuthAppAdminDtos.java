package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.UpdatedBy;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthAppStatus;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** OAuth 앱 설정 API의 요청과 응답이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorOAuthAppAdminDtos {
    public record AppRequest(String clientId, String clientSecret, String appSlug) {
        @Override
        public String toString() {
            return "AppRequest[값 생략]";
        }
    }

    public record AppsView(boolean storeAvailable, List<AppView> apps) {
    }

    public record AppView(
        String connectorId, String connectorTitle, String provider, String clientId, String appSlug,
        boolean set, String prefix, UpdatedBy updatedBy, Instant updatedAt) {

        static AppView of(ConnectorOAuthAppStatus app, Function<Long, String> names) {
            Long userId = app.updatedBy();
            UpdatedBy by = userId == null ? null : new UpdatedBy(userId, names.apply(userId));
            return new AppView(
                app.connectorId(), app.connectorTitle(), app.provider(), app.clientId(), app.appSlug(),
                app.set(), app.prefix(), by, app.updatedAt());
        }
    }
}
