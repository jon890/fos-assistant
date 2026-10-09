package com.bifos.assistant.skill;

import static com.bifos.assistant.skill.application.SkillPackageZip.MAX_ENTRIES;
import static com.bifos.assistant.skill.application.SkillPackageZip.MAX_UNPACKED_BYTES;
import static com.bifos.assistant.skill.application.SkillPackageZip.MAX_ZIP_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.bifos.assistant.skill.application.ReceivedSkillPackage;
import com.bifos.assistant.skill.application.SkillPackageEntry;
import com.bifos.assistant.skill.application.SkillPackageProblem;
import com.bifos.assistant.skill.application.SkillPackageZip;
import com.bifos.assistant.skill.application.model.SkillPackageReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 믿을 수 없는 zip 을 메모리에서 받을 때 위험한 항목과 상한을 문제 하나로 막는지 본다.
 *
 * <p>시험 zip 은 Commons Compress 의 {@link ZipArchiveOutputStream} 으로 메모리 채널에 만든다. 채널에 쓰면 STORED 항목도 크기와
 * CRC 를 미리 정하지 않아도 된다. 깨진 zip 은 만든 바이트의 정해진 자리를 바꿔 얻는다.
 */
class SkillPackageZipTest {

    private static final int MIB = 1024 * 1024;
    private static final int LOCAL_HEADER_METHOD_OFFSET = 8;
    private static final int CENTRAL_HEADER_METHOD_OFFSET = 10;
    private static final int END_OF_CENTRAL_DIRECTORY_BYTES = 22;
    private static final int END_OF_CENTRAL_DIRECTORY_CD_OFFSET = 16;

    private final SkillPackageZip skillPackageZip = new SkillPackageZip();

    @Test
    @DisplayName("정상 zip 은 디렉터리 항목을 빼고 경로와 바이트를 그대로 준다")
    void returnsPathsAndBytesWithoutDirectories() throws IOException {
        byte[] skillMd = "---\nname: pdf-forms\n---\n# 본문\n".getBytes(StandardCharsets.UTF_8);
        byte[] script = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);
        byte[] zip = zip(out -> {
            directory(out, "pdf-forms/");
            write(out, entry("pdf-forms/SKILL.md", ZipEntry.DEFLATED), skillMd);
            ZipArchiveEntry run = entry("pdf-forms/scripts/run.sh", ZipEntry.STORED);
            run.setUnixMode(0100755);
            write(out, run, script);
        });

        ReceivedSkillPackage received = skillPackageZip.read(zip);

