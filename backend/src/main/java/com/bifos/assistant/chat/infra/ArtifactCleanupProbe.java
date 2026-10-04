package com.bifos.assistant.chat.infra;

/** 보관 기간 정리의 중간 시점에 끼어들어 동시 쓰기를 재현할 때 쓴다. 운영에서는 아무것도 하지 않는다. */
@FunctionalInterface
interface ArtifactCleanupProbe {
    /** 잠금 안에서 폴더가 기간을 넘겼다고 판정한 직후, 첫 파일을 지우기 전에 부른다. */
    void afterJudged(Long conversationId);
}
