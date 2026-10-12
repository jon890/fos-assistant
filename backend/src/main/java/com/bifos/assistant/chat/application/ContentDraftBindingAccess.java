package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.shared.auth.CurrentUser;

/** HTTP 선언 준비와 잠금 안의 DB 권한 확인을 분리한다. */
public interface ContentDraftBindingAccess {
    ContentDraftBindingStamp prepare(CurrentUser user, Long agentId, Long bindingId);


    ContentDraftBindingStamp requireCurrent(CurrentUser user, Long agentId, Long bindingId);


    void requireUnchanged(CurrentUser user, ContentDraftBindingStamp stamp);


    /**
     * 요청자 에이전트에 붙은 요청자 연결 가운데 snapshot 을 선언한 커넥터의 바인딩이 정확히 하나면 그 번호다.
     * 트랜잭션 밖에서 부른다.
     *
     * @throws com.bifos.assistant.chat.domain.ContentDraftBindingChoiceException 0개이거나 2개 이상일 때
     */
    Long soleSnapshotBinding(CurrentUser user, Long agentId);
}
