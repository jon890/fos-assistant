package com.bifos.assistant.chat.infra;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface ArtifactAtomicMover {
    void move(Path source, Path target) throws IOException;
}
