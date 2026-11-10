package com.bifos.assistant.task.infra;

import java.util.List;

/** JpaRepository 의 모든 저장 경로에서 새 작업의 제목과 지시문이 평문으로 쓰이지 않게 한다. */
public interface TaskWrites<T> {
    <S extends T> S save(S task);


    <S extends T> S saveAndFlush(S task);


    <S extends T> List<S> saveAll(Iterable<S> tasks);


    <S extends T> List<S> saveAllAndFlush(Iterable<S> tasks);
}
