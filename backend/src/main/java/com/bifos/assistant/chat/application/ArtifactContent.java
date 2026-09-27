package com.bifos.assistant.chat.application;

import java.io.InputStream;

/**
 * 돌려줄 결과물 파일 하나의 본문이다. 받은 쪽이 {@code body} 를 닫는다.
 *
 * @param contentType 확장자로 정한 형식
 * @param byteSize 본문의 바이트 수
 * @param body 파일에서 읽는 스트림
 */
public record ArtifactContent(String contentType, long byteSize, InputStream body) {
}
