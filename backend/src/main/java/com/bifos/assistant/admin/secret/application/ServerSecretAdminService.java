package com.bifos.assistant.admin.secret.application;

import com.bifos.assistant.admin.secret.application.dto.ServerSecretOverview;
import com.bifos.assistant.browser.application.GatewaySecretService;
import com.bifos.assistant.connector.connection.application.ConnectorBrowserBindings;
import com.bifos.assistant.connector.connection.application.dto.BrowserBindingReinstall;
import com.bifos.assistant.crypto.application.ServerSecretService;
import com.bifos.assistant.notification.application.VapidKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 「서버 비밀값」 화면의 흐름을 잇는다(서버 비밀값 스펙 REQ1-REQ3, REQ18).
 *
 * <p>중계 서명 비밀을 바꾸는 일은 {@code browser} 가, 바인딩을 다시 설치하는 일은 위층인 {@code connector} 가 한다. 둘을 차례로 부르는
 * 일만 여기 있다. 이 메서드들에 {@code @Transactional} 을 붙이지 않는다. 값 쓰기를 커밋한 뒤에 대시보드를 부른다.
 */
@Service
@RequiredArgsConstructor
public class ServerSecretAdminService {

    private final ServerSecretService store;
    private final GatewaySecretService gateway;
    private final ConnectorBrowserBindings bindings;
    private final VapidKeyService vapid;


    public ServerSecretOverview overview() {
        return new ServerSecretOverview(store.storeAvailable(), gateway.status(), bindings.count(), vapid.status());
    }


    /**
     * 중계 서명 비밀을 새로 만든 뒤 브라우저 플러그인 바인딩을 모두 다시 설치한다. 값 쓰기가 실패하면 다시 설치하지 않는다. 다시 설치가
     * 플러그인 목록을 읽지 못해도 값은 이미 바뀌었으므로 결과로 알린다.
     */
    public BrowserBindingReinstall regenerateGateway(Long adminId) {
        gateway.regenerate(adminId);
        return bindings.reinstallAll();
    }


    public void deleteGateway(Long adminId) {
        gateway.delete(adminId);
    }


    /**
     * 웹 푸시 VAPID 키를 새로 만들고 모든 사용자의 등록 기기를 비운다(서버 비밀값 스펙 REQ6).
     *
     * @param subject 푸시 서비스가 연락할 곳. 비어 있으면 만드는 관리자의 이메일이다
     * @return 기기가 해제된 사용자 수
     */
    public int regenerateVapid(Long adminId, String adminEmail, String subject) {
        String contact = subject == null || subject.isBlank() ? "mailto:" + adminEmail : subject;
        return vapid.regenerate(adminId, contact);
    }


    /** @return 기기가 해제된 사용자 수 */
    public int deleteVapid(Long adminId) {
        return vapid.delete(adminId);
    }
}
