package com.bifos.assistant.context;

/** 문맥 항목의 본문을 싣는 방식이다(ADR-071). */
public enum ContextBodyMode {
    /** 본문을 그대로 싣는다. */
    INLINE,
    /** 제목만 싣는다. 본문은 도구로 읽는다. */
    TITLE_ONLY,
    /** 예산이나 민감도 때문에 싣지 않았다. */
    OMITTED
}
