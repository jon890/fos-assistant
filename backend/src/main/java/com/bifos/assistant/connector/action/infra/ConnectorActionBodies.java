package com.bifos.assistant.connector.action.infra;

import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionBodyOpener;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 기존 사용자 데이터 key로 승인 줄의 본문을 저장하고 푼다. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConnectorActionBodies implements ConnectorActionBodyOpener {
    private final TextCipher cipher;


    public boolean enabled() {
        return cipher.enabled();
    }


    public void sealArgs(ConnectorAction action, String plain) {
        var sealed = seal(action, "args_json", plain);
        action.sealArgs(sealed.content(), sealed.keyId());
        action.attachOpener(this);
    }


    public void sealResult(ConnectorAction action) {
        if (action.storedResultText() == null || action.resultKeyId() != null) {
            return;
        }
        var sealed = seal(action, "result_text", action.storedResultText());
        action.sealResult(sealed.content(), sealed.keyId());
        action.attachOpener(this);
    }


    /** 상태를 바꾸기 전에 봉인을 끝내 매핑된 칸에 결과 평문이 들어가지 않게 한다. */
    public void succeed(ConnectorAction action, String plain, Instant now) {
        var sealed = seal(action, "result_text", plain);
        action.succeed(sealed.content(), sealed.keyId(), now);
        action.attachOpener(this);
    }


    public void fail(ConnectorAction action, String errorCode, String plain, Instant now) {
        var sealed = plain == null ? new SealedText(null, null) : seal(action, "result_text", plain);
        action.fail(errorCode, sealed.content(), sealed.keyId(), now);
        action.attachOpener(this);
    }


    /** 결과를 모르는 줄에 커넥터가 선언한 코드와 재시도 식별자를 봉인해 남긴다. 결과 본문은 아니다. */
    public void unknown(ConnectorAction action, String plain, Instant now) {
        var sealed = seal(action, "result_text", plain);
        action.unknown(sealed.content(), sealed.keyId(), now);
        action.attachOpener(this);
    }


    private SealedText seal(ConnectorAction action, String column, String plain) {
        if (action.id() == null || plain == null) {
            throw new IllegalStateException("connector action body requires an id and content");
        }
        if (!cipher.enabled() && action.risk() != ToolRisk.FINANCIAL) {
            return new SealedText(plain, null);
        }
        var sealed = cipher.seal(action.userId(), aad(action, column), plain)
            .orElseThrow(() -> new IllegalStateException("connector action body encryption unavailable"));
        if (sealed.keyId() == null || sealed.content() == null) {
            throw new IllegalStateException("connector action body encryption unavailable");
        }
        return sealed;
    }


    @Override
    public String open(ConnectorAction action, String column, Long keyId, String stored) {
        try {
            return cipher.open(keyId, action.userId(), aad(action, column), stored).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("승인 본문 복호화 실패 actionId={} column={} kind={}", action.id(), column, ex.getClass().getSimpleName());
            return null;
        }
    }


    private static String aad(ConnectorAction action, String column) {
        return "connector_action:" + action.id() + ":column:" + column + ":user:" + action.userId();
    }
}
