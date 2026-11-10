package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.Task;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 새 작업은 제목과 지시문을 빼고 넣은 뒤 줄 번호로 AAD 를 만들어 같은 트랜잭션에서 암호문을 적는다(ADR-20261008 /
 * data-encryption). 암호화가 꺼져 있으면 평문으로 넣는다. 이미 있는 작업은 합치기만 한다. 글을 고치는 쪽은 고친 뒤 직접 암호화한다.
 */
@RequiredArgsConstructor
public class TaskWritesImpl implements TaskWrites<Task> {

    private final EntityManager entityManager;
    private final TaskContents contents;


    @Override
    public <S extends Task> S save(S task) {
        if (task.id() != null) {
            return entityManager.merge(task);
        }
        if (!contents.sealing() || task.titleKeyId() != null) {
            entityManager.persist(task);
            return task;
        }
        task.detachPlainForSealing();
        entityManager.persist(task);
        if (task.id() == null) {
            entityManager.flush();
        }
        contents.seal(task);
        return task;
    }


    @Override
    public <S extends Task> S saveAndFlush(S task) {
        S saved = save(task);
        entityManager.flush();
        return saved;
    }


    @Override
    public <S extends Task> List<S> saveAll(Iterable<S> tasks) {
        List<S> saved = new ArrayList<>();
        tasks.forEach(task -> saved.add(save(task)));
        return saved;
    }


    @Override
    public <S extends Task> List<S> saveAllAndFlush(Iterable<S> tasks) {
        List<S> saved = saveAll(tasks);
        entityManager.flush();
        return saved;
    }
}
