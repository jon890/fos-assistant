package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.runtime.application.dto.RunCommand;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.function.Consumer;

public record PendingTurn(
    CurrentUser user,
    Conversation conversation,
    Agent agent,
    RunCommand command,
    AgentExecution execution,
    SequenceCounter counter,
    StringBuilder streamed,
    TurnIntent intent,
    Consumer<ChatEvent> onEvent) {
}
