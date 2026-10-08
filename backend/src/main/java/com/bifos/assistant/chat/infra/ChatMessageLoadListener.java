package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatMessage;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.stereotype.Component;

/**
 * 읽어 온 메시지에 복호화 도구를 붙인다(ADR-20261008 / data-encryption).
 *
 * <p>여기서는 풀지 않는다. 본문은 {@link ChatMessage#content()} 를 처음 부를 때 푼다. 읽는 도중에 다른 질의를 내지 않고, 본문을
 * 꺼내지 않는 목록은 복호화 비용을 내지 않는다. 엔티티에 리스너 주석을 달면 domain 이 infra 를 가리키게 되어 Hibernate 의
 * 사건 목록에 직접 더한다.
 */
@Component
@RequiredArgsConstructor
class ChatMessageLoadListener implements PostLoadEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final ChatMessageContents contents;

    @PostConstruct
    void register() {
        entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getEventListenerRegistry()
                .appendListeners(EventType.POST_LOAD, this);
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (event.getEntity() instanceof ChatMessage message) {
            message.attachOpener(contents);
        }
    }
}
