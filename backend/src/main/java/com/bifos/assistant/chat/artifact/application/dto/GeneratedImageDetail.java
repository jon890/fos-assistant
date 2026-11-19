package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 답에 묶인 그림 하나를 화면에 보일 때 더하는 값이다.
 *
 * @param prompt 그림 지시문. 풀지 못하면 null
 * @param vaultItemId 그 그림의 살아 있는 보관본 번호. 없으면 null
 */
public record GeneratedImageDetail(String prompt, Long vaultItemId) {
}
