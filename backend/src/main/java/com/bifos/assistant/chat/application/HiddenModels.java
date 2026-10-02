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
}
