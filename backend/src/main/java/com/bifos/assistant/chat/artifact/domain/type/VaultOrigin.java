package com.bifos.assistant.chat.artifact.domain.type;

/**
 * 보관 행이 들어온 길이다(ADR-20261119 / file-space-put). 자동 보관과 「보관」 단추로 들어온 행은 값이 없다.
 *
 * <p>둘을 나누는 까닭은 경로의 이름 공간이 달라서다. 실행 공간의 {@code a/b.png} 와 대화 폴더의 {@code a/b.png} 는 다른 파일이다.
 */
public enum VaultOrigin {

    /** 에이전트의 넣기 도구가 대화의 결과물 폴더에서 읽어 넣었다. */
    PUT,

    /** 에이전트의 넣기 도구가 실행 공간에서 읽어 넣었다. 경로는 실행 공간 안의 상대 경로다. */
    PUT_WORKSPACE
}
