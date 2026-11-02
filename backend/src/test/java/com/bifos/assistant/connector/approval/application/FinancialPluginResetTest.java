package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 클래스가 함께 쓰는 금융 plugin 이 앞 검사의 상태를 다음 검사에 넘기지 않는지 본다.
 *
 * <p>검사 사이의 경계는 JUnit 이 부르는 것과 같은 {@link #releasePlugin()} 과 {@link #setupProducer()} 를 검사 안에서 불러 만든다.
 * 앞 검사의 실행에 기대지 않으므로 메서드 하나만 골라 돌려도, 순서를 바꿔 돌려도 같은 것을 확인한다.
 */
class FinancialPluginResetTest extends FinancialConnectorTestBase {


    @Test
    @DisplayName("거절 mode 와 카운터를 남긴 뒤에도 같은 프로세스의 다음 검사는 새 profile 과 처음 카운터, 기본 응답 mode 로 시작한다")
    void startsNextTestFromInitialState() throws Exception {
        String firstUrl = pluginUrl;
        String firstProfile = profile;
        int startsBefore = starts;
        configure("400");
        var rejected = request();
        assertThat(approval.approve(user, rejected.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(counters().path("prepare").intValue()).isPositive();
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);

        nextTest();

        assertThat(pluginUrl).as("같은 프로세스").isEqualTo(firstUrl);
        assertThat(starts).isEqualTo(startsBefore);
        assertThat(profile).isNotEqualTo(firstProfile);
        assertThat(counters()).isEqualTo(INITIAL_COUNTERS.deepCopy().put("url", counters().path("url").stringValue()));
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("설치 원본을 바꾼 검사 다음에는 새 프로세스가 처음 선언으로 뜬다")
    void restartsAfterManifestChange() throws Exception {
        String firstUrl = pluginUrl;
        int startsBefore = starts;
        control("manifest-change", Map.of());
        assertThat(shared.dirty()).as("되돌리지 못한 것으로 표시").isTrue();

        nextTest();

        assertThat(pluginUrl).as("새 프로세스").isNotEqualTo(firstUrl);
        assertThat(starts).isEqualTo(startsBefore + 1);
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.SUCCEEDED);
    }


    /** 이 검사를 끝내고 다음 검사를 준비할 때 JUnit 이 부르는 정리와 준비를 그대로 부른다. */
    private void nextTest() throws Exception {
        releasePlugin();
        setupProducer();
    }


    private void configure(String mode) {
        String sha = client.readCatalog().getFirst().executionGuardManifestSha256();
        control("configure", Map.of("profile", profile, "mode", mode, "guard", Map.of("v", 1, "protocol", "approval-claim-v2",
            "bindingId", bindingId.toString(), "connectionId", connectionId.toString(),
            "connectionUpdatedAt", connectionRevision.toString(), "manifestSha256", sha)));
    }
}
