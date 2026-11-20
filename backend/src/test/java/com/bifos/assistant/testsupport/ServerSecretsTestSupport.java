package com.bifos.assistant.testsupport;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.bifos.assistant.crypto.infra.DatabaseServerSecrets;
import com.bifos.assistant.crypto.infra.ServerSecretRepository;
import com.bifos.assistant.notification.infra.GeneratedVapidKeys;
import com.bifos.assistant.notification.infra.VapidKeys;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;

/**
 * 컨텍스트와 H2 메모리 DB 를 함께 쓰는 검사 사이에 서버 비밀값이 남지 않게 한다.
 *
 * <p>중계 서명 비밀은 이제 설정이 아니라 {@code server_secret} 의 {@code browser_gateway} 에서 읽는다. 그 설정을
 * {@link OverrideProperties} 로 주던 검사가 그대로 돌도록, 검사 시작 때 그 값을 저장소에 넣는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ServerSecretsTestSupport {

    static final String GATEWAY_SECRET_KEY = "assistant.browser.gateway-secret";
    static final String GATEWAY_SECRET_NAME = "browser_gateway";


    /** 모든 줄을 지우고 캐시를 비운다. 저장소가 없는 작은 컨텍스트에서는 아무것도 하지 않는다 */
    public static void reset(ApplicationContext context) {
        context.getBeanProvider(ServerSecretRepository.class).ifAvailable(ServerSecretRepository::deleteAllInBatch);
        context.getBeanProvider(DatabaseServerSecrets.class).ifAvailable(DatabaseServerSecrets::invalidateAll);
    }


    /** 검사가 준 중계 서명 비밀 설정을 저장소에 넣는다. 설정이 없거나 비었으면 아무것도 하지 않는다 */
    static void seed(ApplicationContext context, List<String> overrides) {
        // 웹 푸시는 서버 비밀값 vapid 로만 켜진다. 검사마다 새 검사 전용 키 쌍을 넣는다. 키가 꺼진 동작은 VapidKeys spy 로 본다.
        context.getBeanProvider(DatabaseServerSecrets.class).ifAvailable(secrets -> {
            if (secrets.available()) {
                GeneratedVapidKeys generated = VapidKeys.generate("mailto:test@example.invalid");
                secrets.write(VapidKeys.SECRET_NAME, generated.sealed(),
                    Map.of("publicKey", generated.publicKey(), "subject", "mailto:test@example.invalid"), null);
            }
        });
        overrides.stream()
            .filter(override -> override.startsWith(GATEWAY_SECRET_KEY + "="))
            .map(override -> override.substring(GATEWAY_SECRET_KEY.length() + 1))
            .filter(value -> !value.isBlank())
            .findFirst()
            .ifPresent(value -> context.getBeanProvider(DatabaseServerSecrets.class)
                .ifAvailable(secrets -> secrets.write(GATEWAY_SECRET_NAME, value, Map.of(), null)));
    }
}
