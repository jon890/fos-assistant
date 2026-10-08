package com.bifos.assistant.hermes;

import java.util.List;

/** Hermes의 toolset 목록과 API server 설정을 읽고 쓴다. */
public interface HermesToolsetClient {

    record ToolsetCatalogEntry(String name, String label, String description) {}

    List<ToolsetCatalogEntry> readCatalog();

    List<String> readEnabled(String apiBaseUrl, String profileName);

    /**
     * API 실행의 toolset 목록을 쓴다.
     *
     * <p>보내기 전에 주인의 첨부 디렉터리를 만든다. Hermes 는 그 디렉터리를 만들지 않는다(ADR-091).
     *
     * @param sandboxOwner 셸·파일·사진 도구를 돌릴 격리 실행 공간의 주인 키. 목록에 셸·파일·사진 도구가 있을 때만 Hermes 가 쓴다
     * @throws com.bifos.assistant.shared.error.ApiException 실행 공간이 준비되지 않았으면 {@code
     *     AGENT_SANDBOX_UNAVAILABLE} 이다. 첨부 디렉터리를 준비하지 못했을 때도 같다. 그때 목록은 바뀌지 않았다
     */
    void writeApiServer(String profileName, List<String> toolsets, String sandboxOwner);

    /**
     * {@link #writeApiServer} 와 같되, 실행 공간 정책에 없는 profile 의 셸 도구를 local 로 돌리지 않는다.
     *
     * <p>사람이 고르지 않은 기본 도구를 켤 때 쓴다. 격리되지 않은 셸이 다른 profile 의 {@code .env} 에 닿지 않게 한다(ADR-086).
     *
     * @throws com.bifos.assistant.shared.error.ApiException 정책이 없거나 그 profile 이 정책에 없으면 {@code
     *     AGENT_SANDBOX_UNAVAILABLE} 이다. 그때 목록은 바뀌지 않았다
     */
    void writeApiServerInSandbox(String profileName, List<String> toolsets, String sandboxOwner);
}
