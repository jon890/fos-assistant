package com.bifos.assistant.chat.conversation.application.dto;

import java.net.URI;

/**
 * 대화에서 확인된 그림 참고 주소다.
 *
 * @param uri 받을 주소. 읽은 페이지에 고를 사진이 없을 때만 null 이다
 * @param fromReadPage 이미 읽은 페이지에서 골랐을 때 참이다
 */
public record FoundImageLink(URI uri, boolean fromReadPage) {
}
