package com.bifos.assistant.chat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대화에 올리는 사진을 어디에 얼마나 둘지 정한다.
 *
 * <p>{@code root} 에 기본값을 두지 않는다. 기본값이 있으면 공유 디렉터리를 붙이지 않은 채 배포해도
 * 기동이 성공하고, 사진이 컨테이너 안에만 쌓여 에이전트가 보지 못한다. 근거는 ADR-020 에 있다.
 *
 * @param root 사진을 두는 디렉터리 뿌리. Control Plane 컨테이너에서 보이는 경로다. 비어 있으면 기동을
 *     멈춘다
 * @param agentRoot 같은 디렉터리를 Hermes 컨테이너에서 보는 경로. 두 컨테이너가 같은 디렉터리를 다른
 *     마운트 지점으로 볼 수 있어 따로 받는다. 에이전트에게 사진 자리를 알릴 때 이것을 적는다. 비어 있으면
 *     기동을 멈춘다
 * @param maxFiles 한 번에 보낼 수 있는 장수
 * @param maxBytes 한 장의 상한
 * @param retentionDays 올린 뒤 파일을 두는 날 수
 */
@ConfigurationProperties(prefix = "assistant.attachment")
public record AttachmentProperties(
        String root, String agentRoot, Integer maxFiles, Long maxBytes, Integer retentionDays) {

    private static final int DEFAULT_MAX_FILES = 10;
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    private static final int DEFAULT_RETENTION_DAYS = 30;

    public AttachmentProperties {
        // 설정의 자리표시자는 환경 변수가 아예 없을 때만 기동을 멈춘다. 빈 문자열은 여기서 막는다.
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("assistant.attachment.root is required");
        }
        if (agentRoot == null || agentRoot.isBlank()) {
            throw new IllegalStateException("assistant.attachment.agent-root is required");
        }
        maxFiles = maxFiles == null ? DEFAULT_MAX_FILES : maxFiles;
        maxBytes = maxBytes == null ? DEFAULT_MAX_BYTES : maxBytes;
        retentionDays = retentionDays == null ? DEFAULT_RETENTION_DAYS : retentionDays;
    }
}
