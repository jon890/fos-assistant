# Phase 02. zip 을 메모리에서 안전하게 받는다

**Execution profile**: deep

## 목표

`skill/application/SkillPackageZip` 이 zip 바이트를 경로와 바이트의 목록(`ReceivedSkillPackage`)으로 바꾼다. 디스크에 풀지 않는다.
경로 조작, 심볼릭 링크와 특수 항목, 암호, 같은 이름, 압축 폭탄, 깨진 압축을 시험으로 막는다.
받기를 검사와 나눠, 다음 plan 의 GitHub 가져오기가 받기만 더하게 한다.

**범위 외**: 경로 규칙과 글 파일, 비밀값, 앞머리 검사(phase 03). API(phase 04).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-skill-package.md`, `backend/docs/flow.md` 의 「스킬 묶음 받기와 검사」 > 「받기」

- 의존 버전 목록은 `backend/gradle/libs.versions.toml`(`[versions]`, `[libraries]`), 쓰는 곳은 `backend/build.gradle.kts` 의 `dependencies` 다
- `application` 과 `domain` 은 타입 하나에 파일 하나다. 저장되지 않는 enum 은 `skill/application/model` 에 둔다(`backend/AGENTS.md`)
- 시험 패키지는 `com.bifos.assistant.skill` 이라 시험이 쓰는 타입과 메서드는 `public` 이다

## 의도 메모

- `SkillPackageZip` 은 `application` 에 둔다. 바깥 시스템을 부르지 않는 메모리 안의 해석이고, 결과의 까닭 enum 이 `application.model` 에 있어 `infra` 에 두면 층이 거꾸로 선다
- JDK `ZipInputStream` 을 쓰지 않는다. 로컬 머리만 읽어 unix mode 가 없고 심볼릭 링크를 가려내지 못한다. Commons Compress 의 `ZipFile` 을 `SeekableInMemoryByteChannel` 로 열어 중앙 디렉터리를 읽는다
- 항목 머리의 크기 칸(`getSize`)은 믿지 않는다. 실제로 읽은 바이트를 센다. 압축률은 따로 보지 않는다
- 받기가 실패하면 문제 하나로 끝낸다. 500 으로 올리지 않는다

## 작업 항목

### 1. 의존을 더한다

- `backend/gradle/libs.versions.toml` 의 `[versions]` 에 `commons-compress = "1.28.0"`, `[libraries]` 에 `commons-compress = { module = "org.apache.commons:commons-compress", version.ref = "commons-compress" }`
- `backend/build.gradle.kts` 의 `dependencies` 에 `implementation(libs.commons.compress)`

### 2. 받기의 타입

- `skill/application/model/SkillPackageReason.java`: enum. `NOT_ZIP`, `ZIP_TOO_LARGE`, `TOO_MANY_ENTRIES`, `UNPACKED_TOO_LARGE`, `UNSAFE_ENTRY`, `NO_SKILL_MD`, `PATH_NOT_ALLOWED`, `NESTED_SKILL_MD`, `NOT_TEXT`, `FILE_TOO_LARGE`, `TOO_MANY_FILES`, `TOTAL_TOO_LARGE`, `SECRET_VALUE`, `FRONTMATTER_INVALID`, `NAME_INVALID`, `NO_BODY`, `SECRET_REQUEST`, `DESCRIPTION_TOO_LONG`, `NAME_TAKEN`, `LIMIT_REACHED`, `SCRIPTS_NEED_SANDBOX`. 미리보기가 보일 까닭 전부다
- `skill/application/SkillPackageProblem.java`: record `(SkillPackageReason reason, String path)`. `path` 는 없으면 `null`
- `skill/application/SkillPackageEntry.java`: record `(String path, byte[] content)`. `path` 는 zip 안의 원래 경로다
- `skill/application/ReceivedSkillPackage.java`: record `(List<SkillPackageEntry> entries, SkillPackageProblem problem)`. 실패면 `entries` 는 빈 목록이고 `problem` 이 차 있다. 성공이면 `problem` 은 `null`. 정적 팩터리 `failed(SkillPackageReason reason, String path)` 를 둔다

### 3. `skill/application/SkillPackageZip.java`

- `@Component public class SkillPackageZip`. `public ReceivedSkillPackage read(byte[] zip)`
- 상수: `public static final int MAX_ZIP_BYTES = 2 * 1024 * 1024`, `MAX_ENTRIES = 200`, `MAX_UNPACKED_BYTES = 4 * 1024 * 1024`
- 순서. 처음 걸리는 문제 하나로 끝낸다. 4~8 은 모든 항목에 먼저 하고, 9 는 그 뒤에 항목마다 한다
  1. `zip.length > MAX_ZIP_BYTES` → `ZIP_TOO_LARGE`
  2. `ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(zip)).get()` 이 `IOException` 이나 `RuntimeException` 을 던지면 `NOT_ZIP`
  3. 항목(`getEntries()`, 중앙 디렉터리 순서) 수가 `MAX_ENTRIES` 를 넘으면 `TOO_MANY_ENTRIES`
  4. 항목마다 이름 검사. `entry.getRawName()` 바이트에 `0x5C`(`\`)가 있으면 `UNSAFE_ENTRY`. Commons Compress 는 FAT 항목 이름의 `\` 를 `/` 로 바꿔 `getName()` 에 주므로 원래 바이트를 본다. 그 밖에 `getName()` 이 `/` 로 시작, `^[A-Za-z]:`, 조각(`/` 로 나눈 것, 디렉터리 항목의 끝 `/` 는 뗀다)이 빈 것, `.`, `..`, 문자 코드 32 미만이나 127 이 있으면 `UNSAFE_ENTRY`(path 는 그 이름)
  5. `entry.isUnixSymlink()` 이거나, `getPlatform() == ZipArchiveEntry.PLATFORM_UNIX` 이고 `getUnixMode()` 의 파일 종류(`mode & 0170000`)가 0 이 아니면서 일반 파일(`0100000`)도 디렉터리(`0040000`)도 아니면 `UNSAFE_ENTRY`
  6. `entry.getGeneralPurposeBit().usesEncryption()` 이거나, `entry.getMethod()` 가 `ZipEntry.STORED` 도 `ZipEntry.DEFLATED` 도 아니거나, `!zipFile.canReadEntryData(entry)` 면 `UNSAFE_ENTRY`. ZSTD, XZ 는 `canReadEntryData` 가 참이지만 그 optional 의존이 없어 읽을 때 `NoClassDefFoundError` 가 나므로 방식을 먼저 제한한다
  7. 디렉터리 항목(`isDirectory()`)은 목록에 넣지 않는다
  8. 같은 이름이 두 번 나오면 `UNSAFE_ENTRY`
  9. `getInputStream(entry)` 를 8 KiB 씩 읽어 지금까지 읽은 모든 항목의 합계가 `MAX_UNPACKED_BYTES` 를 넘는 순간 멈추고 `UNPACKED_TOO_LARGE`. 읽으며 `java.util.zip.CRC32` 를 계산해 다 읽은 뒤 `entry.getCrc()` 와 다르면 `UNSAFE_ENTRY`(`ZipFile.getInputStream` 은 CRC 를 확인하지 않는다). 읽는 중의 `IOException`(깨진 DEFLATE)과 `RuntimeException` 도 그 항목 이름과 함께 `UNSAFE_ENTRY` 다
- 이름은 Commons Compress 가 준 문자열 그대로 쓴다. 정규화하지 않는다
- `ZipFile` 과 스트림은 try-with-resources 로 닫는다

### 4. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/skill/SkillPackageZipTest.java`

