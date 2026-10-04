package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import java.util.List;

/** 항목이 왜 보였는지다. 화면은 이 코드를 정해진 문구로 그린다. */
public record AttentionWhy(
        AttentionTrigger trigger,
        List<AttentionSignal> signals,
        AttentionConfidence confidence,
        List<AttentionSourceRef> sources) {}
