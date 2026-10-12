package com.bifos.assistant.chat.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/** bindingId 를 생략한 preview 에서 바인딩을 하나로 정하지 못했다. 메시지는 내부 값 없는 고정 문구다. */
public class ContentDraftBindingChoiceException extends ApiException {
    public static final String NONE = "이 에이전트에 원고 저장을 지원하는 연결이 없다. 사용자에게 원고 저장을 지원하는 연결을 이 에이전트에 붙여 달라고 안내한다.";
    public static final String MANY = "이 에이전트에 원고 저장을 지원하는 연결이 여럿이다. bindingId 를 지정한다.";


    public ContentDraftBindingChoiceException(String reason) {
        super(ErrorCode.VALIDATION_FAILED, reason);
    }
}
