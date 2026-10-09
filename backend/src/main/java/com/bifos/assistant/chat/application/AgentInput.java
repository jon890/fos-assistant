package com.bifos.assistant.chat.application;

import com.bifos.assistant.hermes.dto.HermesImage;
import java.util.List;

/**
 * 사진 안내를 붙인 실행 입력 글과, 그 뒤에 이미지로 함께 싣는 사진이다.
 *
 * @param text 사진 안내 단락과 사용자가 쓴 글
 * @param images 화면 순서대로 실은 사진. 싣지 않았으면 비어 있다
 */
public record AgentInput(String text, List<HermesImage> images) {

    public AgentInput {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
