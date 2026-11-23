package com.bifos.assistant.chat.attachment.application.dto;

import com.bifos.assistant.runtime.application.dto.RunImage;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 이번 메시지의 사진과 함께 넣을 이미지를 한 예산으로 고른 결과다.
 *
 * @param photos 이번 메시지의 사진. 보낸 순서다
 * @param extraDataUrls 함께 넣을 이미지의 {@code data:} 주소. 받은 순서이고 넣지 못한 자리는 null 이다
 */
public record EmbeddedImages(List<AgentPhoto> photos, List<String> extraDataUrls) {

    /** 넣은 함께 넣을 이미지만 이름표를 붙여 낸다. 이름표는 {@code labels} 의 같은 자리다. */
    public List<RunImage> extraImages(List<String> labels) {
        return IntStream.range(0, extraDataUrls.size())
            .filter(i -> extraDataUrls.get(i) != null)
            .mapToObj(i -> new RunImage(labels.get(i), extraDataUrls.get(i)))
            .toList();
    }
}
