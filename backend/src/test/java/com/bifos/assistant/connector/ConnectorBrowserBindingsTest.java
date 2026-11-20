package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.connection.application.ConnectorBrowserBindings;
import com.bifos.assistant.connector.connection.application.ConnectorInstallAttempts;
import com.bifos.assistant.connector.connection.application.dto.BrowserBindingReinstall;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 비밀을 바꾼 뒤 대상 바인딩을 읽지 못해도 예외 대신 결과로 알리는지 본다(대체 리뷰 P4). */
class ConnectorBrowserBindingsTest {

    private static final ConnectorManifest BROWSER = new ConnectorManifest(
        "demo-browser", "브라우저 메모", "", List.of(), "ping", "browser", 1, List.of(), List.of(),
        ConnectorAppearance.NONE, false, true, "https://login.example.test/sign-in");


    @Test
    @DisplayName("대상 바인딩 목록을 읽지 못하면 다시 설치는 읽지 못했다는 결과를 내고 수는 비어 있다")
    void reportsUnreadableBindings() {
        HermesConnectorClient connector = mock(HermesConnectorClient.class);
        when(connector.readCatalog()).thenReturn(List.of(BROWSER));
        ConnectorBindingRepository bindings = mock(ConnectorBindingRepository.class);
        when(bindings.findIdsByConnectorIdIn(anyCollection())).thenThrow(new IllegalStateException("db down"));
        ConnectorInstallAttempts attempts = mock(ConnectorInstallAttempts.class);
        when(attempts.start(any())).thenAnswer(invocation -> ((Supplier<?>)invocation.getArgument(0)).get());
        ConnectorBrowserBindings service = new ConnectorBrowserBindings(bindings, connector, attempts, Clock.systemUTC());

        assertThat(service.reinstallAll()).isEqualTo(new BrowserBindingReinstall(0, 0, 0, true));
        assertThat(service.count()).isEmpty();
    }
}
