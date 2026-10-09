package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.RecoveredAnswerGuard;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기동 정리가 다시 붙어 끝낸 살펴보기 turn 의 답을 대화에 남기지 않게 한다(ADR-081).
 *
 * <p>그 답은 결과 블록의 JSON 이 섞인, 검사하지 않은 글이다. 대신 알림 줄 하나를 남긴다. 성공으로 끝났으면 실패 알림 줄이고, 취소로
 * 끝났으면 사용자가 멈췄을 때와 같은 멈춤 알림 줄이다. 사용자가 점검 대화에서 직접 보낸
 * 보통 turn 은 살펴보기 줄의 루트가 아니므로 답이 그대로 남는다. 규칙은 {@code docs/features/proactive.md} 의 「끝날 때」 가
 * 갖는다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveCheckAnswerGuard implements RecoveredAnswerGuard {

    private final ProactiveCheckRepository checks;

    @Override
    public Optional<String> noticeInsteadOfAnswer(Long rootExecutionId, ExecutionStatus ended) {
        if (!checks.existsByRootExecutionId(rootExecutionId)) {
            return Optional.empty();
        }
        // 자동 실행한 살펴보기는 사용자에게 바로 알리지 않는다. 답도 알림 줄도 남기지 않는다.
        if (checks.existsByRootExecutionIdAndTrigger(rootExecutionId, CheckTrigger.AUTONOMY)) {
            return Optional.of("");
        }
        return Optional.of(
                ended == ExecutionStatus.CANCELLED
                        ? ProactiveCheckRun.STOPPED_NOTICE
                        : ProactiveCheckService.FAILED_NOTICE);
    }
}
