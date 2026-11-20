package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 답 아래에 보이는, 넣기 도구가 「내 파일」 에 넣은 파일 하나다(ADR-20261119 / file-space-put).
 *
 * @param vaultItemId 보관본의 번호. 내려받기와 「내 파일에서 보기」 에 쓴다
 * @param path 보관본의 경로. 지웠으면 빈 글
 * @param byteSize 묶음 전체의 평문 바이트 수
 * @param deleted 「내 파일」 에서 지웠다
 * @param title 제목. 없거나 풀지 못하면 null
 */
public record PutFileDetail(Long vaultItemId, String path, long byteSize, boolean deleted, String title) {
}
