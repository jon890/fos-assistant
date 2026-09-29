package com.bifos.assistant.chat.application;

import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import java.util.List;

/**
 * 한 에이전트의 profile 로 대화가 고를 수 있는 모델이다.
 *
 * @param defaultProvider 그 profile 의 기본 provider. Hermes 가 주지 않으면 null
 * @param defaultModel 그 profile 의 기본 모델. Hermes 가 주지 않으면 null
 * @param providers 고를 수 있는 provider. 기본 provider 가 있으면 맨 앞이고, 나머지는 Hermes 가 준 차례다
 * @param reasoningEfforts 고를 수 있는 effort. 낮은 것부터 적는다
 */
public record ModelOptions(
        String defaultProvider,
        String defaultModel,
        List<HermesModelCatalog.Provider> providers,
        List<String> reasoningEfforts) {
}
