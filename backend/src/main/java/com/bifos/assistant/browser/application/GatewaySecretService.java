package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.dto.GatewaySecretStatus;
import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.shared.config.LiveProperties;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브라우저 중계의 서명 비밀을 서버가 새로 만들고 지운다(서버 비밀값 스펙 REQ18, ADR-20261120 / server-secret).
 *
 * <p>값은 사람이 넣지 않는다. 새로 만들면 옛 비밀로 서명한 표식은 바로 무효다. 붙은 바인딩을 다시 설치하는 일은 위층이 이어서 한다.
 */
@Service
@RequiredArgsConstructor
public class GatewaySecretService {

    /** 서명 비밀의 바이트 수다. 소문자 16진수 64자가 되어 기존 32자 이상 규칙을 넉넉히 넘는다 */
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSecrets secrets;
    private final LiveProperties<BrowserProperties> properties;


    @Transactional(readOnly = true)
    public GatewaySecretStatus status() {
        boolean urlConfigured = hasText(properties.current().gatewayBaseUrl());
        return secrets.find(BrowserGatewayTokens.SECRET_NAME)
            .map(row -> new GatewaySecretStatus(row.set(), row.prefix(), row.updatedBy(), row.updatedAt(), urlConfigured))
            .orElseGet(() -> new GatewaySecretStatus(false, null, null, null, urlConfigured));
    }


    /** 새 비밀을 만들어 쓴다. KEK 가 없으면 {@code SECRET_STORE_UNAVAILABLE} 이다 */
    @Transactional
    public void regenerate(Long adminId) {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        secrets.write(BrowserGatewayTokens.SECRET_NAME, HexFormat.of().formatHex(bytes), Map.of(), adminId);
    }


    /** 비밀을 지워 중계를 끈다 */
    @Transactional
    public void delete(Long adminId) {
        secrets.clear(BrowserGatewayTokens.SECRET_NAME, adminId);
    }


    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
