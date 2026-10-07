package com.bifos.assistant.testsupport;

import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 승인 줄의 사건을 받은 순간에 트랜잭션이 열려 있었는지를 함께 적는 대역이다.
 *
 * <p>기본은 꺼져 있어 아무것도 적지 않는다. 사건을 보는 검사가 {@link #start()} 로 켜고, {@link IntegrationTestIsolation} 이
 * 검사 뒤에 {@link #reset()} 으로 끈다.
 */
public class ConnectorChangeRecorder {
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile boolean recording;

    /** 적은 것을 비우고 이 뒤의 사건을 적는다. */
    public void start() {
        seen.clear();
        recording = true;
    }

    /** 적은 것을 비운다. 켜 둔 상태는 그대로다. */
    public void clear() {
        seen.clear();
    }

    /** 켠 뒤에 받은 사건이다. 받은 순서대로다. */
    public List<Seen> seen() {
        return seen;
    }

    /** 끄고 적은 것을 비운다. */
    public void reset() {
        recording = false;
        seen.clear();
    }

    @EventListener
    public void on(ConnectorActionChanged event) {
        if (recording) {
            seen.add(new Seen(event, TransactionSynchronizationManager.isActualTransactionActive()));
        }
    }

    /** 받은 사건과 그때 트랜잭션이 열려 있었는지다. */
    public record Seen(ConnectorActionChanged event, boolean insideTransaction) {}
}
