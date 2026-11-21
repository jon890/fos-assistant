package com.bifos.assistant.chat.conversation.application.dto;

import java.util.List;

/**
 * 비서의 웹 도구 결과 한 덩어리에서 뽑은 주소 후보다(ADR-20261121 / found-link-image-source).
 *
 * @param base 상대 주소를 풀 기준 주소. 읽은 페이지의 주소이고, 검색 결과처럼 기준이 없으면 null 이다
 * @param links 결과에 적힌 그대로의 주소 후보
 * @param requested 상품 페이지 읽기에서 요청한 주소. 다른 주소로 이동했을 때에도 검색 당시의 주소로 사진을 고를 수 있게 한다
 * @param images 읽은 페이지의 사진 후보. 사진 후보를 알리지 않는 옛 호출이면 null 이다
 */
public record FoundPage(String base, List<String> links, String requested, List<FoundImage> images) {

    public FoundPage(String base, List<String> links) {
        this(base, links, null, null);
    }
}
