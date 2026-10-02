package com.bifos.assistant.usage.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 대화 번호를 공개 식별자로 바꾸는 조회다. 대화를 가진 쪽이 구현한다. */
public interface ConversationPublicIds {

    /** 대화 번호별 공개 식별자를 한 번에 읽는다. 지운 대화도 넣는다. 번호가 비면 빈 표를 돌려준다. */
    Map<Long, UUID> publicIdsOf(Collection<Long> conversationIds);
}
