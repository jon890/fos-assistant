package com.bifos.assistant.browser.domain;

import java.net.URI;

/** 브라우저의 CDP 가 답하는지 본다. */
public interface CdpProbe {

    /** {@code GET /json/version} 이 200 으로 답하면 참이다. 닿지 못해도 거짓이다. */
    boolean ready(URI address);
}
