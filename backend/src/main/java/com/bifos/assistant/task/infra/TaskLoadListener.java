package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.Task;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.stereotype.Component;

/**
 * 읽어 온 예약 작업에 복호화 도구를 붙인다(ADR-20261008 / data-encryption).
 *
 * <p>여기서는 풀지 않는다. 제목과 지시문은 {@link Task#title()} 과 {@link Task#instruction()} 을 처음 부를 때 푼다. 엔티티에
 * 리스너 주석을 달면 domain 이 infra 를 가리키게 되어 Hibernate 의 사건 목록에 직접 더한다.
 */
@Component
@RequiredArgsConstructor
class TaskLoadListener implements PostLoadEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final TaskContents contents;


    @PostConstruct
    void register() {
        entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getEventListenerRegistry()
            .appendListeners(EventType.POST_LOAD, this);
    }


    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (event.getEntity() instanceof Task task) {
            task.attachOpener(contents);
        }
    }
}
