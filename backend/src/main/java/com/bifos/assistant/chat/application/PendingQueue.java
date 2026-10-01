package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatPendingMessage;
import java.util.List;

/** 한 대화의 대기 줄이다. {@code held} 가 참이면 사용자가 풀 때까지 보내지 않는다. */
public record PendingQueue(boolean held, List<ChatPendingMessage> items) {}
