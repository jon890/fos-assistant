package com.bifos.assistant.usage.domain;

/**
 * 가격표에서 찾은 한 모델의 가격과 그 가격을 읽은 가격표의 버전이다.
 *
 * <p>둘을 따로 물으면 그 사이에 가격표가 다시 읽혀, 옛 가격으로 낸 금액에 새 버전이 붙을 수 있다. 그래서
 * 한 번의 조회로 함께 받는다. 금액과 버전이 같은 가격표에서 나와야 한다는 것은 ADR-004 가 정한다.
 */
public record CatalogPrice(ModelPrice price, String version) {
}
