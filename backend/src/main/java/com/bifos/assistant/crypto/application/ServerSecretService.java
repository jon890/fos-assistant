package com.bifos.assistant.crypto.application;

import com.bifos.assistant.crypto.domain.ServerSecrets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 화면이 서버 비밀값 저장소의 상태를 읽는 자리다(ADR-20261120 / server-secret).
 *
 * <p>원문을 내는 메서드를 두지 않는다. 값마다의 상태는 그 값의 주인 기능이 낸다.
 */
@Service
@RequiredArgsConstructor
public class ServerSecretService {

    private final ServerSecrets secrets;


    /** KEK 가 있어 저장할 수 있는가. 거짓이면 화면이 저장 단추를 끈다 */
    public boolean storeAvailable() {
        return secrets.available();
    }
}
