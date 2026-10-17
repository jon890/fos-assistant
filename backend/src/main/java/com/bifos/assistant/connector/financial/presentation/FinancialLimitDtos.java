package com.bifos.assistant.connector.financial.presentation;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 주문 상한 화면 API 의 요청 모양이다. 응답은 {@code FinancialLimitsView} 를 그대로 낸다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FinancialLimitDtos {
    private static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,14}(\\.[0-9]{1,6})?$");


    /**
     * 상한 하나를 바꾸는 요청이다.
     *
     * @param value 소수 6자리까지의 0 이상 십진 글. null 이면 운영 최대치를 따르게 한다
     */
    public record LimitUpdateRequest(String value) {

        /** 모양이 틀리면 {@code VALIDATION_FAILED} 다. */
        public BigDecimal parsed() {
            if (value == null) {
                return null;
            }
            if (!DECIMAL.matcher(value).matches()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "financial limit must be a decimal text");
            }
            return new BigDecimal(value);
        }
    }
}
