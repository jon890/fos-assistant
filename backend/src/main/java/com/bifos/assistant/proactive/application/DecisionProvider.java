package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.DecisionQuestion;
import com.bifos.assistant.proactive.domain.DecisionState;
import java.util.List;

/** 판단만 하는 port 다. provider 가 권한이나 실행 여부를 정하지 않는다. */
public interface DecisionProvider {

    String id();

    DecisionResponse evaluate(DecisionState state, List<DecisionQuestion> questions, DecisionRequest request);
}