시험 zip 은 시험 안에서 Commons Compress 의 `ZipArchiveOutputStream` 으로 만든다. 심볼릭 링크 항목은 `ZipArchiveEntry.setUnixMode(0120777)` 이다.

- 정상 zip 이 경로와 바이트를 그대로 준다. 디렉터리 항목은 빠진다
- 넘는 순간 멈춘다: 첫 항목이 0 바이트 5 MiB(DEFLATED)이고 둘째 항목이 깨진 DEFLATE 인 zip 은 `UNPACKED_TOO_LARGE` 다. 끝까지 읽는 구현은 둘째 항목에서 `UNSAFE_ENTRY` 를 낸다
- 압축 폭탄: 0 바이트 1 GiB 를 DEFLATED 로 담은 항목(2 MiB 안)이 `UNPACKED_TOO_LARGE` 를 내고 예외가 밖으로 나오지 않는다
- 깨진 DEFLATE: DEFLATED 항목 데이터의 첫 바이트를 `0xFF` 로 바꾼 zip(BTYPE 이 예약값 11 이 되어 `ZipException`)은 `UNSAFE_ENTRY` 이고 예외가 밖으로 나오지 않는다
- CRC 불일치: STORED 항목 `hello` 의 데이터 바이트 `h` 를 `j` 로 바꾼 zip 은 `UNSAFE_ENTRY` 다
- 읽지 못하는 압축 방식: STORED 항목의 method 칸(로컬 머리 offset 8, 중앙 디렉터리 머리 offset 10, little-endian 2바이트)을 93 으로 바꾼 zip 은 `UNSAFE_ENTRY` 이고 `Error` 가 밖으로 나오지 않는다
- `\` 이름: 이름 `a_b.md` 로 만든 zip 의 바이트에서 `a_b.md` 를 모두 같은 길이의 `a\b.md` 로 바꾸면 `UNSAFE_ENTRY` 다
- 각각 문제 하나로 끝난다: 2 MiB 를 넘는 바이트(`ZIP_TOO_LARGE`), 무작위 바이트(`NOT_ZIP`), 항목 201개(`TOO_MANY_ENTRIES`), `../evil.md`·`/etc/x`·`C:/x.md`·`a//b.md`·`a/./b.md`(`UNSAFE_ENTRY`, path 는 그 이름), 심볼릭 링크 항목(`UNSAFE_ENTRY`), 같은 이름 둘(`UNSAFE_ENTRY`)

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.SkillPackageZipTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
node scripts/check-file-length.mjs
```

- 셋 다 종료 코드 0. `qualityCheck` 의 포맷 위반이면 `cd backend && ./gradlew spotlessApply` 결과를 기능 커밋 뒤 따로 커밋한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/model/SkillPackageReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageProblem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageEntry.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/ReceivedSkillPackage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageZip.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillPackageZipTest.java` | 신규 |
