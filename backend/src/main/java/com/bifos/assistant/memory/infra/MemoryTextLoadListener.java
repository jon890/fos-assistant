package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryTextOpener;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.stereotype.Component;

/**
 * 읽어 온 기억과 revision 에 복호화 도구를 붙인다(ADR-20261103 / memory-encryption).
 *
 * <p>여기서는 풀지 않는다. 제목과 본문은 처음 꺼낼 때 푼다. 엔티티에 리스너 주석을 달면 domain 이 infra 를 가리키게 되어 Hibernate 의
 * 사건 목록에 직접 더한다. 대화 메시지의 {@code ChatMessageLoadListener} 와 같은 방식이다.
 */
@Component
@RequiredArgsConstructor
class MemoryTextLoadListener implements PostLoadEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final MemoryTextOpener opener;


    @PostConstruct
    void register() {
        entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getEventListenerRegistry()
            .appendListeners(EventType.POST_LOAD, this);
    }


    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (event.getEntity() instanceof Memory memory) {
            memory.attachOpener(opener);
        } else if (event.getEntity() instanceof MemoryRevision revision) {
            revision.attachOpener(opener);
        }
    }
}
