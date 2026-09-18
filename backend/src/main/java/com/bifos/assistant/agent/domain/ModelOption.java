package com.bifos.assistant.agent.domain;

/**
 * 실행 하나에 쓸 provider 와 모델의 짝이다.
 *
 * <p>Hermes 는 둘을 함께 받아야 한다. {@code provider} 만 주면 config 의 모델 문자열을 새 provider 에
 * 그대로 넘겨 {@code No LLM provider configured} 로 끝난다. 그래서 이 둘을 떼어 다루지 않는다.
 *
 * @param provider Hermes 가 아는 provider 이름
 * @param model 그 provider 의 모델 이름
 */
public record ModelOption(String provider, String model) {

    public ModelOption {
        provider = provider == null ? null : provider.strip();
        model = model == null ? null : model.strip();
    }

    /** provider 와 모델이 모두 채워져 있는가. 하나라도 비면 Hermes 를 부르지 않는다. */
    public boolean complete() {
        return provider != null && !provider.isBlank() && model != null && !model.isBlank();
    }

    /** 화면과 사건에 적는 한 줄 표기다. */
    public String label() {
        return provider + "/" + model;
    }
}
