package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.domain.HermesToolName;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import com.bifos.assistant.usage.execution.application.port.ConnectorToolNames;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 작업 과정에 보일 플러그인 도구의 이름을 카탈로그 캐시로 찾는다(docs/features/app-shell/design.md 「작업 과정의 이름」).
 *
 * <p>등록 이름은 글자를 바꾸고 줄여 원래 이름을 되찾을 수 없다. 그래서 manifest 마다 선언한 도구의 등록 이름을 계산해 같은 값을
 * 찾는다. 선언에 없는 등록 이름도 서버 이름까지의 앞부분이 그 manifest 의 것이면 서비스 이름만 붙인다. 바인딩을 보지 않으므로
 * 다른 사람이 공개한 에이전트와 오래된 답에도 같은 이름이 붙는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorToolNameLookup implements ConnectorToolNames {
    private static final String MCP_PREFIX = "mcp__";

    private final ConnectorCatalogCache catalog;


    @Override
    public Map<String, ConnectorToolName> namesOf(Collection<String> hermesTools) {
        List<String> asked = hermesTools.stream()
            .filter(name -> name != null && name.startsWith(MCP_PREFIX))
            .distinct()
            .toList();
        if (asked.isEmpty()) {
            return Map.of();
        }
        List<ConnectorManifest> manifests;
        try {
            manifests = catalog.manifests().stream()
                .filter(manifest -> manifest.mcpServer() != null)
                .toList();
        } catch (RuntimeException ex) {
            log.warn("connector catalog unreadable for tool names reason={}", ex.getClass().getSimpleName());
            return Map.of();
        }
        Map<String, ConnectorToolName> declared = new HashMap<>();
        for (ConnectorManifest manifest : manifests) {
            for (ConnectorTool tool : manifest.tools()) {
                declared.putIfAbsent(
                    HermesToolName.of(manifest.mcpServer(), tool.name()),
                    new ConnectorToolName(manifest.title(), blankToNull(tool.title())));
            }
        }
        Map<String, ConnectorToolName> names = new HashMap<>();
        for (String name : asked) {
            ConnectorToolName found = declared.get(name);
            if (found == null) {
                found = manifests.stream()
                    .filter(manifest -> name.startsWith(HermesToolName.of(manifest.mcpServer(), "")))
                    .findFirst()
                    .map(manifest -> new ConnectorToolName(manifest.title(), null))
                    .orElse(null);
            }
            if (found != null) {
                names.put(name, found);
            }
        }
        return names;
    }


    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
