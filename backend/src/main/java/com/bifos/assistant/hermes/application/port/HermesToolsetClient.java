package com.bifos.assistant.hermes.application.port;

import java.util.List;

/** Hermes의 toolset 목록과 API server 설정을 읽고 쓴다. */
public interface HermesToolsetClient {

    record ToolsetCatalogEntry(String name, String label, String description) {
    }


    List<ToolsetCatalogEntry> readCatalog();


    List<String> readEnabled(String apiBaseUrl, String profileName);


    /**
     * API 실행의 toolset 목록을 쓴다.
     *
     * <p>보내기 전에 주인의 첨부 디렉터리를 만든다. Hermes 는 그 디렉터리를 만들지 않는다(ADR-091).
     * 목록에 {@code image_gen} 이 있으면 같은 쓰기에서 그 profile 에 이미지 서비스 설정을 넣게 한다. 이미 있는 설정은
     * 덮어쓰지 않는다. 넣지 못하면 목록도 바뀌지 않고 예외가 난다(ADR-20261119 / image-gen-defaults).
     *
     * @param sandboxOwner 셸·파일·사진 도구를 돌릴 격리 실행 공간의 주인 키. 목록에 셸·파일·사진 도구가 있을 때만 Hermes 가 쓴다
     * @throws com.bifos.assistant.shared.error.ApiException 실행 공간이 준비되지 않았으면 {@code
     *     AGENT_SANDBOX_UNAVAILABLE} 이다. 첨부 디렉터리를 준비하지 못했을 때도 같다. 그때 목록은 바뀌지 않았다
     */
    void writeApiServer(String profileName, List<String> toolsets, String sandboxOwner);


    /**
     * {@link #writeApiServer} 와 같되, 본문에 {@code require_sandbox} 를 넣는다.
     *
     * <p>plugin 은 이제 이 값과 상관없이 정책 밖 profile 의 셸 저장을 거절한다(ADR-20261120 / no-local-shell). 옛 plugin 위에서도
     * local 로 켜지 않도록 기본 도구와 기동 때 다시 저장이 계속 이 쓰기를 쓴다. 정책 밖 거절이면
     * {@link HermesRequestRejected#profileUnlisted()} 가 참이다.
     *
     * @throws com.bifos.assistant.shared.error.ApiException 정책이 없거나 그 profile 이 정책에 없으면 {@code
     *     AGENT_SANDBOX_UNAVAILABLE} 이다. 그때 목록은 바뀌지 않았다
     */
    void writeApiServerInSandbox(String profileName, List<String> toolsets, String sandboxOwner);
}
