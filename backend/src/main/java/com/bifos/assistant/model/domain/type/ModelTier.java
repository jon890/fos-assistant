package com.bifos.assistant.model.domain.type;

/**
 * 입력창에서 고르는 모델 칸이다. ChatGPT 의 Thinking 슬라이더 칸을 이 차례대로 옮겼다(ADR-20261101 / model-setting).
 *
 * <p>지난 단계 이름({@code FAST}, {@code BALANCED}, {@code DEEP})은 실행 기록에만 남는다. 요청으로 오면 받지 않는다.
 */
public enum ModelTier {
    INSTANT,
    MEDIUM,
    HIGH,
    EXTRA_HIGH,
    PRO
}