        assertThat(received.problem()).isNull();
        assertThat(received.entries())
                .extracting(SkillPackageEntry::path)
                .containsExactly("pdf-forms/SKILL.md", "pdf-forms/scripts/run.sh");
        assertThat(received.entries().get(0).content()).isEqualTo(skillMd);
        assertThat(received.entries().get(1).content()).isEqualTo(script);
    }

    @Test
    @DisplayName("풀린 합계가 상한과 같으면 받는다")
    void acceptsUnpackedTotalEqualToLimit() throws IOException {
        byte[] zip = zip(out -> {
            write(out, entry("a.md", ZipEntry.DEFLATED), new byte[MAX_UNPACKED_BYTES - 1]);
            write(out, entry("b.md", ZipEntry.STORED), new byte[1]);
        });

        ReceivedSkillPackage received = skillPackageZip.read(zip);

        assertThat(received.problem()).isNull();
        assertThat(received.entries())
                .extracting(entry -> entry.content().length)
                .containsExactly(MAX_UNPACKED_BYTES - 1, 1);
    }

    @Test
    @DisplayName("풀린 합계가 상한을 넘는 순간 멈추고 뒤의 깨진 항목을 읽지 않는다")
    void stopsAtTheMomentUnpackedTotalExceedsLimit() throws IOException {
        byte[] zip = zip(out -> {
            write(out, entry("big.md", ZipEntry.DEFLATED), new byte[5 * MIB]);
            write(out, entry("broken.md", ZipEntry.DEFLATED), "깨질 항목".getBytes(StandardCharsets.UTF_8));
        });
        zip[(int) dataOffset(zip, "broken.md")] = (byte) 0xFF;

        assertProblem(skillPackageZip.read(zip), SkillPackageReason.UNPACKED_TOO_LARGE, null);
    }

    @Test
    @DisplayName("1 GiB 로 풀리는 압축 폭탄은 예외 없이 풀린 크기 초과로 끝난다")
    void rejectsZipBombWithoutThrowing() throws IOException {
        byte[] zip = zip(out -> {
            out.putArchiveEntry(entry("bomb.md", ZipEntry.DEFLATED));
            byte[] zeros = new byte[MIB];
            for (int i = 0; i < 1024; i++) {
                out.write(zeros);
            }
            out.closeArchiveEntry();
        });
        assertThat(zip.length).as("폭탄 zip 은 받기의 zip 상한 안에 든다").isLessThanOrEqualTo(MAX_ZIP_BYTES);

        AtomicReference<ReceivedSkillPackage> received = new AtomicReference<>();
        assertThatCode(() -> received.set(skillPackageZip.read(zip))).doesNotThrowAnyException();

        assertProblem(received.get(), SkillPackageReason.UNPACKED_TOO_LARGE, null);
    }

    @Test
    @DisplayName("깨진 DEFLATE 는 예외 없이 그 항목의 위험한 항목으로 끝난다")
    void rejectsBrokenDeflateWithoutThrowing() throws IOException {
        byte[] zip = zip(
                out -> write(out, entry("x.md", ZipEntry.DEFLATED), "hello hello".getBytes(StandardCharsets.UTF_8)));
        // 첫 바이트가 0xFF 면 BTYPE 이 예약값 11 이 되어 inflate 가 실패한다.
        zip[(int) dataOffset(zip, "x.md")] = (byte) 0xFF;

        AtomicReference<ReceivedSkillPackage> received = new AtomicReference<>();
        assertThatCode(() -> received.set(skillPackageZip.read(zip))).doesNotThrowAnyException();

        assertProblem(received.get(), SkillPackageReason.UNSAFE_ENTRY, "x.md");
    }

    @Test
    @DisplayName("데이터가 적힌 CRC 와 다르면 위험한 항목이다")
    void rejectsCrcMismatch() throws IOException {
        byte[] zip = zip(out -> write(out, entry("x.md", ZipEntry.STORED), "hello".getBytes(StandardCharsets.UTF_8)));
        int offset = (int) dataOffset(zip, "x.md");
        assertThat(zip[offset]).as("STORED 데이터의 첫 바이트").isEqualTo((byte) 'h');
        zip[offset] = (byte) 'j';

        assertProblem(skillPackageZip.read(zip), SkillPackageReason.UNSAFE_ENTRY, "x.md");
    }

    @Test
    @DisplayName("받지 않는 압축 방식은 Error 없이 위험한 항목이다")
    void rejectsUnsupportedMethodWithoutError() throws IOException {
        byte[] zip = zip(out -> write(out, entry("x.md", ZipEntry.STORED), "hello".getBytes(StandardCharsets.UTF_8)));
        int localHeader = (int) localHeaderOffset(zip, "x.md");
        int centralHeader = centralDirectoryOffset(zip);
        writeShortLe(zip, localHeader + LOCAL_HEADER_METHOD_OFFSET, 93);
        writeShortLe(zip, centralHeader + CENTRAL_HEADER_METHOD_OFFSET, 93);

        AtomicReference<ReceivedSkillPackage> received = new AtomicReference<>();
        assertThatCode(() -> received.set(skillPackageZip.read(zip))).doesNotThrowAnyException();

        assertProblem(received.get(), SkillPackageReason.UNSAFE_ENTRY, "x.md");
    }

    @Test
    @DisplayName("원래 이름 바이트에 역슬래시가 있으면 위험한 항목이다")
    void rejectsBackslashInRawName() throws IOException {
        byte[] zip = zip(out -> write(out, entry("a_b.md", ZipEntry.STORED), "hello".getBytes(StandardCharsets.UTF_8)));
        int replaced = replaceAll(
                zip, "a_b.md".getBytes(StandardCharsets.US_ASCII), "a\\b.md".getBytes(StandardCharsets.US_ASCII));
        assertThat(replaced).as("로컬 머리와 중앙 디렉터리의 이름").isEqualTo(2);

        ReceivedSkillPackage received = skillPackageZip.read(zip);

        assertThat(received.entries()).isEmpty();
        assertThat(received.problem().reason()).isEqualTo(SkillPackageReason.UNSAFE_ENTRY);
    }

    @Test
    @DisplayName("zip 상한을 넘는 바이트는 zip 크기 초과다")
    void rejectsTooLargeZip() {
        assertProblem(skillPackageZip.read(new byte[MAX_ZIP_BYTES + 1]), SkillPackageReason.ZIP_TOO_LARGE, null);
    }

    @Test
    @DisplayName("zip 이 아닌 바이트는 zip 아님이다")
    void rejectsRandomBytes() {
        byte[] bytes = new byte[4096];
        new Random(7).nextBytes(bytes);

        assertProblem(skillPackageZip.read(bytes), SkillPackageReason.NOT_ZIP, null);
    }

    @Test
    @DisplayName("항목이 상한만큼이면 받고 하나 더 많으면 항목 수 초과다")
    void rejectsEntriesOverLimit() throws IOException {
        byte[] atLimit = zip(out -> {
            for (int i = 0; i < MAX_ENTRIES; i++) {
                write(out, entry("f" + i + ".md", ZipEntry.STORED), new byte[] {'x'});
            }
        });
        byte[] overLimit = zip(out -> {
            for (int i = 0; i <= MAX_ENTRIES; i++) {
                write(out, entry("f" + i + ".md", ZipEntry.STORED), new byte[] {'x'});
            }
        });

        assertThat(skillPackageZip.read(atLimit).entries()).hasSize(MAX_ENTRIES);
        assertProblem(skillPackageZip.read(overLimit), SkillPackageReason.TOO_MANY_ENTRIES, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.md", "/etc/x", "C:/x.md", "a//b.md", "a/./b.md", "a/\tb.md"})
    @DisplayName("위험한 경로는 그 이름과 함께 위험한 항목이다")
    void rejectsUnsafePaths(String name) throws IOException {
        byte[] zip = zip(out -> {
            write(out, entry("SKILL.md", ZipEntry.STORED), new byte[] {'x'});
            write(out, entry(name, ZipEntry.STORED), new byte[] {'x'});
        });

        assertProblem(skillPackageZip.read(zip), SkillPackageReason.UNSAFE_ENTRY, name);
    }

    @Test
    @DisplayName("심볼릭 링크 항목은 위험한 항목이다")
    void rejectsSymlink() throws IOException {
        byte[] zip = zip(out -> {
            ZipArchiveEntry link = entry("link.md", ZipEntry.STORED);
            link.setUnixMode(0120777);
            write(out, link, "/etc/passwd".getBytes(StandardCharsets.UTF_8));
        });

        assertProblem(skillPackageZip.read(zip), SkillPackageReason.UNSAFE_ENTRY, "link.md");
    }

    @Test
    @DisplayName("같은 이름의 항목 둘은 위험한 항목이다")
    void rejectsDuplicateNames() throws IOException {
        byte[] zip = zip(out -> {
            write(out, entry("SKILL.md", ZipEntry.STORED), new byte[] {'a'});
            write(out, entry("SKILL.md", ZipEntry.STORED), new byte[] {'b'});
        });

        assertProblem(skillPackageZip.read(zip), SkillPackageReason.UNSAFE_ENTRY, "SKILL.md");
    }

    private static void assertProblem(ReceivedSkillPackage received, SkillPackageReason reason, String path) {
        assertThat(received.entries()).as("실패한 받기의 목록").isEmpty();
        assertThat(received.problem()).isEqualTo(new SkillPackageProblem(reason, path));
    }

    @FunctionalInterface
    private interface ZipWriter {
        void write(ZipArchiveOutputStream out) throws IOException;
    }

    private static byte[] zip(ZipWriter writer) throws IOException {
        SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(channel)) {
            // 바이트를 바꾸는 시험이 머리의 자리를 알 수 있게 Zip64 추가 칸을 넣지 않는다.
            out.setUseZip64(Zip64Mode.Never);
            writer.write(out);
        }
        return Arrays.copyOf(channel.array(), (int) channel.size());
    }

    private static ZipArchiveEntry entry(String name, int method) {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(method);
        return entry;
    }

    private static void write(ZipArchiveOutputStream out, ZipArchiveEntry entry, byte[] content) throws IOException {
        out.putArchiveEntry(entry);
        out.write(content);
        out.closeArchiveEntry();
    }

    private static void directory(ZipArchiveOutputStream out, String name) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(0040755);
        out.putArchiveEntry(entry);
        out.closeArchiveEntry();
    }

    private static long dataOffset(byte[] zip, String name) throws IOException {
        try (ZipFile zipFile = open(zip)) {
            return zipFile.getEntry(name).getDataOffset();
        }
    }

    private static long localHeaderOffset(byte[] zip, String name) throws IOException {
        try (ZipFile zipFile = open(zip)) {
            return zipFile.getEntry(name).getLocalHeaderOffset();
        }
    }

    private static ZipFile open(byte[] zip) throws IOException {
        return ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip))
                .get();
    }

    /** 주석이 없는 zip 의 끝 머리에서 중앙 디렉터리의 시작 자리를 읽는다. */
    private static int centralDirectoryOffset(byte[] zip) {
        int at = zip.length - END_OF_CENTRAL_DIRECTORY_BYTES + END_OF_CENTRAL_DIRECTORY_CD_OFFSET;
        return (zip[at] & 0xFF) | (zip[at + 1] & 0xFF) << 8 | (zip[at + 2] & 0xFF) << 16 | (zip[at + 3] & 0xFF) << 24;
    }

    private static void writeShortLe(byte[] zip, int at, int value) {
        zip[at] = (byte) (value & 0xFF);
        zip[at + 1] = (byte) (value >> 8 & 0xFF);
    }

    private static int replaceAll(byte[] bytes, byte[] from, byte[] to) {
        int count = 0;
        for (int i = 0; i + from.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, bytes, i, to.length);
                count++;
            }
        }
        return count;
    }
}
