package com.bifos.assistant.crypto.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자 본문을 저장할 때 암호화하는 설정이다(ADR-20261008 / data-encryption).
 *
 * <p>{@code kekFile} 과 {@code activeKekId} 는 함께 채우거나 함께 비운다. 비우면 암호화가 꺼져 새 본문을 평문으로 저장한다.
 * {@code required} 가 참인데 비어 있으면 기동하지 않는다. 운영은 이 값을 켠다. 공개 저장소의 다른 설치는 key 없이도 뜬다.
 *
 * @param kekFile KEK 목록 파일의 경로. 한 줄에 {@code <id>:<base64 32바이트>} 하나
 * @param activeKekId 새 데이터 key 를 감쌀 KEK 의 id
 * @param required 참이면 KEK 가 없을 때 기동을 멈춘다
 * @param dekCacheTtl 푼 데이터 key 를 메모리에 두는 시간
 * @param dekCacheMaxEntries 메모리에 두는 데이터 key 의 수. 넘으면 가장 오래 쓰지 않은 것부터 버린다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.data-encryption")
public record DataEncryptionProperties(
        String kekFile, String activeKekId, boolean required, Duration dekCacheTtl, int dekCacheMaxEntries) {

    public DataEncryptionProperties {
        kekFile = kekFile == null ? "" : kekFile.strip();
        activeKekId = activeKekId == null ? "" : activeKekId.strip();
        dekCacheTtl = dekCacheTtl == null ? Duration.ofMinutes(10) : dekCacheTtl;
        if (dekCacheMaxEntries <= 0) {
            dekCacheMaxEntries = 1000;
        }
        if (kekFile.isEmpty() != activeKekId.isEmpty()) {
            throw new IllegalArgumentException("data encryption: kek-file 과 active-kek-id 는 함께 채우거나 함께 비워야 한다");
        }
        if (required && kekFile.isEmpty()) {
            throw new IllegalArgumentException("data encryption: required 인데 kek-file 이 비어 있다");
        }
    }

    public boolean configured() {
        return !kekFile.isEmpty();
    }
}
