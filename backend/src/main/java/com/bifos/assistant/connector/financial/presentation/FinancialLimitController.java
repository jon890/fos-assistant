package com.bifos.assistant.connector.financial.presentation;

import com.bifos.assistant.connector.financial.application.FinancialLimitSettings;
import com.bifos.assistant.connector.financial.application.dto.FinancialLimitsView;
import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.financial.presentation.FinancialLimitDtos.LimitUpdateRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌 주인이 자기 주문 상한을 읽고 바꾸는 화면 API 다(ADR-20261015 / financial-user-limits).
 *
 * <p>누구의 상한인지는 로그인에서만 정한다. 브라우저 세션으로만 부르며, 에이전트의 MCP 토큰으로는 부를 수 없다.
 */
@RestController
@RequestMapping("/api/v1/financial-limits")
@RequiredArgsConstructor
public class FinancialLimitController {
    private final FinancialLimitSettings settings;
    private final CurrentUserProvider currentUser;
    private final Clock clock;


    @GetMapping
    public FinancialLimitsView get() {
        return settings.view(currentUser.require(), clock.instant());
    }


    /** 올리기도 바로 반영한다. 다시 확인은 화면이 한다. 모르는 이름은 입력 오류다. */
    @PutMapping("/{name}")
    public FinancialLimitsView put(@PathVariable String name, @RequestBody LimitUpdateRequest request) {
        var kind = FinancialLimitKind.fromApiName(name)
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "unknown financial limit"));
        return settings.set(currentUser.require(), kind, request.parsed(), clock.instant());
    }
}
