package com.bifos.assistant.quality.application.dto;

/**
 * 평가 묶음 하나다. 「지금 돌리기」 가 고를 목록이다.
 *
 * @param timeoutMinutes 묶음 파일이 정한 문항 하나의 상한(분)
 */
public record EvalBundleView(String id, String title, int questionCount, int timeoutMinutes) {
}
