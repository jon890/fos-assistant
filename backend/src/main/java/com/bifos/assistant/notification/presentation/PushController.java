package com.bifos.assistant.notification.presentation;

import com.bifos.assistant.notification.application.PushDeviceService;
import com.bifos.assistant.notification.application.dto.PushConfig;
import com.bifos.assistant.notification.presentation.PushDtos.PushConfigView;
import com.bifos.assistant.notification.presentation.PushDtos.PushDeviceView;
import com.bifos.assistant.notification.presentation.PushDtos.RegisterPushDeviceRequest;
import com.bifos.assistant.notification.presentation.PushDtos.UnregisterCurrentRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사용자 자신의 웹 푸시 기기를 다루는 경로다(ADR-20261120 / web-push). 요청 본문이 사용자를 정하지 못한다.
 *
 * <p>계약은 {@code docs/features/attention/design.md} 의 「Control Plane API」 가 갖는다.
 */
@RestController
@RequestMapping("/api/v1/push")
@RequiredArgsConstructor
public class PushController {

    private final CurrentUserProvider currentUser;
    private final PushDeviceService devices;


    @GetMapping("/config")
    public PushConfigView config() {
        currentUser.require();
        PushConfig config = devices.config();
        return new PushConfigView(config.enabled(), config.publicKey());
    }


    @GetMapping("/devices")
    public List<PushDeviceView> list() {
        return devices.list(currentUser.require()).stream().map(PushDeviceView::from).toList();
    }


    @PutMapping("/devices")
    public PushDeviceView register(@RequestBody RegisterPushDeviceRequest request) {
        return PushDeviceView.from(devices.register(currentUser.require(), request.toRegistration()));
    }


    @DeleteMapping("/devices/current")
    public ResponseEntity<Void> unregisterCurrent(@RequestBody UnregisterCurrentRequest request) {
        devices.unregisterCurrent(currentUser.require(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
