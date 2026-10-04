package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.RecoveredAnswerGuard;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기동 정리가 다시 붙어 끝낸 살펴보기 turn 의 답을 대화에 남기지 않게 한다(ADR-081).
 *
 * <p>그 답은 결과 블록의 JSON 이 섞인, 검사하지 않은 글이다. 대신 실패 알림 줄 하나를 남긴다. 사용자가 점검 대화에서 직접 보낸
 * 보통 turn 은 살펴보기 줄의 루트가 아니므로 답이 그대로 남는다. 규칙은 {@code docs/backend/proactive-check.md} 의 「끝날 때」 가
 * 갖는다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveCheckAnswerGuard implements RecoveredAnswerGuard {

    private final ProactiveCheckRepository checks;

    @Override
    public Optional<String> noticeInsteadOfAnswer(Long rootExecutionId) {
        return checks.existsByRootExecutionId(rootExecutionId)
                ? Optional.of(ProactiveCheckService.FAILED_NOTICE)
                : Optional.empty();
    }
}
