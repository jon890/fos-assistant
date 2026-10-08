package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.DecisionEvidence;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 평가와 replay 만 제공한다. 단추로 연 살펴보기에서는 자동으로 부르지 않는다. 매일 깨우기는 동의한 사용자에게만 {@code
 * ProactiveLoopCoordinator} 가 한 번 부른다.
 */
@Service
@RequiredArgsConstructor
public class ValueEvaluationService {

    private final List<DecisionProvider> providers;
    private final ValueEvaluator evaluator;
    private final ValueEvaluationStore store;

    public ValueEvaluation evaluate(CurrentUser user, Long checkId, String providerId) {
        DecisionProvider provider = provider(providerId);
        return run(user, store.begin(user.id(), checkId, provider.id()), provider);
    }

    public ValueEvaluation replay(CurrentUser user, Long sourceId, String providerId) {
        DecisionProvider provider = provider(providerId);
        return run(user, store.beginReplay(user.id(), sourceId, provider.id()), provider);
    }

    public ValueEvaluation read(CurrentUser user, Long id) {
        return store.read(user.id(), id);
    }

    private ValueEvaluation run(CurrentUser user, ValueEvaluation row, DecisionProvider provider) {
        DecisionEvidence input = row.evidence();
        DecisionResponse response =
                evaluator.evaluate(input.state(), input.questions(), provider, new DecisionRequest(user));
        store.finish(
                user.id(),
                row.id(),
                new DecisionEvidence(input.state(), input.questions(), response.provider(), response.result()));
        return store.read(user.id(), row.id());
    }

    private DecisionProvider provider(String id) {
        return providers.stream()
                .filter(each -> each.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "unknown decision provider"));
    }
}
