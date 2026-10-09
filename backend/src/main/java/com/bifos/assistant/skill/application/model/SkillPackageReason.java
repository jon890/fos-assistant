package com.bifos.assistant.skill.application.model;

/**
 * 스킬 묶음을 받거나 검사할 때 난 문제의 까닭이다. 미리보기 응답의 {@code reason} 값이고 저장하지 않는다.
 *
 * <p>{@code NOT_ZIP} 부터 {@code UNSAFE_ENTRY} 까지는 받기가 내고, 나머지는 검사가 낸다(ADR-20261009-skill-package).
 */
public enum SkillPackageReason {
    /** zip 으로 읽히지 않는다. */
    NOT_ZIP,
    /** zip 바이트가 받기의 상한을 넘는다. */
    ZIP_TOO_LARGE,
    /** 디렉터리를 포함한 항목 수가 받기의 상한을 넘는다. */
    TOO_MANY_ENTRIES,
    /** 실제로 푼 바이트의 합계가 받기의 상한을 넘는다. */
    UNPACKED_TOO_LARGE,
    /** 특수 항목, 암호, 받지 않는 압축 방식, 깨진 압축과 CRC 불일치, 위험한 경로, 같은 경로 둘. */
    UNSAFE_ENTRY,
    /** 맨 위 {@code SKILL.md} 가 없다. */
    NO_SKILL_MD,
    /** 경로 규칙에 맞지 않는다. */
    PATH_NOT_ALLOWED,
    /** 맨 위가 아닌 자리에 {@code SKILL.md} 가 있다. */
    NESTED_SKILL_MD,
    /** UTF-8 글이 아니다. */
    NOT_TEXT,
    /** 파일 하나가 크기 상한을 넘는다. */
    FILE_TOO_LARGE,
    /** 파일 수가 상한을 넘는다. */
    TOO_MANY_FILES,
    /** 파일 크기의 합계가 상한을 넘는다. */
    TOTAL_TOO_LARGE,
    /** 비밀값처럼 보이는 글이 있다. */
    SECRET_VALUE,
    /** 앞머리를 읽지 못한다. */
    FRONTMATTER_INVALID,
    /** 이름이 이름 규칙에 맞지 않는다. */
    NAME_INVALID,
    /** 앞머리 뒤에 본문이 없다. */
    NO_BODY,
    /** 앞머리에 비밀 요청 칸이 있다. */
    SECRET_REQUEST,
    /** 설명이 1024자를 넘거나, 새 스킬인데 60자를 넘는다. */
    DESCRIPTION_TOO_LONG,
    /** 새 스킬인데 Hermes 기본 스킬과 이름이 같다. */
    NAME_TAKEN,
    /** 새 스킬인데 올린 스킬이 한도에 닿았다. */
    LIMIT_REACHED,
    /** {@code scripts/} 가 있는데 {@code terminal} 이 꺼져 있다. */
    SCRIPTS_NEED_SANDBOX
}
