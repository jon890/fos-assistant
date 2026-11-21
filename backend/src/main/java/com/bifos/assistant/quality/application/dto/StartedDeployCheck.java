package com.bifos.assistant.quality.application.dto;

/**
 * 확인을 시작한 결과다.
 *
 * @param created 새로 시작했으면 참. 이미 도는 확인을 돌려줬으면 거짓
 */
public record StartedDeployCheck(DeployCheckView check, boolean created) {
}
