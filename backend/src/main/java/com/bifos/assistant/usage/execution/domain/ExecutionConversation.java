package com.bifos.assistant.usage.execution.domain;

/**
 * 실행 줄이 대화에서 읽는 값이다.
 *
 * <p>{@code usage} 가 대화 엔티티를 받으면 {@code usage} 가 {@code chat} 을 쓰게 되어 패키지 순환이 생긴다(ADR-068).
 * 대화 번호를 {@code Long} 으로 그대로 받으면 같은 시그니처의 {@code parentExecutionId}, {@code rootExecutionId} 와
 * 자리가 바뀌어도 컴파일된다. 타입으로 구분해 그것을 막는다.
 *
 * @param id 대화 번호
 */
public record ExecutionConversation(Long id) {
}
