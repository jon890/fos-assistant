package com.bifos.assistant.chat.infra;

/**
 * 보관 기간이 지나 지운 파일 하나다.
 *
 * @param path 대화 폴더 안의 상대 경로. {@code /} 로 나눈다
 */
public record ArtifactRemoved(Long conversationId, String path) {

    public boolean isHtml() {
        return ArtifactStore.HTML.equals(ArtifactStore.extensionOf(path));
    }
}
