package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelTier;

/** 실행 줄에 남길 실제 요청 값과 그 값을 만든 단계를 함께 둔다. */
public record ResolvedModelTier(ModelChoice choice, ModelTier tier) {
}
