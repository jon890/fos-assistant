package com.bifos.assistant.chat.artifact.infra;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 링크 사진 받기의 HTTPS 요청 하나를 보낸다. 고정한 IP 로 연결하고 SNI 와 {@code Host} 는 원래 이름을 쓴다.
 *
 * <p>스티커 받기의 {@link ArtifactSourceTransport} 와 달리 200 이 아닌 답도 상태와 머리를 그대로 돌려준다. 넘기기를 부르는 쪽이 따라간다.
 */
@FunctionalInterface
public interface LinkImageTransport {

    ArtifactSourceResponse get(InetAddress address, String host, Request request, ArtifactSourceCancellation cancellation)
        throws IOException;


    /**
     * @param source 요청할 주소
     * @param headers 더할 요청 머리. {@code Host}, {@code Connection} 은 전송이 정한다
     * @param connectTimeout 연결 제한 시간
     * @param readTimeout 읽기 제한 시간
     * @param maxHeaderBytes 응답 머리의 상한
     */
    record Request(URI source, Map<String, String> headers, Duration connectTimeout, Duration readTimeout, int maxHeaderBytes) {
        public Request {
            headers = Map.copyOf(headers);
        }
    }
}
