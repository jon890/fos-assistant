package com.bifos.assistant.chat.artifact.application.dto;

import java.time.Instant;

/**
 * 보관한 결과물 하나다. 묶음이면 뿌리 파일 하나로 보인다.
 *
 * @param id 보관본의 번호. 이 번호로 읽고 지운다
 * @param path 보관할 때 대화 폴더 안에서의 뿌리 파일 상대 경로
 * @param byteSize 묶음 전체의 평문 바이트 수
 * @param fileCount 묶음의 파일 수. 뿌리를 센다. 묶음이 아니면 1 이다
 * @param vaultedAt 보관한 시각
 */
public record ArtifactVaultItem(Long id, String path, long byteSize, int fileCount, Instant vaultedAt) {
}
