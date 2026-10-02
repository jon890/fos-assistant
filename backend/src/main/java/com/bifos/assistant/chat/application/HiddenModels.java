package com.bifos.assistant.chat.application;

import java.util.List;

/**
 * 한 그룹이 숨긴 provider 와 모델이다.
 *
 * @param entries 숨긴 항목. provider 이름, 모델 이름 차례다
 */
public record HiddenModels(List<Entry> entries) {

    /**
     * @param provider 숨긴 provider 이름
     * @param model 숨긴 모델 이름. null 이면 그 provider 전체다
     */
    public record Entry(String provider, String model) {}

    public HiddenModels {
        entries = List.copyOf(entries);
    }

    public static HiddenModels none() {
        return new HiddenModels(List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** provider 전체를 숨겼는가. */
    public boolean hidesProvider(String provider) {
        return entries.stream()
                .anyMatch(entry -> entry.model() == null && entry.provider().equals(provider));
    }

    /** 그 모델이 숨겨졌는가. provider 전체를 숨긴 것도 포함한다. */
    public boolean hides(String provider, String model) {
        return entries.stream()
                .anyMatch(entry -> entry.provider().equals(provider)
                        && (entry.model() == null || entry.model().equals(model)));
    }

    /**
     * profile 의 기본 모델이 숨겨졌는가. 요청에 모델을 싣지 않는 실행이 실제로 돌 모델을 판정할 때 쓴다.
     *
     * <p>Hermes 가 기본 모델을 주지 않으면 견줄 값이 없어 거짓이다. 기본 provider 만 주지 않으면 모델 이름이 같은
     * 항목으로 본다. 이때 provider 전체를 숨긴 항목은 어느 provider 인지 알 수 없어 견주지 않는다.
     */
    public boolean hidesProfileDefault(String provider, String model) {
        if (model == null) {
            return false;
        }
        if (provider != null) {
            return hides(provider, model);
        }
        return entries.stream().anyMatch(entry -> model.equals(entry.model()));
    }
}
