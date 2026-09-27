package com.bifos.assistant.usage.domain;

import java.util.Optional;

/** provider 와 모델의 단가를 찾는다. */
public interface PriceCatalog {

    /** 그 provider 와 모델의 가격과, 그 가격을 읽은 가격표의 버전이다. 가격이 없으면 비어 있다. */
    Optional<CatalogPrice> find(String provider, String model);

    /**
     * 지금 들고 있는 가격표와 그것을 받아 온 시점이다. 예를 들면 {@code models.dev@2026-09-17} 이다.
     *
     * <p>금액에 적을 버전은 이것을 따로 부르지 말고 {@link #find} 가 돌려준 {@link CatalogPrice#version()} 을
     * 쓴다. 두 번 물으면 그 사이에 가격표가 다시 읽혀 옛 가격으로 낸 금액에 새 버전이 붙을 수 있다.
     */
    String version();

    /** 카탈로그를 읽어 조회에 답할 수 있으면 true 다. */
    boolean isAvailable();
}
