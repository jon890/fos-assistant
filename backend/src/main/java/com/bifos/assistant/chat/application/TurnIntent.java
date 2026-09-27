package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;

/** 이 turn 이 새 질문인지, 마지막 답의 다시 생성인지. */
public sealed interface TurnIntent {

    String REGENERATE_INSTRUCTION =
            "사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다.";

    record Fresh() implements TurnIntent {}

    record Regenerate(ChatMessage previousAnswer, ChatMessage question) implements TurnIntent {}

    static String instructionFor(TurnIntent intent) {
        if (intent instanceof Regenerate regenerate && regenerate.previousAnswer() != null) {
            return REGENERATE_INSTRUCTION;
        }
        return null;
    }

    static String appendTo(String instructions, TurnIntent intent) {
        String addition = instructionFor(intent);
        if (addition == null) {
            return instructions;
        }
        return instructions == null || instructions.isBlank() ? addition : instructions + "\n\n" + addition;
    }
}
