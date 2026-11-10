package com.bifos.assistant.task.domain;

/**
 * 암호화해 저장한 예약 작업의 제목과 지시문을 푼다(ADR-20261008 / data-encryption).
 *
 * <p>작업을 읽어 올 때 엔티티에 붙이고, 글을 처음 꺼낼 때 한 번 부른다. key 가 없거나 암호문이 맞지 않아 풀지 못하면
 * {@link Task#UNREADABLE_CONTENT} 를 돌려준다.
 */
public interface TaskContentOpener {

    String openTitle(Task task);


    String openInstruction(Task task);
}
