package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.purge.application.port.ConversationNativeResultPurge;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 지운 대화의 내장 위임 결과 글을 비운다(ADR-20261117 / native-delegation-wake). 줄과 상태와 시각은 남긴다. */
@Component
@RequiredArgsConstructor
public class NativeDelegationResultPurge implements ConversationNativeResultPurge {

    private final HermesSessionBindingRepository bindings;


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseBodies(Long conversationId) {
        bindings.clearTextsOfConversation(conversationId);
    }
}
