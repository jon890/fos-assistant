package com.bifos.assistant.chat.infra;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public record ArtifactSourceResponse(int status, Map<String, String> headers, InputStream body)
        implements AutoCloseable {
    @Override
    public void close() throws IOException {
        body.close();
    }
}
