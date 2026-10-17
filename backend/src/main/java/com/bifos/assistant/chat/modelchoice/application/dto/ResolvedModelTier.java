package com.bifos.assistant.chat.modelchoice.application.dto;

import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;

/** 실행 줄에 남길 실제 요청 값과 그 값을 만든 단계를 함께 둔다. */
public record ResolvedModelTier(ModelChoice choice, ModelTier tier) {
}
