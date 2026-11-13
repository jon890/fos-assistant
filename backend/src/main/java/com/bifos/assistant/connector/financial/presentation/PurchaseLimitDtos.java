package com.bifos.assistant.connector.financial.presentation;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 구매 상한 화면 API 의 요청 모양이다. 응답은 {@code PurchaseLimitsView} 를 그대로 낸다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PurchaseLimitDtos {

    /**
     * 상한 하나를 바꾸는 요청이다.
     *
     * @param value 0 이상의 원 단위 JSON 정수. 글, 소수, null 은 받지 않는다. 운영 최대치가 없어 비우기도 없다
     */
    public record PurchaseLimitUpdateRequest(JsonNode value) {

        /** JSON 정수가 아니면 {@code VALIDATION_FAILED} 다. 범위는 서비스가 본다. */
        public long parsed() {
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "purchase limit must be a won integer");
            }
            return value.longValue();
        }
    }
}
