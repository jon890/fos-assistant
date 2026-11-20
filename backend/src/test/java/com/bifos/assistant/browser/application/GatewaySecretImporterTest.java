package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 옛 설정의 중계 서명 비밀을 이름마다 한 번만 가져오는지 본다(서버 비밀값 스펙 REQ9, DECISION16). */
@BackendIntegrationTest
class GatewaySecretImporterTest {

    private static final String LEGACY = "legacy-gateway-secret-0123456789abcdef";

    @Autowired
    ServerSecrets secrets;


    @Test
    @DisplayName("줄이 없으면 옛 값을 가져오고, 줄이 있으면 옛 값이 바뀌어도 가져오지 않는다")
    void importsOnlyOnce() {
        assertThat(importer(LEGACY).importLegacy()).isTrue();
        assertThat(secrets.read(BrowserGatewayTokens.SECRET_NAME)).contains(LEGACY);
        assertThat(secrets.find(BrowserGatewayTokens.SECRET_NAME).orElseThrow().updatedBy()).isNull();

        assertThat(importer(LEGACY + "-changed").importLegacy()).isFalse();
        assertThat(secrets.read(BrowserGatewayTokens.SECRET_NAME)).contains(LEGACY);
    }


    @Test
    @DisplayName("화면에서 지운 값은 옛 값이 남아 있어도 되살아나지 않는다")
    void doesNotReviveClearedValue() {
        secrets.write(BrowserGatewayTokens.SECRET_NAME, "stored-gateway-secret-0123456789abcdef", Map.of(), 1L);
        secrets.clear(BrowserGatewayTokens.SECRET_NAME, 1L);

        assertThat(importer(LEGACY).importLegacy()).isFalse();
        assertThat(secrets.read(BrowserGatewayTokens.SECRET_NAME)).isEmpty();
    }


    @Test
    @DisplayName("옛 값이 비어 있으면 줄을 만들지 않는다")
    void skipsEmptyLegacy() {
        assertThat(importer(null).importLegacy()).isFalse();
        assertThat(secrets.exists(BrowserGatewayTokens.SECRET_NAME)).isFalse();
    }


    @Test
    @DisplayName("KEK 가 없으면 옛 값을 가져오지 않고 줄을 만들지 않는다")
    void skipsWithoutKek() {
        ServerSecrets withoutKek = mock(ServerSecrets.class);
        when(withoutKek.available()).thenReturn(false);

        assertThat(new GatewaySecretImporter(withoutKek, live(LEGACY)).importLegacy()).isFalse();
        verify(withoutKek, never()).write(any(), any(), any(), any());
    }


    private GatewaySecretImporter importer(String legacy) {
        return new GatewaySecretImporter(secrets, live(legacy));
    }


    private static LiveProperties<BrowserProperties> live(String legacy) {
        BrowserProperties properties = new BrowserProperties(
            false, null, null, null, null, null, null, null, 1024, null, null, null, 2,
            Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofMinutes(30),
            null, legacy);
        return LiveProperties.fixed(BrowserProperties.class, properties);
    }
}
