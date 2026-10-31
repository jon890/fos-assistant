package com.bifos.assistant.chat.modelchoice.application.dto;

import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import java.util.List;

/**
 * 그룹 하나의 모델 목록이다. 켜진 모든 에이전트의 profile 에서 도는 provider 와 모델만 담는다.
 *
 * @param providers 고를 수 있는 provider. 첫 profile 목록의 차례다. 각 provider 의 reasoning 표에는 모든 모델이 있고,
 * profile 마다 밝힌 지원이 다르면 {@code UNKNOWN} 이다
 * @param reasoningEfforts 고를 수 있는 effort. 낮은 것부터 적는다
 */
public record GroupModelCatalog(List<HermesModelCatalog.Provider> providers, List<String> reasoningEfforts) {
}
