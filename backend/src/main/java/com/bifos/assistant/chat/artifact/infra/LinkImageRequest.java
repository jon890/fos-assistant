package com.bifos.assistant.chat.artifact.infra;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 링크 사진 받기의 요청 하나다({@link LinkImageTransport}).
 *
 * @param source 요청할 주소
 * @param headers 더할 요청 머리. {@code Host}, {@code Connection} 은 전송이 정한다
 * @param connectTimeout 연결 제한 시간
 * @param readTimeout 읽기 제한 시간
 * @param maxHeaderBytes 응답 머리의 상한
 */
public record LinkImageRequest(URI source, Map<String, String> headers, Duration connectTimeout, Duration readTimeout, int maxHeaderBytes) {

    public LinkImageRequest {
        headers = Map.copyOf(headers);
    }
}
