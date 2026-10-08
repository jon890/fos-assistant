package com.bifos.assistant.crypto.application;

import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.UserDataKey;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동할 때 활성 KEK 가 아닌 KEK 로 감싼 데이터 key 를 활성 KEK 로 다시 감싼다(ADR-20261008 / data-encryption).
 *
 * <p>KEK 를 바꾸는 절차는 이렇다. KEK 파일에 새 key 를 더하고 {@code active-kek-id} 를 새 id 로 바꿔 기동한다. 이 작업이 데이터
 * key 줄만 다시 감싼다. 본문은 다시 쓰지 않는다. 남은 줄이 없다는 로그를 본 뒤 파일에서 옛 key 를 뺀다.
 *
 * <p>실패해도 기동을 멈추지 않는다. 옛 KEK 가 파일에 남아 있으면 그 줄은 그대로 읽힌다. 다음 기동에서 다시 본다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataKeyRewrapper implements ApplicationRunner {

    private final UserDataKeyRepository keys;
    private final KeyEncryptionKeys keks;
    private final DataKeyWriter writer;

    @Override
    public void run(ApplicationArguments args) {
        try {
            rewrapAll();
        } catch (RuntimeException e) {
            log.error("데이터 key 를 다시 감싸지 못했다 exception={}", e.getClass().getName());
        }
    }

    /** @return 다시 감싼 줄 수 */
    public int rewrapAll() {
        if (!keks.available()) {
            return 0;
        }
        List<UserDataKey> stale = keys.findByKekIdNot(keks.activeKeyId());
        int rewrapped = 0;
        int missing = 0;
        for (UserDataKey row : stale) {
            if (writer.rewrap(row.id())) {
                rewrapped++;
            } else if (!keks.has(row.kekId())) {
                missing++;
            }
        }
        if (!stale.isEmpty()) {
            log.info("데이터 key 를 활성 KEK 로 다시 감쌌다 rewrapped={} kekMissing={}", rewrapped, missing);
        }
        if (missing > 0) {
            log.warn("KEK 파일에 없는 옛 KEK 로 감싼 데이터 key 가 있다. 그 사용자의 본문은 읽지 못한다 count={}", missing);
        }
        return rewrapped;
    }
}
