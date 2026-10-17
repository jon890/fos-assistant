package com.bifos.assistant.memory.domain.type;

/**
 * 기억 두 개 사이의 연결 종류다(ADR-20261015 / memory-link).
 *
 * <p>Control Plane 이 다르게 동작해야 하는 둘만 둔다. 연결의 뜻(「이 목표의 일부」 같은 것)은 {@code RELATED} 의 자유 글 label 이 갖는다.
 */
public enum MemoryLinkType {
    /** 새 항목({@code from})이 옛 항목({@code to})을 대신한다. 늘 제안으로 만들고 사람이 받아들여야 효력이 있다. */
    SUPERSEDES,
    /** 두 항목이 관련 있다. 방향이 없고 번호가 작은 쪽이 {@code from} 이다. */
    RELATED
}
