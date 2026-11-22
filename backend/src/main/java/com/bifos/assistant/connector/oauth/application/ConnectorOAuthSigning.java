package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthSigningRewrite;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthSigningStatus;
import com.bifos.assistant.crypto.domain.ServerSecretRecord;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자가 단추 연결의 서명 비밀을 새로 만들고, 모든 단추 연결의 OAuth 파일에 새 통로 주소를 다시 쓴다(설계 「서버 비밀값 줄」).
 *
 * <p>새로 만들면 옛 비밀로 서명한 통로 표식과 진행 중이던 연결 흐름의 상태 값은 바로 무효다. 통로 주소는 실행기 밖 파일에 있어 바인딩을 다시
 * 설치하지 않는다. 떠 있는 플러그인 프로세스는 다음 프로세스부터 새 주소를 받는다.
 *
 * <p>이 클래스의 메서드에 {@code @Transactional} 을 붙이지 않는다. 비밀 쓰기가 반영된 뒤에 연결마다 따로 잠근다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorOAuthSigning {
    private final ServerSecrets secrets;
    private final ConnectorOAuthSigner signer;
    private final ConnectorConnectionRepository connections;
    private final AppUserRepository users;
    private final HermesConnectorOAuthClient oauth;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. */
    private final TransactionTemplate transactions;


    public ConnectorOAuthSigningStatus status() {
        ServerSecretRecord row = secrets.find(ConnectorOAuthSigner.SECRET_NAME).orElse(null);
        int count = (int)connections.findAll().stream().filter(ConnectorOAuthSigning::usesPassage).count();
        return new ConnectorOAuthSigningStatus(
            secrets.available(), row != null && row.set(), row == null ? null : row.updatedBy(),
            row == null ? null : row.updatedAt(), signer.available(), count);
    }


    /**
     * 비밀을 새로 만들고 해제되지 않은 단추 연결마다 새 통로 주소를 쓴다. 한 연결의 실패는 세고 다음으로 간다. 비밀은 이미 바뀌었기 때문이다.
     *
     * <p>대상은 상태로 먼저 고르지 않고 모든 연결 번호에서 주인 행을 잠근 뒤 다시 읽어 정한다. 처음 연결하는 흐름의 행은 commit 전까지 해제
     * 상태라, 옛 비밀로 주소를 만든 흐름이 목록을 읽은 뒤에 커밋되면 빠진다. 돌아오기는 같은 주인 행 잠금 안에서 주소를 만들어 commit 하므로
     * 여기서 잠그면 그 커밋을 기다린 뒤의 연결을 본다.
     */
    public ConnectorOAuthSigningRewrite regenerate(Long adminId) {
        signer.regenerate(adminId);
        // 주인 번호는 트랜잭션 밖에서 읽는다. 안에서 읽으면 잠근 뒤의 다시 읽기가 잠그기 전에 읽은 엔티티를 돌려준다.
        List<ConnectorConnection> candidates = connections.findAll();
        int total = 0;
        int failed = 0;
        for (ConnectorConnection candidate : candidates) {
            Boolean rewritten;
            try {
                rewritten = transactions.execute(status -> rewrite(candidate.id(), candidate.userId()));
            } catch (RuntimeException ex) {
                // 예외 메시지에는 통로 주소가 섞일 수 있어 종류만 남긴다.
                log.warn("connector OAuth token URL rewrite failed connection={} exception={}",
                    candidate.id(), ex.getClass().getSimpleName());
                rewritten = Boolean.FALSE;
            }
            if (rewritten != null) {
                total++;
                failed += rewritten ? 0 : 1;
            }
        }
        return new ConnectorOAuthSigningRewrite(total, failed);
    }


    /** @return 다시 썼으면 참, 쓰지 못했으면 거짓, 대상이 아니면 null */
    private Boolean rewrite(Long connectionId, Long userId) {
        if (users.findByIdForUpdate(userId).isEmpty()) {
            return null;
        }
        ConnectorConnection connection = connections.findById(connectionId).filter(ConnectorOAuthSigning::usesPassage).orElse(null);
        if (connection == null) {
            return null;
        }
        Outcome outcome = oauth.replaceTokenUrl(connection.vault(), signer.currentUrl(connectionId).orElseThrow());
        if (outcome != Outcome.OK) {
            log.warn("connector OAuth token URL rewrite did not succeed connection={} outcome={}", connectionId, outcome);
        }
        return outcome == Outcome.OK;
    }


    private static boolean usesPassage(ConnectorConnection connection) {
        return connection.status() != ConnectionStatus.DISCONNECTED && connection.fields().auth().usesOAuth();
    }
}
