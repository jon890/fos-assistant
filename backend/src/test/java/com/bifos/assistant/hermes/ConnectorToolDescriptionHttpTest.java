package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ConnectorToolDescriptionHttpTest {
    @Test
    @DisplayName("실제 HTTP 카탈로그는 공통 경계마다 정책을 보존하고 설명과 요약만 정제한다")
    void preservesPoliciesAndNormalizesDescriptionsFromHttp() throws Exception {
        var mapper = JsonMapper.builder().build();
        var vectors = mapper.readTree(Files.readString(Path.of("../test/fixtures/connector-tool-descriptions.json")));
        for (var vector : vectors) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new HttpHermesConnectorClient(
                new HermesProperties("unused", "https://example.test", "token", "https://example.test", null, null, null, null),
                new SandboxAttachmentDirectory("unused"));
            ReflectionTestUtils.setField(client, "client", builder.build());
            String raw = "[{\"id\":\"demo-notes\",\"schema\":2,\"title\":\"검사\",\"description\":\"검사\","
                + "\"fields\":[],\"verify\":{\"tool\":\"read_note\"},\"mcp_server\":\"demo\","
                + "\"tools\":{\"read_note\":{\"risk\":\"READ\",\"approval\":\"none\",\"title\":\"읽기\",\"description\":"
                + asciiJson(mapper.writeValueAsString(vector.get("value"))) + ",\"summary\":"
                + asciiJson(mapper.writeValueAsString(vector.get("value"))) + "}}}]";
            server.expect(requestTo("https://example.test/api/connectors/catalog"))
                .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
            var tool = client.readCatalog().getFirst().tools().getFirst();
            assertThat(tool.risk()).isEqualTo("READ");
            assertThat(tool.approval()).isEqualTo("none");
            assertThat(tool.title()).isEqualTo("읽기");
            String expected = vector.get("expected").isNull() ? null : vector.get("expected").asString();
            assertThat(tool.description()).as(vector.get("name").asString()).isEqualTo(expected);
            assertThat(tool.summary()).as(vector.get("name").asString()).isEqualTo(expected);
            server.verify();
        }
    }


    @Test
    @DisplayName("옛 생성자는 상세 설명 없이 식별자와 상시 허락을 유지한다")
    void preservesLegacyConstructors() {
        var old = new ConnectorTool("write_note", "WRITE", "required", "메모", true, List.of("note_id"));
        var oldest = new ConnectorTool("write_note", "WRITE", "required", "메모", false);
        assertThat(old.description()).isNull();
        assertThat(old.summary()).isNull();
        assertThat(old.identifiers()).containsExactly("note_id");
        assertThat(old.grant()).isTrue();
        assertThat(oldest.description()).isNull();
        assertThat(oldest.identifiers()).isEmpty();
        assertThat(oldest.grant()).isFalse();
    }


    /** HTTP의 UTF-8 인코더가 잘못된 surrogate를 ?로 바꾸기 전에 JSON escape로 보존한다. */
    private static String asciiJson(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character > 127) {
                result.append(String.format("\\u%04x", (int)character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

}
