package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.connection.application.ConnectorToolNameLookup;
import com.bifos.assistant.connector.connection.domain.HermesToolName;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorToolNameLookupTest {
    private final ConnectorCatalogCache catalog = mock(ConnectorCatalogCache.class);
    private final ConnectorToolNameLookup lookup = new ConnectorToolNameLookup(catalog);


    @Test
    @DisplayName("선언한 도구는 서비스 이름과 도구 제목을, 제목이 없는 도구는 서비스 이름만 받는다")
    void declaredToolGetsServiceAndTitle() {
        when(catalog.manifests()).thenReturn(List.of(gmail()));

        assertThat(lookup.namesOf(List.of("mcp__gmail__get_message", "mcp__gmail__untitled", "mcp__gmail__undeclared")))
            .containsEntry("mcp__gmail__get_message", new ConnectorToolName("Gmail", "메일 읽기"))
            .containsEntry("mcp__gmail__untitled", new ConnectorToolName("Gmail", null))
            .containsEntry("mcp__gmail__undeclared", new ConnectorToolName("Gmail", null));
    }


    @Test
    @DisplayName("서버 이름의 글자를 바꾸고 줄인 등록 이름도 계산한 값으로 찾는다")
    void findsRewrittenRegisteredName() {
        String longTool = "list_every_single_label_in_the_mailbox_with_details";
        ConnectorManifest manifest = manifest("naver-blog", "네이버 블로그", "naver-blog",
            new ConnectorTool(longTool, "READ", "none", "라벨 모두 보기", null));
        when(catalog.manifests()).thenReturn(List.of(manifest));
        String registered = HermesToolName.of("naver-blog", longTool);

        assertThat(lookup.nameOf(registered)).isEqualTo(new ConnectorToolName("네이버 블로그", "라벨 모두 보기"));
    }


    @Test
    @DisplayName("어느 플러그인의 것인지 모르는 이름과 플러그인 도구가 아닌 이름은 빠진다")
    void unknownNamesAreLeftOut() {
        when(catalog.manifests()).thenReturn(List.of(gmail()));

        assertThat(lookup.namesOf(List.of("mcp__other__read", "mcp__fos_assistant__memory_read"))).isEmpty();
        assertThat(lookup.nameOf("web_search")).isNull();
    }


    @Test
    @DisplayName("플러그인 도구가 없으면 카탈로그를 읽지 않는다")
    void doesNotReadCatalogWithoutPluginTools() {
        assertThat(lookup.namesOf(List.of("web_search", "terminal"))).isEmpty();
        verify(catalog, never()).manifests();
    }


    @Test
    @DisplayName("카탈로그를 읽지 못하면 이름을 붙이지 않고 실패하지 않는다")
    void catalogFailureGivesNoNames() {
        when(catalog.manifests()).thenThrow(new IllegalStateException("dashboard down"));

        assertThat(lookup.namesOf(List.of("mcp__gmail__get_message"))).isEmpty();
    }


    private static ConnectorManifest gmail() {
        return manifest("gmail", "Gmail", "gmail",
            new ConnectorTool("get_message", "READ", "none", "메일 읽기", null),
            new ConnectorTool("untitled", "READ", "none", " ", null));
    }


    private static ConnectorManifest manifest(String id, String title, String server, ConnectorTool... tools) {
        return new ConnectorManifest(id, title, "", List.of(), null, server, 2, List.of(tools));
    }
}
