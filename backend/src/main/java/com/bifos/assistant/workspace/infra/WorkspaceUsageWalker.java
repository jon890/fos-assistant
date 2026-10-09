package com.bifos.assistant.workspace.infra;

import com.bifos.assistant.workspace.domain.WorkspaceMeasured;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 실행 공간 디렉터리 하나의 용량을 센다. 규칙은 {@code docs/code-architecture.md} 의 「관리자 용량」 이 갖는다.
 *
 * <p>{@link WorkspaceTree} 처럼 링크를 따라가지 않는다. 링크는 항목으로 세되 크기에 넣지 않고, 일반 파일만 크기에 더한다. 읽지 못한
 * 디렉터리는 건너뛰고 일부만 센 것으로 둔다. 결과에 파일 이름과 경로를 싣지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkspaceUsageWalker {

    /**
     * {@code dir} 아래를 센다. 항목이 {@code maxEntries} 를 넘으려 하거나 {@code clock} 의 시각이 {@code deadline} 을 지나면 거기서
     * 멈추고 {@code partial} 을 참으로 둔다.
     *
     * @param dir 부르는 쪽이 링크가 아닌 디렉터리인지 본 디렉터리. 그 자신은 항목으로 세지 않는다
     */
    public static WorkspaceMeasured measure(Path dir, long maxEntries, Instant deadline, Clock clock) {
        Counter counter = new Counter(dir, maxEntries, deadline, clock);
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, counter);
        } catch (IOException ex) {
            // 방문자는 예외를 던지지 않는다. 그래도 나오면 거기까지 센 값을 일부로 준다.
            counter.partial = true;
        }
        return new WorkspaceMeasured(counter.bytes, counter.entries, counter.partial);
    }

    /** 링크를 따라가지 않는 방문자다. 예외를 던지지 않고 실패를 {@code partial} 로 남긴다. */
    private static final class Counter extends SimpleFileVisitor<Path> {
        private final Path start;
        private final long maxEntries;
        private final Instant deadline;
        private final Clock clock;
        private long bytes;
        private long entries;
        private boolean partial;

        private Counter(Path start, long maxEntries, Instant deadline, Clock clock) {
            this.start = start;
            this.maxEntries = maxEntries;
            this.deadline = deadline;
            this.clock = clock;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            if (dir.equals(start)) {
                return FileVisitResult.CONTINUE;
            }
            return count(0);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            return count(attributes.isRegularFile() ? attributes.size() : 0);
        }

        /** 읽지 못한 항목이다. 디렉터리면 그 아래를 세지 못했으므로 일부만 센 것이다. */
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException ex) {
            partial = true;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException ex) {
            if (ex != null) {
                partial = true;
            }
            return FileVisitResult.CONTINUE;
        }

        private FileVisitResult count(long size) {
            if (entries >= maxEntries || clock.instant().isAfter(deadline)) {
                partial = true;
                return FileVisitResult.TERMINATE;
            }
            entries++;
            bytes += size;
            return FileVisitResult.CONTINUE;
        }
    }
}
