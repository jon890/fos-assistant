package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.function.Consumer;

record PendingTurn(
    CurrentUser user,
    Conversation conversation,
    Agent agent,
    HermesRunCommand command,
    AgentExecution execution,
    SequenceCounter counter,
    StringBuilder streamed,
    TurnIntent intent,
    Consumer<ChatEvent> onEvent) {
}
