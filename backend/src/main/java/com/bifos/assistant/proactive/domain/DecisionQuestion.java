package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.DecisionAxis;

/** 축과 판단 기준을 함께 저장한다. replay 에서도 같은 질문을 쓴다. */
public record DecisionQuestion(DecisionAxis axis, String question) {}
