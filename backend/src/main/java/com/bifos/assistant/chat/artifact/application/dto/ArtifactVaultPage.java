package com.bifos.assistant.chat.artifact.application.dto;

import java.util.List;

/**
 * 「내 파일」 목록의 한 쪽과 용량이다(스펙 workspace REQ10, REQ17).
 *
 * @param nextCursor 다음 쪽이 있으면 이 쪽 마지막 번호. 없으면 null
 * @param usedBytes 쓴 용량
 * @param quotaBytes 사용자 용량
 * @param full 남은 용량이 파일 하나 상한보다 작다
 */
public record ArtifactVaultPage(
    List<ArtifactVaultEntry> items, Long nextCursor, long usedBytes, long quotaBytes, boolean full) {
}
