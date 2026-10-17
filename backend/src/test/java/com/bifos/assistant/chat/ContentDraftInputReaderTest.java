package com.bifos.assistant.chat;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.contentdraft.application.ContentDraftInputReader;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContentDraftInputReaderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContentDraftInputReader reader = new ContentDraftInputReader();


    @Test
    @DisplayName("canonical 블록과 코드포인트 경계를 그대로 수용한다")
    void acceptsAllCanonicalBlocksAndCodePointBoundary() {
        var node = json.readTree("""
            {"title":"제목","category":"분류","tags":["태그"],"blocks":[
              {"type":"paragraph","text":"문단"},
              {"type":"image","assetId":"7","caption":"","observationRef":{"id":"9","revision":1}},
              {"type":"sticker","code":"hello_1"},
              {"type":"map","name":"이름","address":"주소|상세"}]}
            """);
        ((ObjectNode)node).put("title", "😀".repeat(100));
        assertThat(reader.read(node).title()).isEqualTo("😀".repeat(100));
        ((ObjectNode)node).put("title", "😀".repeat(101));
        assertThatThrownBy(() -> reader.read(node)).isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("정규 ID와 정수 revision만 받고 문자열과 실수 변환을 거절한다")
    void rejectsNonCanonicalIdsAndRevisionCoercion() {
        for (String id : List.of("0", "007", "-1", "1.0", "9223372036854775808")) {
            assertThatThrownBy(() -> ContentDraftInputReader.id(json.valueToTree(id))).isInstanceOf(ApiException.class);
        }
        for (String revision : List.of("0", "1.0", "\"1\"", "9007199254740992", "null")) {
            assertThatThrownBy(() -> ContentDraftInputReader.revision(json.readTree(revision))).isInstanceOf(ApiException.class);
        }
        assertThat(ContentDraftInputReader.id(json.valueToTree("9223372036854775807"))).isEqualTo(Long.MAX_VALUE);
        assertThat(ContentDraftInputReader.uuid(UUID.randomUUID().toString())).isNotNull();
        assertThatThrownBy(() -> ContentDraftInputReader.uuid("1-1-1-1-1")).isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("unknown null 중복 태그 예약 줄과 UTF-8 한도 위반을 거절한다")
    void rejectsUnknownNullDuplicateTagsReservedLinesAndOversizedUtf8() {
        for (String draft : List.of(
            "{\"title\":null,\"category\":\"분류\",\"tags\":[],\"blocks\":[]}",
            "{\"title\":\"제목\",\"category\":\"분류\",\"tags\":[\"A\",\" a \"],\"blocks\":[]}",
            "{\"title\":\"제목\",\"category\":\"분류\",\"tags\":[],\"blocks\":[],\"extra\":1}")) {
            assertThatThrownBy(() -> reader.read(json.readTree(draft))).isInstanceOf(ApiException.class);
        }
        for (String line : List.of("[사진 1: a.jpg]", "[스티커: hello]", "[지도: 이름 | 주소]", "[기존 구성요소 1]", "a\nb", "😀".repeat(20_000))) {
            var node = json.readTree("{\"title\":\"제목\",\"category\":\"분류\",\"tags\":[],\"blocks\":[]}");
            ((ArrayNode)node.get("blocks")).add(json.createObjectNode().put("type", "paragraph").put("text", line));
            assertThatThrownBy(() -> reader.read(node)).isInstanceOf(ApiException.class);
        }
    }


    @Test
    @DisplayName("같은 assetId를 두 이미지 블록에 두면 고정 코드와 문구로 거절하고 서로 다른 사진은 받는다")
    void rejectsDuplicatePhotoAssetsWithStableCode() {
        var duplicated = reader.read(json.readTree("""
            {"title":"제목","category":"분류","tags":[],"blocks":[
              {"type":"image","assetId":"7"},{"type":"paragraph","text":"사이"},
              {"type":"image","assetId":"7","caption":"다른 설명"}]}
            """));
        assertThatThrownBy(() -> ContentDraftInputReader.requireUniquePhotos(duplicated))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_DUPLICATE_PHOTO);
                assertThat(ex.getMessage()).isEqualTo(ContentDraftInputReader.DUPLICATE_PHOTO_MESSAGE)
                    .contains("한 번만").contains("지우고");
            });
        var distinct = reader.read(json.readTree("""
            {"title":"제목","category":"분류","tags":[],"blocks":[
              {"type":"image","assetId":"7"},{"type":"image","assetId":"8"},{"type":"sticker","code":"a"},{"type":"sticker","code":"a"}]}
            """));
        ContentDraftInputReader.requireUniquePhotos(distinct);
    }
}
