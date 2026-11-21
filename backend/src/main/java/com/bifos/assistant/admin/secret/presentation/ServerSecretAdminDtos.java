package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.application.dto.ServerSecretOverview;
import com.bifos.assistant.browser.application.dto.GatewaySecretStatus;
import com.bifos.assistant.notification.application.dto.VapidStatus;
import com.bifos.assistant.connector.connection.application.dto.BrowserBindingReinstall;
import java.time.Instant;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 「서버 비밀값」 경로가 주고받는 모양이다. 원문을 담는 칸은 없다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ServerSecretAdminDtos {

    /** 마지막으로 바꾼 사람이다. 이름을 찾지 못하면 이름이 null 이다 */
    public record UpdatedBy(Long id, String name) {
    }

    /** 브라우저 중계 서명 비밀 한 줄이다 */
    public record BrowserGatewayView(
        boolean set,
        String prefix,
        UpdatedBy updatedBy,
        Instant updatedAt,
        boolean gatewayUrlConfigured,
        Integer bindingCount) {
    }

    /** 웹 푸시 VAPID 키 한 줄이다. 공개 키는 비밀이 아니라 원문을 보인다. 비밀 키 칸은 없다 */
    public record VapidView(boolean set, String publicKey, String subject, UpdatedBy updatedBy, Instant updatedAt, int deviceCount) {
    }

    /** 화면 전체의 상태다 */
    public record OverviewView(boolean storeAvailable, BrowserGatewayView browserGateway, VapidView vapid) {

        static OverviewView of(ServerSecretOverview overview, Function<Long, String> names) {
            GatewaySecretStatus status = overview.browserGateway();
            UpdatedBy by = status.updatedBy() == null ? null : new UpdatedBy(status.updatedBy(), names.apply(status.updatedBy()));
            Integer count = overview.browserBindingCount().isPresent() ? overview.browserBindingCount().getAsInt() : null;
            VapidStatus push = overview.vapid();
            UpdatedBy pushBy = push.updatedBy() == null ? null : new UpdatedBy(push.updatedBy(), names.apply(push.updatedBy()));
            return new OverviewView(
                overview.storeAvailable(),
                new BrowserGatewayView(status.set(), status.prefix(), by, status.updatedAt(), status.gatewayUrlConfigured(), count),
                new VapidView(push.set(), push.publicKey(), push.subject(), pushBy, push.updatedAt(), push.deviceCount()));
        }
    }

    /** 바인딩 다시 설치 결과다 */
    public record ReinstallView(int total, int restartPending, int failed, boolean catalogUnavailable) {

        static ReinstallView of(BrowserBindingReinstall result) {
            return new ReinstallView(result.total(), result.restartPending(), result.failed(), result.catalogUnavailable());
        }
    }

    /** VAPID 새로 만들기 요청이다. 연락처가 비어 있으면 만드는 관리자의 이메일을 쓴다. {@code expectedPublicKey} 는 화면이 읽은 공개 키(없었으면 null)이고 지금 키와 다르면 거절한다 */
    public record RegenerateVapidRequest(String subject, String expectedPublicKey) {
    }

    /** VAPID 새로 만들기와 지우기 응답이다 */
    public record VapidChangedView(boolean storeAvailable, VapidView vapid, int releasedUsers) {
    }

    /** 다시 만들기 응답이다. 바뀐 화면 상태와 다시 설치 결과를 함께 준다 */
    public record RegeneratedView(boolean storeAvailable, BrowserGatewayView browserGateway, ReinstallView reinstall) {
    }
}
