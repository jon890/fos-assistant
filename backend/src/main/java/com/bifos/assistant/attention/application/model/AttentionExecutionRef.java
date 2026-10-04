package com.bifos.assistant.attention.application.model;

/**
 * 맡긴 일 항목의 실행이다. 화면이 항목 열쇠를 잘라 실행 번호를 얻지 않게 따로 낸다.
 *
 * @param id 실행 번호
 * @param status 실행 상태 이름({@code RUNNING}, {@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED})
 */
public record AttentionExecutionRef(Long id, String status) {}
