package com.bifos.assistant.proactive.evaluation.application.port;

import com.bifos.assistant.proactive.evaluation.application.dto.DecisionRequest;
import com.bifos.assistant.proactive.evaluation.application.dto.DecisionResponse;
import com.bifos.assistant.proactive.evaluation.domain.DecisionQuestion;
import com.bifos.assistant.proactive.evaluation.domain.DecisionState;
import java.util.List;

/** 판단만 하는 port 다. provider 가 권한이나 실행 여부를 정하지 않는다. */
public interface DecisionProvider {

    String id();


    DecisionResponse evaluate(DecisionState state, List<DecisionQuestion> questions, DecisionRequest request);
}
