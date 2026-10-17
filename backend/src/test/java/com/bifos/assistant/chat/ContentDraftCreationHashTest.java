package com.bifos.assistant.chat;

import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.contentdraft.application.ContentDraftInputReader;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftCreationContext;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContentDraftCreationHashTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContentDraftInputReader reader = new ContentDraftInputReader();


    @Test
    @DisplayName("독립 Python compact UTF8 배열 golden vector와 네 식별 키 및 본문 hash가 같다")
    void matchesIndependentUtf8GoldenVectors() {
        var scope = new ContentDraftCreationContext(7L, 8L, "profile", 99L, 99L, "root", "root", "call_1");
        var request = UUID.fromString("12345678-1234-5678-9012-123456789abc");
        var expected = List.of(
            "54f5d8913e64787ce22b935dd4b961894bc177d0bd2f8ef0dfea25a8b46f129b",
            "031ed9cf87cdce97d9557d689de57238e3036ae19ca322002392f24be0f79a21",
            "8cc5334330a1f67bcde459b0650d3bdb7acdd60a623dc0432af0e8203e331076",
            "b038dd15b2bef51454c395ebdfbf34462a0af14b5c7ebf6a5eb42e27e21df698");
        var actual = List.of(scope.creationKey(request), scope.callKey(), scope.creationIdentityKey(request), scope.callIdentityKey());
        for (int index = 0; index < expected.size(); index++) {
            assertThat(HexFormat.of().formatHex(actual.get(index))).isEqualTo(expected.get(index));
        }
        var input = reader.read(json.readTree("""
            {"title":"원고😀","category":"분류","tags":["태그"],"blocks":[
                {"type":"paragraph","text":"본문"},
                {"type":"image","assetId":"12","observationRef":{"id":"13","revision":2}},
                {"type":"sticker","code":"smile"},{"type":"map","name":"장소","address":"주소"}]}
            """));
        assertThat(HexFormat.of().formatHex(ContentDraftCreationContext.requestHash(input)))
            .isEqualTo("d95699aeef1aa82b4184f2c39890ad69c44e0b51009e1007794bfda16166d7eb");
    }


    @Test
    @DisplayName("객체 순서와 escape는 같고 Unicode 조합 배열 순서와 caption 생략은 다르다")
    void preservesSemanticInputWithoutNormalizingContent() {
        var original = json.readTree("""
            {"title":"é","category":"분류","tags":["하나","둘"],"blocks":[{"type":"image","assetId":"12"}]}
            """);
        var reordered = json.readTree("""
            {"blocks":[{"assetId":"12","type":"image"}],"tags":["하나","둘"],"category":"분류","title":"é"}
            """);
        var hash = ContentDraftCreationContext.requestHash(reader.read(original));
        assertThat(ContentDraftCreationContext.requestHash(reader.read(reordered))).isEqualTo(hash);
        var decomposed = original.deepCopy();
        ((ObjectNode)decomposed).put("title", "e\u0301");
        assertThat(ContentDraftCreationContext.requestHash(reader.read(decomposed))).isNotEqualTo(hash);
        var emptyCaption = original.deepCopy();
        ((ObjectNode)emptyCaption.path("blocks").get(0)).put("caption", "");
        assertThat(ContentDraftCreationContext.requestHash(reader.read(emptyCaption))).isNotEqualTo(hash);
        var tagOrder = original.deepCopy();
        ((ObjectNode)tagOrder).set("tags", json.createArrayNode().add("둘").add("하나"));
        assertThat(ContentDraftCreationContext.requestHash(reader.read(tagOrder))).isNotEqualTo(hash);
    }
}
