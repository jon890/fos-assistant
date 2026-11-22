package com.bifos.assistant.quality.application.dto;

import com.bifos.assistant.quality.domain.QualityRun;

/**
 * 평가 줄을 열어 본 결과다.
 *
 * @param created 새로 만들었으면 참. 이미 도는 평가를 돌려줬으면 거짓
 */
public record OpenedEval(QualityRun run, boolean created) {
}
