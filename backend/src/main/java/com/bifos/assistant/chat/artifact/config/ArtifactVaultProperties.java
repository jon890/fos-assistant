package com.bifos.assistant.chat.artifact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자가 보관한 결과물의 한도다.
 *
 * @param userQuotaBytes 사용자당 보관 사본 평문 바이트 수의 합 상한. 넘으면 새 보관만 거절한다
 * @param maxFileBytes 파일 하나의 상한. 결과물 쓰기 도구의 한 파일 상한과 같다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.artifact-vault")
public record ArtifactVaultProperties(Long userQuotaBytes, Long maxFileBytes) {

    private static final long DEFAULT_USER_QUOTA_BYTES = 5L * 1024 * 1024 * 1024;
    private static final long DEFAULT_MAX_FILE_BYTES = 5L * 1024 * 1024;


    public ArtifactVaultProperties {
        userQuotaBytes = userQuotaBytes == null ? DEFAULT_USER_QUOTA_BYTES : userQuotaBytes;
        maxFileBytes = maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        if (userQuotaBytes <= 0 || maxFileBytes <= 0) {
            throw new IllegalStateException("assistant.artifact-vault limits must be positive");
        }
    }
}
