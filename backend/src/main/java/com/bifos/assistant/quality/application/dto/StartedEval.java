package com.bifos.assistant.quality.application.dto;

/**
 * 평가를 시작한 결과다.
 *
 * @param created 새로 시작했으면 참. 이미 도는 평가를 돌려줬으면 거짓
 */
public record StartedEval(EvalView eval, boolean created) {
}
