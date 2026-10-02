package com.bifos.assistant.chat.infra;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

public interface ArtifactSourceTransport {
    ArtifactSourceResponse get(
            InetAddress address,
            String originalHost,
            URI source,
            Duration connectTimeout,
            Duration readTimeout,
            ArtifactSourceCancellation cancellation)
            throws IOException;
}
