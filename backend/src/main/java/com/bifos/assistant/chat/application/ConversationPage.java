package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import java.util.List;

/**
 * 대화 목록의 한 쪽이다.
 *
 * @param items 최근에 바뀐 것부터 담은 대화
 * @param nextCursor 다음 쪽을 읽을 때 {@code cursor} 로 넘기는 값. 마지막 쪽이면 null
 */
public record ConversationPage(List<Conversation> items, String nextCursor) {}
