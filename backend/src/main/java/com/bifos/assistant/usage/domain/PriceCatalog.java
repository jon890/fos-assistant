package com.bifos.assistant.usage.domain;

import java.util.Optional;

/** provider 와 모델의 단가를 찾는다. */
public interface PriceCatalog {

    /** 그 provider 와 모델의 가격이 없으면 비어 있다. */
    Optional<ModelPrice> find(String provider, String model);

    /**
     * 가격표와 그것을 받아 온 시점을 적는다. 예를 들면 {@code models.dev@2026-09-17} 이다. 금액마다 함께
     * 저장하므로 나중에 가격이 바뀌어도 지난 기록이 다시 계산되지 않는다.
     */
    String version();

    /** 카탈로그를 읽어 조회에 답할 수 있으면 true 다. */
    boolean isAvailable();
}
