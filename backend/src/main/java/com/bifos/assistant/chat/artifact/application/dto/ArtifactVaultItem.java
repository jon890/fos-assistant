package com.bifos.assistant.chat.artifact.application.dto;

import java.time.Instant;

/**
 * 보관한 결과물 하나다.
 *
 * @param id 보관 사본의 번호. 이 번호로 읽고 지운다
 * @param path 보관할 때 대화 폴더 안에서의 상대 경로
 * @param byteSize 평문 바이트 수
 * @param vaultedAt 보관한 시각
 */
public record ArtifactVaultItem(Long id, String path, long byteSize, Instant vaultedAt) {
}
