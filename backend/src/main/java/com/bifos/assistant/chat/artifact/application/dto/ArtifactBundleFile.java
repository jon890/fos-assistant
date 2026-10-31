package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 묶음 안 파일 하나다.
 *
 * @param path 대화 폴더 안의 고른 상대 경로
 * @param byteSize 훑을 때의 평문 바이트 수
 * @param sha256 훑을 때 본문의 SHA-256 16진수. 저장할 때 다시 읽어 비교할 값이다
 */
public record ArtifactBundleFile(String path, long byteSize, String sha256) {
}
