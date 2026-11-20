package com.bifos.assistant.chat.contentdraft.application.dto;

/**
 * 원고에 넣을 수 있게 만든 그림 사본이다(ADR-20261120 / generated-image-draft).
 *
 * @param assetId 원고의 사진 자리에 쓰는 첨부 번호
 * @param fileName 초안의 사진 줄에 쓰는 파일 이름
 * @param photoDir 초안의 {@code photo_dir} 에 쓰는, Hermes 에서 보이는 사진 폴더
 * @param title 그림 제목
 */
public record ContentDraftImageView(String assetId, String fileName, String photoDir, String title) {
}
