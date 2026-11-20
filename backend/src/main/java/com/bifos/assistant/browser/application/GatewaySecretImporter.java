package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.shared.config.LiveProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 옛 설정 {@code assistant.browser.gateway-secret} 의 값을 서버 비밀값으로 한 번 가져온다(서버 비밀값 스펙 REQ9, DECISION16).
 *
 * <p>웹 서버가 요청을 받기 전에 돈다. 그래서 첫 요청부터 저장소의 비밀로 서명한다. 줄이 이미 있으면 값이 비어 있어도 건너뛴다. 관리자가
 * 화면에서 지운 값이 옛 설정 때문에 되살아나지 않게 하려는 것이다. 옛 설정은 지우지 않는다. 운영이 로그를 보고 지운다.
 *
 * <p>실패해도 기동을 멈추지 않는다. 중계만 꺼진 채 뜬다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewaySecretImporter implements SmartInitializingSingleton {

    private final ServerSecrets secrets;
    private final LiveProperties<BrowserProperties> properties;


    @Override
    public void afterSingletonsInstantiated() {
        try {
            importLegacy();
        } catch (RuntimeException ex) {
            log.warn("browser gateway secret import failed exception={}", ex.getClass().getName());
        }
    }


    /** @return 가져왔으면 참 */
    public boolean importLegacy() {
        String legacy = properties.current().gatewaySecret();
        if (legacy == null || legacy.isBlank() || !secrets.available() || secrets.exists(BrowserGatewayTokens.SECRET_NAME)) {
            return false;
        }
        secrets.write(BrowserGatewayTokens.SECRET_NAME, legacy, Map.of(), null);
        log.info("browser gateway secret imported");
        return true;
    }
}
