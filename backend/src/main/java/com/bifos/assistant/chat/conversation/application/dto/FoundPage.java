package com.bifos.assistant.chat.conversation.application.dto;

import java.util.List;

/**
 * 비서의 웹 도구 결과 한 덩어리에서 뽑은 주소 후보다(ADR-20261121 / found-link-image-source).
 *
 * @param base 상대 주소를 풀 기준 주소. 읽은 페이지의 주소이고, 검색 결과처럼 기준이 없으면 null 이다
 * @param links 결과에 적힌 그대로의 주소 후보
 */
public record FoundPage(String base, List<String> links) {
}
