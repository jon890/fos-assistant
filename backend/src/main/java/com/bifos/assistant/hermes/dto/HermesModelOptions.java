package com.bifos.assistant.hermes.dto;

/**
 * profile 이 지금 기본으로 쓰는 provider 와 모델이다.
 *
 * <p>{@code GET /api/model/options} 를 읽은 것이다. provider 를 주지 않는 Hermes 판이 있어
 * {@code provider} 는 비어 있을 수 있다.
 *
 * @param model 그 profile 의 기본 모델. 읽지 못하면 null
 * @param provider 그 profile 의 기본 provider. 응답에 없으면 null
 */
public record HermesModelOptions(String model, String provider) {
}
