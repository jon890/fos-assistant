package com.bifos.assistant.browser.application.dto;

/**
 * 승인 카드가 가리키는 탭이다. 탭 번호와, 카드를 띄울 때 그 탭 주소의 지문이다.
 *
 * @param urlHash {@code BrowserTabControl.urlHash} 의 값. 실행 때 지금 주소와 비교해 탭이 다른 페이지로 갔으면 건너뛴다
 */
public record TabRef(String id, String urlHash) {
}
