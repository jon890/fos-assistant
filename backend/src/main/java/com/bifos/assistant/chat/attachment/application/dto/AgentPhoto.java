package com.bifos.assistant.chat.attachment.application.dto;

/**
 * 이번 메시지의 사진 한 장을 에이전트에게 어떻게 보일지다.
 *
 * @param attachmentId 첨부 번호
 * @param ordinal 그 대화에서 몇 번째 사진인지
 * @param dataUrl 실행 입력에 싣는 {@code data:image/jpeg;base64,...}. 싣지 않으면 null
 */
public record AgentPhoto(Long attachmentId, int ordinal, String dataUrl) {

    /** 실행 입력에 이미지로 실었으면 참이다. */
    public boolean embedded() {
        return dataUrl != null;
    }
}
