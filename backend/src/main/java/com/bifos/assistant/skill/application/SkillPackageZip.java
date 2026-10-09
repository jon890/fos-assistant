package com.bifos.assistant.skill.application;

import static com.bifos.assistant.skill.application.model.SkillPackageReason.NOT_ZIP;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.TOO_MANY_ENTRIES;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.UNPACKED_TOO_LARGE;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.UNSAFE_ENTRY;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.ZIP_TOO_LARGE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Component;

/**
 * 믿을 수 없는 zip 바이트를 메모리 안에서 경로와 바이트의 목록으로 바꾼다. 디스크에 풀지 않는다.
 *
 * <p>JDK 의 {@code ZipInputStream} 은 로컬 머리만 읽어 unix mode 가 없으므로 심볼릭 링크를 가려내지 못한다. 그래서 Commons
 * Compress 의 {@link ZipFile} 로 중앙 디렉터리를 읽는다. 항목 머리의 크기 칸은 믿지 않고 실제로 푼 바이트를 센다. 처음 걸리는
 * 문제 하나로 끝내고 예외를 밖으로 던지지 않는다(ADR-20261009-skill-package).
 */
@Component
public class SkillPackageZip {

    public static final int MAX_ZIP_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_UNPACKED_BYTES = 4 * 1024 * 1024;

    private static final int READ_CHUNK_BYTES = 8 * 1024;
    private static final byte BACKSLASH = 0x5C;
    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:");
    private static final int FILE_TYPE_MASK = 0170000;
    private static final int REGULAR_FILE = 0100000;
    private static final int DIRECTORY = 0040000;

    public ReceivedSkillPackage read(byte[] zip) {
        if (zip.length > MAX_ZIP_BYTES) {
            return ReceivedSkillPackage.failed(ZIP_TOO_LARGE, null);
        }
        ZipFile zipFile;
        try {
            zipFile = open(zip);
        } catch (IOException | RuntimeException e) {
            return ReceivedSkillPackage.failed(NOT_ZIP, null);
        }
        try (zipFile) {
            return readEntries(zipFile);
        } catch (IOException e) {
            // 메모리 채널을 닫는 일만 여기 온다. 항목을 읽는 중의 IOException 은 항목의 문제로 바꿨다.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Unicode Path 추가 칸을 쓰지 않는다. 그 칸은 원래 이름 바이트와 다른 이름을 줄 수 있어, 원래 바이트에서 {@code \} 를 찾는
     * 검사를 비켜 갈 수 있다. 이름은 원래 바이트를 UTF-8 로 읽은 것 하나다.
     */
    private static ZipFile open(byte[] zip) throws IOException {
        return ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip))
                .setUseUnicodeExtraFields(false)
                .get();
    }

    private static ReceivedSkillPackage readEntries(ZipFile zipFile) {
        List<ZipArchiveEntry> entries = Collections.list(zipFile.getEntries());
        if (entries.size() > MAX_ENTRIES) {
            return ReceivedSkillPackage.failed(TOO_MANY_ENTRIES, null);
        }
        List<ZipArchiveEntry> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ZipArchiveEntry entry : entries) {
            String name = entry.getName();
            if (!isSafeName(entry) || isSpecial(entry) || !isReadable(zipFile, entry)) {
                return ReceivedSkillPackage.failed(UNSAFE_ENTRY, name);
            }
            if (entry.isDirectory()) {
                continue;
            }
            if (!names.add(name)) {
                return ReceivedSkillPackage.failed(UNSAFE_ENTRY, name);
            }
            files.add(entry);
        }
        return unpack(zipFile, files);
    }

    /**
     * 모든 항목을 차례로 풀며 합계를 센다. 합계가 상한을 넘는 순간 그 항목을 끝까지 읽지 않고 멈춘다. 압축 폭탄도 상한과 한
     * 덩어리 너머로는 풀리지 않는다.
     */
    private static ReceivedSkillPackage unpack(ZipFile zipFile, List<ZipArchiveEntry> files) {
        List<SkillPackageEntry> unpacked = new ArrayList<>(files.size());
        long total = 0;
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        for (ZipArchiveEntry entry : files) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            CRC32 crc = new CRC32();
            try (InputStream in = zipFile.getInputStream(entry)) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_UNPACKED_BYTES) {
                        return ReceivedSkillPackage.failed(UNPACKED_TOO_LARGE, null);
                    }
                    content.write(chunk, 0, read);
                    crc.update(chunk, 0, read);
                }
            } catch (IOException | RuntimeException e) {
                // 깨진 DEFLATE 와 어긋난 로컬 머리가 여기 온다.
                return ReceivedSkillPackage.failed(UNSAFE_ENTRY, entry.getName());
            }
            // ZipFile.getInputStream 은 CRC 를 확인하지 않는다.
            if (crc.getValue() != entry.getCrc()) {
                return ReceivedSkillPackage.failed(UNSAFE_ENTRY, entry.getName());
            }
            unpacked.add(new SkillPackageEntry(entry.getName(), content.toByteArray()));
        }
        return new ReceivedSkillPackage(List.copyOf(unpacked), null);
    }

    /**
     * 라이브러리는 FAT 항목 이름의 {@code \} 를 {@code /} 로 바꿔 주므로 {@code \} 는 원래 이름 바이트에서 찾는다. 나머지는
     * 라이브러리가 준 이름을 정규화하지 않고 본다.
     */
    private static boolean isSafeName(ZipArchiveEntry entry) {
        for (byte b : entry.getRawName()) {
            if (b == BACKSLASH) {
                return false;
            }
        }
        String name = entry.getName();
        if (name.startsWith("/") || DRIVE_LETTER.matcher(name).find()) {
            return false;
        }
        if (name.codePoints().anyMatch(SkillPackageZip::isInvisible)) {
            return false;
        }
        String path = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 제어 문자(C0, DEL, C1), 서식 문자(U+202E 같은 방향 바꿈), 줄과 문단 구분자다. 화면에 보이는 이름과 실제 이름이 달라지거나
     * 줄이 나뉠 수 있다.
     */
    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** 심볼릭 링크, 장치 파일처럼 unix mode 의 파일 종류가 일반 파일도 디렉터리도 아닌 항목이다. */
    private static boolean isSpecial(ZipArchiveEntry entry) {
        if (entry.isUnixSymlink()) {
            return true;
        }
        if (entry.getPlatform() != ZipArchiveEntry.PLATFORM_UNIX) {
            return false;
        }
        int type = entry.getUnixMode() & FILE_TYPE_MASK;
        return type != 0 && type != REGULAR_FILE && type != DIRECTORY;
    }

    /**
     * 암호가 없고 저장이나 DEFLATE 인 항목만 읽는다. ZSTD 와 XZ 는 {@code canReadEntryData} 가 참이지만 그 optional 의존이 없어
     * 읽을 때 {@code NoClassDefFoundError} 가 나므로 방식을 먼저 제한한다.
     */
    private static boolean isReadable(ZipFile zipFile, ZipArchiveEntry entry) {
        if (entry.getGeneralPurposeBit().usesEncryption()) {
            return false;
        }
        int method = entry.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            return false;
        }
        return zipFile.canReadEntryData(entry);
    }
}
