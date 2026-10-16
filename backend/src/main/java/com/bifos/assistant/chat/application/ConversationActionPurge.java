package com.bifos.assistant.chat.application;

import java.time.Instant;
import java.util.List;

/** 승인 실행과 복구에 필요한 본문을 보호하면서 대화의 커넥터 개인 본문만 지우는 경계다. */
public interface ConversationActionPurge {
    /** 사용자·대화 잠금 안에서 안전한 줄의 본문만 지우고 처리한 승인 줄 수를 돌려준다. */
    int eraseBodies(Long conversationId, Instant now);


    /** 예전 purge가 남긴 본문도 정리한다. 아직 대화 자체를 purge하지 않은 후보는 호출자가 따로 읽는다. */
    List<Long> findPurgedCandidates(List<Long> skipped, int limit, Instant now);
}
