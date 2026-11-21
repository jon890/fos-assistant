package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.UpdatedBy;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthSigningRewrite;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthSigningStatus;
import java.time.Instant;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 단추 연결 서명 비밀 API 의 응답이다. 원문과 통로 주소를 담는 칸은 없다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorOAuthSigningAdminDtos {

    /** 서명 비밀 한 줄이다. {@code connectionCount} 는 새로 만들면 통로 주소를 다시 쓸 단추 연결 수다. */
    public record SigningView(
        boolean storeAvailable, boolean set, UpdatedBy updatedBy, Instant updatedAt, boolean passageConfigured, int connectionCount) {

        static SigningView of(ConnectorOAuthSigningStatus status, Function<Long, String> names) {
            Long userId = status.updatedBy();
            UpdatedBy by = userId == null ? null : new UpdatedBy(userId, names.apply(userId));
            return new SigningView(
                status.storeAvailable(), status.set(), by, status.updatedAt(), status.passageConfigured(), status.connectionCount());
        }
    }

    /** 통로 주소를 다시 쓴 결과다. */
    public record RewriteView(int total, int failed) {

        static RewriteView of(ConnectorOAuthSigningRewrite rewrite) {
            return new RewriteView(rewrite.total(), rewrite.failed());
        }
    }

    /** 새로 만들기 응답이다. 바뀐 상태와 다시 쓴 결과를 함께 준다. */
    public record RegeneratedSigningView(SigningView signing, RewriteView rewrite) {
    }
}
