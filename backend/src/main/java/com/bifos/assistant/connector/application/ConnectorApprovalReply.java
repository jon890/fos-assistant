package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;

/** 승인 줄에 대한 hook 응답을 조립한다. 승인 여부와 실행 가능 여부는 호출하는 서비스가 판정한다. */
final class ConnectorApprovalReply {
    /**
     * 승인을 기다리는 호출에 답하는 글이다. 모델이 읽는다.
     *
     * <p>약속은 실제 동작과 같아야 한다. 대화 화면에 승인 카드가 뜨고, 승인하면 저장한 인자로 한 번 실행한 결과가 그
     * 대화의 다음 turn 으로 온다({@link ConnectorActionResultSource}). 번호는 줄을 가리키는 값이라 사용자에게 읽어 줄
     * 것이 아니다.
     */
    private static final String APPROVAL_MESSAGE = "이 동작은 사용자의 승인이 필요하다. 승인 요청 번호는 %s 다. "
            + "대화 화면에 승인 카드가 떴으니 사용자에게 거기서 승인해 달라고 알린다. 번호는 사용자에게 말하지 않는다. "
            + "같은 도구를 다시 부르지 않는다. 승인하면 저장한 인자 그대로 한 번 실행되고 결과가 이 대화로 온다.";

    /** 대화 없이 돈 실행은 승인 카드가 뜰 화면이 없다. 승인받을 수 있다고 약속하지 않는다. */
    private static final String APPROVAL_WITHOUT_CONVERSATION_MESSAGE =
            "이 동작은 사용자의 승인이 필요하다. 승인 요청 번호는 %s 다. " + "이 실행은 대화가 없어 승인받을 화면이 없으므로 실행되지 않는다. 같은 도구를 다시 부르지 않는다.";

    private ConnectorApprovalReply() {}

    static ConnectorPolicyAnswer of(ConnectorAction action) {
        String message = action.conversationId() == null ? APPROVAL_WITHOUT_CONVERSATION_MESSAGE : APPROVAL_MESSAGE;
        return new ConnectorPolicyAnswer(false, String.format(message, action.publicId()), action.publicId());
    }
}
