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


    /** 모델 이름과 글의 SHA-256 이다. 모델을 바꾸면 모든 지문이 달라진다. */
    public static byte[] fingerprint(String model, String text) {
        return Sha256.newDigest().digest((model + "\n" + text).getBytes(StandardCharsets.UTF_8));
    }
}
