package com.bifos.assistant.chat.conversation.application.dto;

import java.net.URI;

/**
 * 대화에서 확인된 그림 참고 주소다.
 *
 * @param uri 받을 주소. 읽은 페이지에 고를 사진이 없을 때만 null 이다
 * @param directPhoto 읽은 페이지에서 고른 사진 주소면 참이다. 다음 장면에서 그 주소를 다시 가리킬 때도 참이다
 */
public record FoundImageLink(URI uri, boolean directPhoto) {
}
