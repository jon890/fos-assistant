package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.notification.domain.VapidKeyPair;
import com.bifos.assistant.notification.infra.GeneratedVapidKeys;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.testsupport.InMemoryServerSecrets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 서버 비밀값에서 VAPID 키를 읽는지 본다(ADR-20261120 / web-push, server-secret). */
class VapidKeysTest {

    @Test
    @DisplayName("저장한 키 쌍이면 켜지고 만들 때의 공개 키와 연락처를 준다")
    void loadsStoredPair() {
        GeneratedVapidKeys generated = VapidKeys.generate("mailto:a@example.invalid");
        VapidKeys keys = new VapidKeys(InMemoryServerSecrets.with(VapidKeys.SECRET_NAME, generated.sealed()));

        assertThat(keys.enabled()).isTrue();
        assertThat(keys.publicKeyBase64Url()).isEqualTo(generated.publicKey());
        assertThat(keys.current()).map(VapidKeyPair::subject).contains("mailto:a@example.invalid");
    }


    @Test
    @DisplayName("값이 없으면 꺼진 채이고 공개 키가 없다")
    void offWhenMissing() {
        VapidKeys keys = new VapidKeys(InMemoryServerSecrets.with(VapidKeys.SECRET_NAME, null));

        assertThat(keys.enabled()).isFalse();
        assertThat(keys.publicKeyBase64Url()).isNull();
    }


    @Test
    @DisplayName("깨진 값이나 한 쌍이 아닌 값이면 꺼진 채다")
    void offWhenBrokenOrMismatched() {
        String mismatched = VapidKeys.generate("mailto:a@example.invalid").sealed()
            .replaceFirst("\"publicKey\":\"[^\"]*\"", "\"publicKey\":\"" + VapidKeys.generate("mailto:b@example.invalid").publicKey() + "\"");

        assertThat(new VapidKeys(InMemoryServerSecrets.with(VapidKeys.SECRET_NAME, "not-json")).enabled()).isFalse();
        assertThat(new VapidKeys(InMemoryServerSecrets.with(VapidKeys.SECRET_NAME, mismatched)).enabled()).isFalse();
    }


    @Test
    @DisplayName("저장한 값이 바뀌면 다음 읽기부터 새 키를 쓴다")
    void followsChanges() {
        InMemoryServerSecrets secrets = InMemoryServerSecrets.with(VapidKeys.SECRET_NAME, VapidKeys.generate("mailto:a@example.invalid").sealed());
        VapidKeys keys = new VapidKeys(secrets);
        String before = keys.publicKeyBase64Url();
        GeneratedVapidKeys next = VapidKeys.generate("mailto:a@example.invalid");

        secrets.write(VapidKeys.SECRET_NAME, next.sealed(), Map.of(), null);

        assertThat(keys.publicKeyBase64Url()).isEqualTo(next.publicKey()).isNotEqualTo(before);
    }
}
