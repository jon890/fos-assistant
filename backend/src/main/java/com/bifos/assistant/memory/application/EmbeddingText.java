package com.bifos.assistant.memory.application;

import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 임베딩할 글과 그 지문을 만든다. 채우는 작업과 질문 때의 벡터 읽기가 같은 계산을 쓴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EmbeddingText {

    /** 제목과 본문을 줄바꿈 하나로 잇고 {@code maxChars} 에서 자른다. 본문이 null 이면 제목만 쓴다. */
    public static String of(String title, String content, int maxChars) {
        String text = content == null ? title : title + "\n" + content;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }


    /**
     * 모델 이름과 글의 SHA-256 이다. KEK 가 없는 설치의 지문이고, 이관이 옛 지문을 알아보는 데도 쓴다. 지문은 {@link MemoryTexts#embeddingFingerprint}
     * 가 낸다.
     */
    public static byte[] plainFingerprint(String model, String text) {
        return Sha256.newDigest().digest((model + "\n" + text).getBytes(StandardCharsets.UTF_8));
    }
}
