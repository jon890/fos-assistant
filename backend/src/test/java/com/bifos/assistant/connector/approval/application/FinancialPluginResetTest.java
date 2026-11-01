package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 클래스가 함께 쓰는 금융 plugin 이 앞 검사의 상태를 다음 검사에 넘기지 않는지 본다.
 *
 * <p>첫 검사가 응답 mode 와 대역 카운터를 바꿔 두고, 다음 검사가 같은 프로세스에서 처음 상태로 시작하는지 확인한다.
 * 되돌리지 못하는 조작을 부른 검사 뒤에는 새 프로세스가 뜨는지도 본다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinancialPluginResetTest extends FinancialConnectorTestBase {
    private static String firstUrl;
    private static String firstProfile;


    @Test
    @Order(1)
    @DisplayName("거절 mode 와 준비·실행 카운터를 남기고 끝난다")
    void leavesModeAndCountersBehind() {
        firstUrl = pluginUrl;
        firstProfile = profile;
        configure("400");
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(counters().path("prepare").intValue()).isPositive();
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @Order(2)
    @DisplayName("같은 프로세스의 다음 검사는 새 profile 과 처음 카운터, 기본 응답 mode 로 시작한다")
    void startsNextTestFromInitialState() {
        assertThat(pluginUrl).as("같은 프로세스").isEqualTo(firstUrl);
        assertThat(starts).isEqualTo(1);
        assertThat(profile).isNotEqualTo(firstProfile);
        assertThat(counters()).isEqualTo(INITIAL_COUNTERS.deepCopy().put("url", counters().path("url").stringValue()));
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @Order(3)
    @DisplayName("설치 원본을 바꾼 검사는 프로세스를 되돌리지 못한 것으로 표시한다")
    void marksManifestChangeAsNotResettable() {
        control("manifest-change", Map.of());
        assertThat(shared.dirty()).isTrue();
    }


    @Test
    @Order(4)
    @DisplayName("되돌리지 못한 검사 다음에는 새 프로세스가 처음 선언으로 뜬다")
    void restartsAfterManifestChange() {
        assertThat(pluginUrl).as("새 프로세스").isNotEqualTo(firstUrl);
        assertThat(starts).isEqualTo(2);
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.SUCCEEDED);
    }


    private void configure(String mode) {
        String sha = client.readCatalog().getFirst().executionGuardManifestSha256();
        control("configure", Map.of("profile", profile, "mode", mode, "guard", Map.of("v", 1, "protocol", "approval-claim-v2",
            "bindingId", bindingId.toString(), "connectionId", connectionId.toString(),
            "connectionUpdatedAt", connectionRevision.toString(), "manifestSha256", sha)));
    }
}
