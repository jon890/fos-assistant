package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.memory.domain.type.MemorySensitivity;

/**
 * 에이전트가 남기려는 사실 하나다. 모양 검사를 마친 값이다.
 *
 * @param collection 둘 collection. 고르지 않았으면 기본 collection 이다
 * @param memoryId 같은 사실을 고칠 기존 항목 번호. 새 항목이면 null 이다
 * @param executionId 이 호출의 origin 실행 번호
 * @param conversationId 그 실행의 대화 번호. 대화 밖의 실행이면 null 이다
 * @param direct 바로 저장 조건을 모두 만족하는가. 민감도와 collection 은 여기서 다시 본다
 */
public record MemoryRememberRequest(
        String title,
        String content,
        String collection,
        MemorySensitivity sensitivity,
        Long memoryId,
        Long executionId,
        Long conversationId,
        boolean direct) {

    /** 제목과 본문을 빼고 낸다. 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemoryRememberRequest[collection=" + collection + ", sensitivity=" + sensitivity + ", memoryId="
                + memoryId + ", executionId=" + executionId + ", direct=" + direct + "]";
    }
}
