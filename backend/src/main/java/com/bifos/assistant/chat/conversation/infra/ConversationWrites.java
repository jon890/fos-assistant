package com.bifos.assistant.chat.conversation.infra;

import java.util.List;

/** JpaRepository 의 모든 저장 경로에서 새 대화의 제목이 평문으로 쓰이지 않게 한다. */
public interface ConversationWrites<T> {
    <S extends T> S save(S conversation);


    <S extends T> S saveAndFlush(S conversation);


    <S extends T> List<S> saveAll(Iterable<S> conversations);


    <S extends T> List<S> saveAllAndFlush(Iterable<S> conversations);
}
