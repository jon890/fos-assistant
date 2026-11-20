package com.bifos.assistant.quality.domain.type;

/** 배포 뒤 확인이 도는 기능이다. 목록과 통과 규칙은 스펙 agent-quality 의 「배포 뒤 확인 기능」 이 갖는다. */
public enum QualityFeature {
    /** 검색과 출처. */
    SEARCH,
    /** Control Plane MCP 의 {@code agent_delegate} 위임. */
    DELEGATION,
    /** 내 파일 넣기({@code file_space_put}). */
    FILE_PUT,
    /** 그림 만들기. */
    IMAGE,
    /** 미리 넣은 기억 읽기. */
    MEMORY_READ,
    /** 예약 작업 발화. */
    TASK_FIRE,
    /** 원본 사진 조회({@code attachment_inspect}). */
    PHOTO_INSPECT
}
