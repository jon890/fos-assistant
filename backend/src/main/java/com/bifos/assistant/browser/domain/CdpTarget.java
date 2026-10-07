package com.bifos.assistant.browser.domain;

/**
 * 브라우저의 탭 하나다. CDP 의 {@code /json/list} 가운데 {@code type} 이 {@code page} 인 것만 담는다.
 *
 * @param id CDP 대상 번호. 영문자와 숫자만이다
 * @param title 탭 제목
 * @param url 탭 주소
 */
public record CdpTarget(String id, String title, String url) {}
