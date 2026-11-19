package com.bifos.assistant.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.port.SandboxFiles;
import com.bifos.assistant.mcp.application.port.SandboxFiles.SandboxFile;
import com.bifos.assistant.mcp.application.port.SandboxFiles.Status;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.workspace.config.WorkspaceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 넣기 도구가 실행 공간을 읽는 규칙(ADR-20261119 / file-space-put)을 본다. 링크를 따라가지 않고 일반 파일만 읽는다. */
class WorkspaceSandboxFilesTest {

    @TempDir
    Path root;


    @Test
    @DisplayName("주인 디렉터리 안의 일반 파일을 읽고 폴더와 링크와 없는 파일은 없는 것으로 본다")
    void readsOnlyRegularFilesInsideOwnerDirectory() throws IOException {
        Path owner = Files.createDirectories(root.resolve("u7/reports"));
        Files.writeString(owner.resolve("q3.csv"), "a,b\n1,2\n");
        Path outside = Files.writeString(root.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(owner.resolve("link.txt"), outside);
        SandboxFiles files = files(root.toString());

        SandboxFile found = files.read("u7", "reports/q3.csv", 1024);

        assertThat(found.status()).isEqualTo(Status.FOUND);
        assertThat(new String(found.bytes(), StandardCharsets.UTF_8)).isEqualTo("a,b\n1,2\n");
        assertThat(files.read("u7", "reports", 1024).status()).isEqualTo(Status.MISSING);
        assertThat(files.read("u7", "reports/link.txt", 1024).status()).isEqualTo(Status.MISSING);
        assertThat(files.read("u7", "reports/none.csv", 1024).status()).isEqualTo(Status.MISSING);
        assertThat(files.read("u7", "../secret.txt", 1024).status()).isEqualTo(Status.MISSING);
    }


    @Test
    @DisplayName("상한을 넘는 파일은 읽지 않는다")
    void refusesFilesOverTheLimit() throws IOException {
        Path owner = Files.createDirectories(root.resolve("a3"));
        Files.write(owner.resolve("big.csv"), new byte[11]);

        assertThat(files(root.toString()).read("a3", "big.csv", 10).status()).isEqualTo(Status.TOO_LARGE);
        assertThat(files(root.toString()).read("a3", "big.csv", 11).status()).isEqualTo(Status.FOUND);
    }


    @Test
    @DisplayName("주인 디렉터리가 없거나 링크이거나 주인 키가 틀리거나 루트가 없으면 없는 것으로 본다")
    void missingOwnerDirectoryIsMissing() throws IOException {
        Path real = Files.createDirectories(root.resolve("u1"));
        Files.writeString(real.resolve("a.csv"), "x");
        Files.createSymbolicLink(root.resolve("u2"), real);

        assertThat(files(root.toString()).read("u9", "a.csv", 10).status()).isEqualTo(Status.MISSING);
        assertThat(files(root.toString()).read("u2", "a.csv", 10).status()).isEqualTo(Status.MISSING);
        assertThat(files(root.toString()).read("../u1", "a.csv", 10).status()).isEqualTo(Status.MISSING);
        assertThat(files("").read("u1", "a.csv", 10).status()).isEqualTo(Status.MISSING);
    }


    private static SandboxFiles files(String rootValue) {
        return new WorkspaceSandboxFiles(
            LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(rootValue, "")));
    }
}
