package com.bifos.assistant.browser.domain;

/**
 * 브라우저 proxy 가 보여 준 브라우저 컨테이너 하나다.
 *
 * @param id 컨테이너 번호
 * @param profileKey 라벨에 적힌 프로필 키. 라벨이 없으면 비어 있다
 * @param running 켜져 있는가
 */
public record RuntimeContainer(String id, String profileKey, boolean running) {}
