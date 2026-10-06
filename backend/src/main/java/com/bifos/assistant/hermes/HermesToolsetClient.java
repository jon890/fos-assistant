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
     * @param sandboxOwner 셸과 파일 도구를 돌릴 격리 실행 공간의 주인 키. 목록에 셸 도구가 있을 때만 Hermes 가 쓴다
     * @throws com.bifos.assistant.shared.error.ApiException 실행 공간이 준비되지 않았으면 {@code
     *     AGENT_SANDBOX_UNAVAILABLE} 이다. 그때 목록은 바뀌지 않았다
     */
    void writeApiServer(String profileName, List<String> toolsets, String sandboxOwner);
}
