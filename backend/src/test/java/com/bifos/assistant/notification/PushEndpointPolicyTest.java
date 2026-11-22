package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.notification.domain.PushEndpointPolicy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 웹 푸시 주소 정책이 허용한 푸시 서비스의 https 주소만 받는지 본다(ADR-20261120 / web-push). */
class PushEndpointPolicyTest {

    private final PushEndpointPolicy policy = new PushEndpointPolicy(List.of(
        "fcm.googleapis.com", "jmt17.google.com", "updates.push.services.mozilla.com", "web.push.apple.com", ".notify.windows.com"));


    @ParameterizedTest
    @ValueSource(strings = {
        "https://fcm.googleapis.com/fcm/send/abc",
        "https://JMT17.google.com:443/fcm/send/token_1:token-2",
        "https://updates.push.services.mozilla.com/wpush/v2/abc",
        "https://web.push.apple.com/QOabc",
        "https://wns2-par02p.notify.windows.com/w/?token=abc",
        "https://FCM.googleapis.com:443/fcm/send/abc"})
    @DisplayName("허용 호스트와 접미사 항목에 맞는 https 주소는 받는다")
    void allowsKnownPushServices(String endpoint) {
        assertThat(policy.allows(endpoint)).isTrue();
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "https://evilnotify.windows.com/w/abc",
        "https://notify.windows.com/w/abc",
        "https://fcm.googleapis.com.attacker.example/fcm/send/abc",
        "http://fcm.googleapis.com/fcm/send/abc",
        "https://fcm.googleapis.com:8443/fcm/send/abc",
        "https://user:pass@fcm.googleapis.com/fcm/send/abc",
        "https://127.0.0.1/x",
        "https://[::1]/x",
        "https://internal.example/x",
        "not a url",
        ""})
    @DisplayName("다른 호스트, http, 다른 포트, userinfo, IP 리터럴은 받지 않는다")
    void rejectsEverythingElse(String endpoint) {
        assertThat(policy.allows(endpoint)).isFalse();
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "http://jmt17.google.com/fcm/send/abc",
        "https://jmt17.google.com:8443/fcm/send/abc",
        "https://user@jmt17.google.com/fcm/send/abc",
        "https://jmt17.google.com.evil.example/fcm/send/abc",
        "https://jmt17.google.com./fcm/send/abc",
        "https://other.google.com/fcm/send/abc",
        "https://jmt17.google.com/fcm/send/",
        "https://jmt17.google.com/another/path/abc",
        "https://jmt17.google.com/fcm/send/../other",
        "https://jmt17.google.com/fcm/send/abc%2Fdef",
        "https://jmt17.google.com/fcm/send/abc?x=y",
        "https://jmt17.google.com/fcm/send/abc#fragment"})
    @DisplayName("Chromium 스테이징 주소는 정확한 호스트와 구독 경로의 https 주소만 받는다")
    void rejectsNonSubscriptionRoutesForChromiumStaging(String endpoint) {
        assertThat(policy.allows(endpoint)).isFalse();
    }


    @Test
    @DisplayName("상한보다 긴 주소는 받지 않는다")
    void rejectsTooLong() {
        String endpoint = "https://fcm.googleapis.com/" + "a".repeat(PushEndpointPolicy.MAX_LENGTH);

        assertThat(policy.allows(endpoint)).isFalse();
    }
}
