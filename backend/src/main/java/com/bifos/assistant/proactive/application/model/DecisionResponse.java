package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionResult;

/** adapter 의 실행 식별 정보와 판단이다. 원시 응답은 남기지 않는다. */
public record DecisionResponse(DecisionProviderInfo provider, DecisionResult result) {}
