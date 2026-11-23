package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.port.ApprovalResumes;
import com.bifos.assistant.mcp.application.port.FinancialLimitRequests;
import com.bifos.assistant.mcp.application.port.ConnectorAttachRequests;
import com.bifos.assistant.mcp.application.port.ConnectorListing;
import com.bifos.assistant.mcp.application.port.MemoryForgetRequests;
import com.bifos.assistant.mcp.application.port.TaskRequests;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.orchestration.config.DelegationProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpToolCatalogTest {
    @Test
    @DisplayName("정의 순서를 유지하고 호출할 때마다 먼저 살펴보기의 현재 대기 상한을 읽는다")
    @SuppressWarnings("unchecked")
    void keepsDefinitionOrderAndReadsCurrentWaitMaxAtEveryCall() {
        LiveProperties<DelegationProperties> properties = mock(LiveProperties.class);
        var remember = mock(McpMemoryRemember.class);
        var observations = mock(McpMediaObservationTools.class);
        var drafts = mock(McpContentDraftTools.class);
        when(drafts.writeDefinition()).thenReturn(Map.of("name", McpContentDraftTools.WRITE));
        when(drafts.readDefinition()).thenReturn(Map.of("name", McpContentDraftTools.READ));
        when(drafts.imageDefinition()).thenReturn(Map.of("name", McpContentDraftTools.IMAGE));
        var snapshots = mock(McpContentDraftSnapshots.class);
        when(snapshots.previewDefinition()).thenReturn(Map.of("name", McpContentDraftSnapshots.PREVIEW));
        when(snapshots.saveDefinition()).thenReturn(Map.of("name", McpContentDraftSnapshots.SAVE));
        Map<String, Object> list = Map.of("name", McpMediaObservationTools.LIST);
        Map<String, Object> record = Map.of("name", McpMediaObservationTools.RECORD);
        Map<String, Object> memory = Map.of("name", "memory_remember");
        when(observations.listDefinition()).thenReturn(list);
        when(observations.recordDefinition()).thenReturn(record);
        when(remember.definition()).thenReturn(memory);
        when(properties.current()).thenReturn(settings(20), settings(40));
        var catalog = new McpToolCatalog(properties, remember, observations, drafts, snapshots,
            new McpFinancialLimitTools(mock(FinancialLimitRequests.class)),
            new McpApprovalResumeTool(mock(ApprovalResumes.class)),
            new McpFileSpaceTools(null, null, null),
            new McpConnectorListTool(mock(ConnectorListing.class)),
            new McpTaskTools(mock(TaskRequests.class)),
            new McpFollowUpTools(null, null, null, null, null, null, null),
            new McpMemoryForgetTool(mock(MemoryForgetRequests.class)),
            new McpConnectorAttachTool(mock(ConnectorAttachRequests.class)));

        var first = catalog.tools();
        assertThat(first).extracting(tool -> tool.get("name"))
            .doesNotHaveDuplicates()
            .containsExactly("memory_read", "artifact_write", "agent_list", "agent_delegate", "agent_status",
                "agent_stop", "follow_up_propose", McpMediaObservationTools.LIST, McpMediaObservationTools.RECORD,
                McpContentDraftTools.WRITE, McpContentDraftTools.READ, McpContentDraftTools.IMAGE, McpContentDraftSnapshots.PREVIEW, McpContentDraftSnapshots.SAVE,
                McpFinancialLimitTools.READ, McpFinancialLimitTools.CHANGE, McpApprovalResumeTool.NAME, McpFileSpaceTools.PUT, McpConnectorListTool.NAME, "memory_remember",
                "memory_search", McpTaskTools.LIST, McpTaskTools.CREATE, McpTaskTools.UPDATE, McpTaskTools.SET_STATE,
                McpTaskTools.DELETE, McpFollowUpTools.LIST, McpFollowUpTools.DONE, McpMemoryForgetTool.NAME, McpConnectorAttachTool.NAME);
        assertThat(first).contains(list, record, memory);
        assertThat(description(first, "agent_delegate"))
            .contains("승인 전에 확인할 수 없는 값은 준비를 시작하는 조건으로 붙이지 않는다",
                "최종 가격 판단과 결제는 사용자가 한다");
        assertThat(description(first, "agent_status"))
            .contains("결과는 끝나면 자동으로 전달되므로 기다리려고 반복해서 부르지 않는다. 사용자가 진행 상황을 물을 때 한 번 부른다.")
            .endsWith("먼저 살펴보기에서는 wait_seconds 로 끝나기를 기다린다(최대 20초).");
        assertThat(description(catalog.tools(), "agent_status")).endsWith("먼저 살펴보기에서는 wait_seconds 로 끝나기를 기다린다(최대 40초).");
    }


    @Test
    @DisplayName("맡김 대기 상한이 0 이면 기다리지 않는다는 설명이고, 아니면 그 초까지 기다려 결과를 함께 준다는 설명이다")
    @SuppressWarnings("unchecked")
    void describesDelegateWaitFromCurrentSetting() {
        LiveProperties<DelegationProperties> properties = mock(LiveProperties.class);
        when(properties.current()).thenReturn(settings(20), settings(20, Duration.ofMinutes(2)));
        var catalog = catalog(properties);

        assertThat(description(catalog.tools(), "agent_delegate")).contains("끝날 때까지 기다리지 않는다.");
        assertThat(description(catalog.tools(), "agent_delegate"))
            .contains("최대 120초까지 결과를 기다려 output 으로 함께 돌려주므로")
            .contains("status 가 RUNNING 이면 note 를 따른다.");
    }


    @Test
    @DisplayName("모든 도구는 모델에게 서명 인자를 노출하지 않고 실행 맥락을 허용한다")
    @SuppressWarnings("unchecked")
    void allowsRuntimeContextForEveryTool() {
        LiveProperties<DelegationProperties> properties = mock(LiveProperties.class);
        when(properties.current()).thenReturn(settings(20));
        var catalog = catalog(properties);

        assertThat(catalog.tools()).allSatisfy(tool -> {
            assertThat(tool.get("inputSchema")).isInstanceOf(Map.class);
            var schema = (Map<String, Object>)tool.get("inputSchema");
            assertThat(schema).as("%s의 루트 스키마", tool.get("name"))
                .doesNotContainKey("additionalProperties");
            assertThat((Map<String, Object>)schema.get("properties")).doesNotContainKey("_fos_ctx");
            assertThat((List<String>)schema.getOrDefault("required", List.of())).doesNotContain("_fos_ctx");
        });
    }


    private static McpToolCatalog catalog(LiveProperties<DelegationProperties> properties) {
        return new McpToolCatalog(properties,
            new McpMemoryRemember(null, null, null, null),
            new McpMediaObservationTools(null, null, null, null, null),
            new McpContentDraftTools(null, null, null, null, null),
            new McpContentDraftSnapshots(null, null, null, null),
            new McpFinancialLimitTools(mock(FinancialLimitRequests.class)),
            new McpApprovalResumeTool(mock(ApprovalResumes.class)),
            new McpFileSpaceTools(null, null, null),
            new McpConnectorListTool(mock(ConnectorListing.class)),
            new McpTaskTools(mock(TaskRequests.class)),
            new McpFollowUpTools(null, null, null, null, null, null, null),
            new McpMemoryForgetTool(mock(MemoryForgetRequests.class)),
            new McpConnectorAttachTool(mock(ConnectorAttachRequests.class)));
    }


    private static String description(List<Map<String, Object>> definitions) {
        return description(definitions, "agent_status");
    }


    private static String description(List<Map<String, Object>> definitions, String name) {
        return definitions.stream().filter(tool -> name.equals(tool.get("name")))
            .map(tool -> (String)tool.get("description")).findFirst().orElseThrow();
    }


    private static DelegationProperties settings(int waitSeconds) {
        return settings(waitSeconds, Duration.ZERO);
    }


    private static DelegationProperties settings(int waitSeconds, Duration delegateWait) {
        return new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 1000, Duration.ofSeconds(waitSeconds), delegateWait);
    }
}
