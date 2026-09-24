package com.bifos.assistant.chat.application;

import java.io.InputStream;

/**
 * 돌려줄 사진 한 장의 본문이다. 받은 쪽이 {@code body} 를 닫는다.
 *
 * @param contentType 올릴 때 저장한 형식
 * @param byteSize 본문의 바이트 수
 * @param body 파일에서 읽는 스트림
 */
public record AttachmentContent(String contentType, long byteSize, InputStream body) {
}
