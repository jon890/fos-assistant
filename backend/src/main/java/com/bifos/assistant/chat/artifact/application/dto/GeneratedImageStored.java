package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 비서가 만든 그림 하나를 대화 폴더에 새로 저장했다는 Spring 사건이다. 같은 그림을 되풀이해 올린 요청에는 내지 않는다.
 *
 * <p>대화 단위 SSE 로 화면에 알려 답이 끝나기 전에 그림을 보이게 한다(generated-image 설계의 PR7).
 *
 * @param conversationId 그림을 저장한 대화의 내부 번호
 * @param path 결과물 폴더 안의 그림 경로
 */
public record GeneratedImageStored(Long conversationId, String path) {
}
