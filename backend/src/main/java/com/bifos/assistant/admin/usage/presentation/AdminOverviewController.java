package com.bifos.assistant.admin.usage.presentation;

import com.bifos.assistant.admin.usage.application.AdminOverviewService;
import com.bifos.assistant.admin.usage.presentation.AdminOverviewDtos.FailuresView;
import com.bifos.assistant.admin.usage.presentation.AdminOverviewDtos.MonthlyView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 영역 첫 화면(요약)의 두 카드가 읽는 자리다. 카드마다 따로 읽어 한쪽이 실패해도 다른 카드는 보인다.
 *
 * <p>둘 다 그룹 전체를 센다. 「사용량과 비용」 화면은 지금처럼 요청한 관리자 자신의 실행만 센다.
 */
@RestController
@RequestMapping("/api/v1/admin/overview")
@RequiredArgsConstructor
public class AdminOverviewController {

    private final CurrentUserProvider currentUser;
    private final AdminOverviewService overview;


    /** 서울 날짜로 오늘을 포함한 7일 동안 그룹에서 실패한 루트 실행 수와 최근 세 건이다. */
    @GetMapping("/failures")
    public FailuresView failures() {
        return FailuresView.of(overview.failures(currentUser.requireAdmin()));
    }


    /** 그룹 전체의 이번 달 실행 수와 예상 추가 사용 요금이다. */
    @GetMapping("/monthly")
    public MonthlyView monthly() {
        return MonthlyView.of(overview.monthly(currentUser.requireAdmin()));
    }
}
