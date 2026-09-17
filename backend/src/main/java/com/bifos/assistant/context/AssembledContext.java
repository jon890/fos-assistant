package com.bifos.assistant.context;

/** 조립한 문자열과 그 길이. 길이를 실행 기록에 남긴다. */
public record AssembledContext(String instructions, long chars) {

    /** 넣을 항목이 없으면 빈 문자열 대신 null 을 보낸다. */
    public static AssembledContext empty() {
        return new AssembledContext(null, 0);
    }
}
