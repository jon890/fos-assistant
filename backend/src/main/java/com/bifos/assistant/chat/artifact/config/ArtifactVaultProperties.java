package com.bifos.assistant.chat.artifact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자가 보관한 결과물의 한도다.
 *
 * @param userQuotaBytes 사용자당 보관 사본 평문 바이트 수의 합 상한. 넘으면 새 보관만 거절한다
 * @param maxFileBytes 파일 하나의 상한. 결과물 쓰기 도구의 한 파일 상한과 같다
 * @param maxBundleBytes 묶음 하나(뿌리와 부르는 파일 모두)의 평문 바이트 수 상한
 * @param maxBundleFiles 묶음 하나의 파일 수 상한. 뿌리를 센다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.artifact-vault")
public record ArtifactVaultProperties(
    Long userQuotaBytes,
    Long maxFileBytes,
    Long maxBundleBytes,
    Integer maxBundleFiles) {


    private static final long DEFAULT_USER_QUOTA_BYTES = 5L * 1024 * 1024 * 1024;
    private static final long DEFAULT_MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long DEFAULT_MAX_BUNDLE_BYTES = 100L * 1024 * 1024;
    private static final int DEFAULT_MAX_BUNDLE_FILES = 1_000;

    public ArtifactVaultProperties {
        userQuotaBytes = userQuotaBytes == null ? DEFAULT_USER_QUOTA_BYTES : userQuotaBytes;
        maxFileBytes = maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        maxBundleBytes = maxBundleBytes == null ? DEFAULT_MAX_BUNDLE_BYTES : maxBundleBytes;
        maxBundleFiles = maxBundleFiles == null ? DEFAULT_MAX_BUNDLE_FILES : maxBundleFiles;
        if (userQuotaBytes <= 0 || maxFileBytes <= 0 || maxBundleBytes <= 0 || maxBundleFiles <= 0) {
            throw new IllegalStateException("assistant.artifact-vault limits must be positive");
        }
    }


    /** 한도를 모두 기본값으로 둔다. */
    public static ArtifactVaultProperties defaults() {
        return new ArtifactVaultProperties(null, null, null, null);
    }
}
