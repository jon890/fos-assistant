package com.bifos.assistant.hermes;

import java.util.List;

/** Hermes의 toolset 목록과 API server 설정을 읽고 쓴다. */
public interface HermesToolsetClient {

    record ToolsetCatalogEntry(String name, String label, String description) {}

    List<ToolsetCatalogEntry> readCatalog();

    List<String> readEnabled(String apiBaseUrl, String profileName);

    void writeApiServer(String profileName, List<String> toolsets);
}
