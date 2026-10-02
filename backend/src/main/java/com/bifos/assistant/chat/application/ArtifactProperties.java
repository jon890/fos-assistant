package com.bifos.assistant.chat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 에이전트가 turn 안에 만든 결과물 파일을 어디에 얼마나 둘지 정한다.
 *
 * <p>사진 첨부와 루트를 따로 둔다. 사진은 행 단위로 지우고 결과물은 폴더 안 파일 단위로 지워 보관과 지우는
 * 대상이 다르다. {@code root} 에 기본값을 두지 않는 까닭은 첨부와 같다. 기본값이 있으면 공유 디렉터리를 붙이지
 * 않은 채 배포해도 기동이 성공하고, 에이전트가 쓴 파일을 Control Plane 이 보지 못한다. 근거는 ADR-027 에
 * 있다.
 *
 * @param root 결과물 폴더의 루트. Control Plane 컨테이너에서 보이는 경로다. 비어 있으면 기동을 멈춘다
 * @param agentRoot 같은 디렉터리를 Hermes 컨테이너에서 보는 경로. 에이전트에게 폴더 자리를 알릴 때 이것을
 *     적는다. 비어 있으면 기동을 멈춘다
 * @param retentionDays 파일이 마지막으로 바뀐 뒤 두는 날 수
 */
@Validated
@ConfigurationProperties(prefix = "assistant.artifact")
public record ArtifactProperties(String root, String agentRoot, Integer retentionDays) {

    private static final int DEFAULT_RETENTION_DAYS = 30;

    public ArtifactProperties {
        // 설정의 자리표시자는 환경 변수가 아예 없을 때만 기동을 멈춘다. 빈 문자열은 여기서 막는다.
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("assistant.artifact.root is required");
        }
        if (agentRoot == null || agentRoot.isBlank()) {
            throw new IllegalStateException("assistant.artifact.agent-root is required");
        }
        retentionDays = retentionDays == null ? DEFAULT_RETENTION_DAYS : retentionDays;
    }
}
