package com.bifos.assistant.chat.artifact.infra;

import java.io.IOException;
import java.net.InetAddress;

/**
 * 링크 사진 받기의 HTTPS 요청 하나를 보낸다. 고정한 IP 로 연결하고 SNI 와 {@code Host} 는 원래 이름을 쓴다.
 *
 * <p>스티커 받기의 {@link ArtifactSourceTransport} 와 달리 200 이 아닌 답도 상태와 머리를 그대로 돌려준다. 넘기기를 부르는 쪽이 따라간다.
 */
@FunctionalInterface
public interface LinkImageTransport {

    ArtifactSourceResponse get(InetAddress address, String host, LinkImageRequest request, ArtifactSourceCancellation cancellation)
        throws IOException;
}
