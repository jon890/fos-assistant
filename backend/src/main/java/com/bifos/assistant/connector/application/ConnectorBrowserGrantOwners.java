package com.bifos.assistant.connector.application;

import com.bifos.assistant.browser.application.BrowserGrantOwners;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 브라우저 중계의 바인딩 표식이 가리키는 바인딩을 읽어 그 연결 주인을 준다. 요청마다 표에서 다시 읽는다. */
@Component
@RequiredArgsConstructor
public class ConnectorBrowserGrantOwners implements BrowserGrantOwners {

    private final ConnectorBindingRepository bindings;

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> bindingOwner(long bindingId) {
        return bindings.findById(bindingId).map(binding -> binding.connection().userId());
    }
}
