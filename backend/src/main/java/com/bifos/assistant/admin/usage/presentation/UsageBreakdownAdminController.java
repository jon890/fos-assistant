package com.bifos.assistant.admin.usage.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.summary.application.UsageSummaryService;
import com.bifos.assistant.admin.usage.presentation.UsageBreakdownAdminDtos.BreakdownRow;
import com.bifos.assistant.admin.usage.presentation.UsageBreakdownAdminDtos.BreakdownView;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 한 달치 실행을 축 하나로 묶어 본다. 경로는 사용자 비용 경로와 같은 {@code /api/v1/usage} 아래다. */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageBreakdownAdminController {

    /** 가족이 사는 곳의 달력으로 달을 끊는다. {@code UsageController} 와 같은 시간대이므로 바꾸면 함께 고친다. */
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");

    private final CurrentUserProvider currentUser;
    private final UsageSummaryService summaries;


    /**
     * 한 달치 실행을 축 하나로 묶어 준다.
     *
     * <p>네 축이 같은 줄 모양을 쓴다. 화면이 표 하나로 네 축을 모두 그릴 수 있게 하려는 것이다.
     * 실행 합계는 데이터베이스가 내고, native 자식의 원장 줄은 {@code UsageSummaryService} 가 그 줄에
     * 더한다(ADR-062).
     *
     * <p>자기 것만 낸다. 여러 사용자를 가로질러 보는 것은 admin 화면이 맡는다.
     *
     * <p>응답 전체가 내부 값이라 {@code MEMBER} 역할은 {@code FORBIDDEN} 을 받는다(ADR-063).
     *
     * @param axis {@code agent}, {@code model}, {@code day}, {@code fingerprint} 중 하나
     * @param month {@code 2026-09} 형태의 대상 달. 없으면 이번 달
     */
    @GetMapping("/breakdown")
    public BreakdownView breakdown(@RequestParam String axis, @RequestParam(required = false) String month) {
        Long userId = currentUser.requireAdmin().id();
        YearMonth target = parseMonth(month);
        Instant from = target.atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        Instant to = target.plusMonths(1).atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        return new BreakdownView(axis, target.toString(), "USD", rows(axis, userId, from, to));
    }


    private List<BreakdownRow> rows(String axis, Long userId, Instant from, Instant to) {
        return switch (axis) {
            case "agent" ->
                summaries.byAgent(userId, from, to).stream()
                    .map(BreakdownRow::ofAgent)
                    .toList();
            case "model" ->
                summaries.byModel(userId, from, to).stream()
                    .map(BreakdownRow::ofModel)
                    .toList();
            case "day" ->
                summaries.byDay(userId, from, to, HOUSEHOLD_ZONE).stream()
                    .map(BreakdownRow::ofDay)
                    .toList();
            case "fingerprint" ->
                summaries.byFingerprint(userId, from, to).stream()
                    .map(BreakdownRow::ofFingerprint)
                    .toList();
            default ->
                throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "axis must be one of agent, model, day, fingerprint");
        };
    }


    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(HOUSEHOLD_ZONE);
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "month must look like 2026-09", ex);
        }
    }
}
