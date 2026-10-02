package com.bifos.assistant.usage.domain;

/**
 * 실행 줄이 대화에서 읽는 값이다.
 *
 * <p>{@code usage} 가 대화 엔티티를 받으면 {@code usage} 가 {@code chat} 을 쓰게 되어 패키지 순환이 생긴다(ADR-068).
 * 번호와 문자열을 따로 받지 않고 record 로 묶은 것은 인자 자리를 바꿔도 컴파일되는 시그니처를 만들지 않기
 * 위해서다.
 *
 * @param id 대화 번호
 * @param reasoningEffort 대화가 고른 값이고 고르지 않았으면 null 이다
 */
public record ExecutionConversation(Long id, String reasoningEffort) {}
