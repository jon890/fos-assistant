package com.bifos.assistant.chat.infra;

import java.util.List;

/** JpaRepository 의 모든 저장 경로에서 대화 줄이 사라지지 않게 한다. */
public interface ChatMessageWrites<T> {
    <S extends T> S save(S message);
    <S extends T> S saveAndFlush(S message);
    <S extends T> List<S> saveAll(Iterable<S> messages);
    <S extends T> List<S> saveAllAndFlush(Iterable<S> messages);
}
