package com.bifos.assistant.workspace.domain;

/** 실행 공간 목록 한 줄의 종류다. 링크를 따라가지 않은 속성으로 정한다. */
public enum WorkspaceEntryKind {
    DIRECTORY,
    /** 일반 파일. 하드 링크가 하나이고 읽을 수 있을 때만 본문을 준다. */
    FILE,
    /** 심볼릭 링크. 가리키는 곳을 읽지 않는다. */
    LINK,
    /** FIFO, 소켓, 장치. 본문을 주지 않는다. */
    OTHER
}
