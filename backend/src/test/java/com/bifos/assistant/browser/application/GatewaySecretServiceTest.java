package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.application.dto.GatewaySecretStatus;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 중계 서명 비밀을 서버가 만들고 지우는지 본다(서버 비밀값 스펙 REQ18). */
@BackendIntegrationTest
class GatewaySecretServiceTest {

    @Autowired
    GatewaySecretService service;

    @Autowired
    ServerSecrets secrets;


    @Test
    @DisplayName("새로 만들면 16진수 64자 비밀이 저장되고 상태에 앞부분과 만든 사람이 보이며 지우면 비어 있다")
    void regeneratesAndDeletes() {
        assertThat(service.status().set()).isFalse();

        service.regenerate(5L);

        String value = secrets.read(BrowserGatewayTokens.SECRET_NAME).orElseThrow();
        assertThat(value).matches("[0-9a-f]{64}");
        GatewaySecretStatus status = service.status();
        assertThat(status.set()).isTrue();
        assertThat(status.prefix()).isEqualTo(value.substring(0, 4));
        assertThat(status.updatedBy()).isEqualTo(5L);

        service.regenerate(5L);
        assertThat(secrets.read(BrowserGatewayTokens.SECRET_NAME)).isPresent().get().isNotEqualTo(value);

        service.delete(6L);
        assertThat(service.status().set()).isFalse();
        assertThat(service.status().updatedBy()).isEqualTo(6L);
        assertThat(secrets.read(BrowserGatewayTokens.SECRET_NAME)).isEmpty();
    }
}
