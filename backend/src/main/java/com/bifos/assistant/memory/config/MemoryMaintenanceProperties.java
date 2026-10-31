package com.bifos.assistant.memory.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 기억 정비 루프의 설정이다(ADR-20261015 / memory-maintenance). 기본값은 제안 모드 첫 4주의 값이고 확인 결과를 보고 고친다.
 *
 * @param archiveIdleDays 이 기간 동안 실리지도 읽히지도 않은 항목만 보관 후보가 된다
 * @param minReviewed 동작 하나를 자동 전환 후보로 보려면 사람이 확인한 후보가 이 수 이상이어야 한다
 * @param minPrecision 그 동작의 「맞음」 비율이 이 값 이상이어야 자동 전환 후보다. 전환은 단계 4 가 한다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.memory.maintenance")
public record MemoryMaintenanceProperties(
    @Min(1) Integer archiveIdleDays,
    @Min(1) Integer minReviewed,
    @DecimalMin("0.0") @DecimalMax("1.0") Double minPrecision) {

    public MemoryMaintenanceProperties {
        archiveIdleDays = archiveIdleDays == null ? 90 : archiveIdleDays;
        minReviewed = minReviewed == null ? 30 : minReviewed;
        minPrecision = minPrecision == null ? 0.9 : minPrecision;
    }


    /** 기본값이다. 테스트가 쓴다. */
    public static MemoryMaintenanceProperties defaults() {
        return new MemoryMaintenanceProperties(null, null, null);
    }
}
