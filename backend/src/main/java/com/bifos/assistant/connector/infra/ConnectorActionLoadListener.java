package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorAction;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.stereotype.Component;

/** 목록에서 본문을 풀지 않고, 읽을 때 복호화 도구만 붙인다. */
@Component
@RequiredArgsConstructor
class ConnectorActionLoadListener implements PostLoadEventListener {
    private final EntityManagerFactory entityManagerFactory;
    private final ConnectorActionBodies bodies;

    @PostConstruct
    void register() {
        entityManagerFactory.unwrap(SessionFactoryImplementor.class).getEventListenerRegistry()
            .appendListeners(EventType.POST_LOAD, this);
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (event.getEntity() instanceof ConnectorAction action) {
            action.attachOpener(bodies);
        }
    }
}
