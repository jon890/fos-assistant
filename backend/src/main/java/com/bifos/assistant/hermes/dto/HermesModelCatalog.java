package com.bifos.assistant.hermes.dto;

import java.util.List;
import java.util.Map;

/**
 * profile 로 고를 수 있는 provider 와 모델이다.
 *
 * <p>{@code GET /api/model/options} 를 읽은 것이다. 인증되지 않았거나 모델이 없는 provider 는 담지 않는다.
 *
 * @param defaultProvider 그 profile 의 기본 provider. Hermes 가 주지 않으면 null
 * @param defaultModel 그 profile 의 기본 모델. Hermes 가 주지 않으면 null
 * @param providers 고를 수 있는 provider. Hermes 가 준 차례 그대로다
 */
public record HermesModelCatalog(String defaultProvider, String defaultModel, List<Provider> providers) {

    /**
     * @param slug Hermes 가 부르는 provider 이름
     * @param name 화면에 보일 이름. Hermes 가 주지 않으면 {@code slug} 다
     * @param models 그 provider 의 모델 이름. Hermes 가 준 차례 그대로다
     * @param reasoning 모델 이름을 열쇠로 한 reasoning 지원 여부. Hermes 가 밝히지 않은 모델은 없다
     */
    public record Provider(String slug, String name, List<String> models, Map<String, Boolean> reasoning) {
    }
}
