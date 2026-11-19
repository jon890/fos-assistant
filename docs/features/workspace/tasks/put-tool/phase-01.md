# Phase 01. 보관함이 새 형식과 25MiB, 제목과 설명을 받는다

**Execution profile**: standard

## 목표

보관함(`chat.artifact`)이 표와 문서 형식을 받고, 파일 하나를 25MiB 까지 받으며, 제목과 설명을 암호화해 둔다. 바이트로 받은 파일 하나를 보관하는 입구를 만든다. 다음 phase 의 넣기 도구가 이 입구를 부른다.

**범위 외**: MCP 도구와 실행 공간 읽기, 답에 묶기, 실행 입력 단락(phase 02). 화면(phase 03). 「내 파일」 목록 화면의 개편(PR2).

## 컨텍스트

보관함은 `chat_artifact` 의 `vault_user_id` 가 채워진 행이다. 지금은 `ArtifactVaultService.vault` 가 대화 폴더의 HTML, CSS, 사진만 `ArtifactBundleReader` 로 훑어 넣는다.
형식 판정은 대화 폴더가 내주는 형식(`ArtifactStore.contentTypeOf`)에 묶여 있어 새 형식을 받지 못한다. 이 phase 는 보관함의 형식을 `VaultFileType` 으로 떼어 낸다. 대화 폴더가 내주는 형식은 넓히지 않는다.
`vault_body` 는 지연 로딩이 아닌 `LONGTEXT` 라 엔티티로 읽는 조회는 본문을 함께 읽는다. 상한을 올리므로 본문이 필요 없는 조회는 projection 으로 바꾼다(`listVaulted` 와 `ArtifactVaultRow` 가 본보기다).

**요구사항**: REQ4, REQ5, REQ6

**근거 문서**: `docs/features/workspace/spec.md`, `docs/features/workspace/design.md` 의 「받는 형식」, 「데이터」, 「실패와 일관성」, `docs/adr/ADR-20261119-file-space-put.md`, `docs/adr/ADR-20261101-artifact-vault.md`

## 의도 메모

- 대화 폴더의 `CONTENT_TYPES` 를 넓히지 않는다. 넓히면 `ArtifactController` 가 화면에 내주는 형식이 함께 넓어진다
- 제목과 설명은 평문으로 두지 않는다. 암호화를 쓸 수 없으면 본문 보관이 이미 거절된다
- 마이그레이션 파일 이름은 예약한 `V20261105074419__chat_artifact_file_space.sql` 그대로 쓴다. 다른 번호를 고르지 않는다
- 기존 `ARTIFACT_VAULT_UNSUPPORTED` 오류 코드와 자동 보관의 `auto_vault_error` 값은 바꾸지 않는다
- 파일 길이 상한은 500줄이다(`scripts/checks/check-file-length.mjs`). `ArtifactVaultService` 는 지금 326줄이라 제목 처리를 `ArtifactVaultTitles` 로 뺀다
- 「보관」 단추와 자동 보관이 받는 형식은 그대로다(대화 폴더의 HTML, CSS, 사진). 묶음 훑기는 `ArtifactService.find` 가 대화 폴더의 형식으로 막는다. 새 형식은 `keep` 으로만 들어온다. 두 길에 같게 바뀌는 것은 파일 하나 상한이다

## 작업 항목

### 1. 마이그레이션 `V20261105074419__chat_artifact_file_space.sql`

`chat_artifact` 에 컬럼 넷을 더한다. 모두 NULL 허용이고 COMMENT 를 붙인다(기존 `V20261105074416__chat_artifact_generated_image.sql` 의 꼴).

- `vault_meta LONGTEXT`, `vault_meta_key_id BIGINT`, `vault_origin VARCHAR(16)`, `vault_message_id BIGINT`
- `vault_message_id` 에 인덱스 하나를 둔다

`backend/docs/data-schema.md` 의 `chat_artifact` 절에 네 컬럼의 뜻을 더한다. `test/repo/migration-checksums.json` 등 저장소 검사가 요구하는 기록은 `scripts/dev/check-local.sh --fast` 의 안내대로 갱신한다.

### 2. `chat/artifact/domain/VaultFileType.java`(신규)와 `ChatArtifact`

- `VaultFileType.of(String path)` 는 확장자로 `Optional<VaultFileType>` 을 준다. 값마다 종류(`IMAGE`, `DOCUMENT`, `TABLE`), 내줄 `Content-Type`, 화면에서 그대로 열어도 되는지(`inline`)를 갖는다. 표는 설계의 「받는 형식」 이다. 기존 형식(html, htm, css, 사진)은 `ArtifactPathPolicy.contentTypeOf` 와 같은 `Content-Type` 과 `inline` 참, 새 형식은 `application/octet-stream` 과 `inline` 거짓이다
- `matches(byte[] head)` 는 이진 형식의 머리 바이트(PNG, JPEG, GIF, WebP, `%PDF-`, zip 의 `PK`)를 보고, 글 형식은 NUL 바이트가 없는지 본다
- `chat/artifact/domain/type/VaultOrigin.java`(신규) enum: `PUT`, `PUT_WORKSPACE`. `ChatArtifact.vaultOrigin` 은 `@Enumerated(EnumType.STRING)` 이다(`autoVaultState` 의 꼴)
- `ChatArtifact` 에 `vaultMeta`, `vaultMetaKeyId`, `vaultOrigin`, `vaultMessageId` 필드와 getter 를 더한다. 보관 뿌리 행을 만드는 정적 메서드가 meta 와 origin 을 받게 한다

### 3. `chat/artifact/infra/ArtifactVaultMeta.java`(신규)

제목과 설명의 JSON 을 `TextCipher` 로 암호화하고 푼다. `GeneratedImageService` 의 `seal`/`promptOf` 가 본보기다.
암호문의 자리(AAD)는 사용자, 대화 번호, 경로로 묶는다. 풀지 못하면 빈 값을 준다. 암호화를 쓸 수 없으면 `Optional.empty()` 를 주어 부르는 쪽이 제목을 버리게 한다.
제목은 앞뒤 공백을 떼고 200자, 설명은 500자로 자른다. 빈 제목은 없는 것으로 본다.

### 4. `ArtifactVaultService`, `ArtifactVaultTitles`(신규), `ChatArtifactRepository`

`keep` 의 거절은 아래 코드와 고정 메시지로 던진다. phase 02 의 도구가 이 표로 까닭을 나눈다.

| 거절 | 오류 코드 | 메시지 |
| --- | --- | --- |
| 받지 않는 확장자 | `ARTIFACT_VAULT_UNSUPPORTED` | `this kind of file is not kept` |
| 머리 바이트가 형식과 맞지 않음, 글 형식에 NUL | `ARTIFACT_VAULT_UNSUPPORTED` | `this file does not match its kind` |
| 파일 하나 상한 초과, 빈 파일 | `ARTIFACT_VAULT_UNSUPPORTED` | `this file is larger than the vault limit`(빈 파일은 `this file is empty`) |
| 사용자 용량 초과 | `ARTIFACT_VAULT_FULL` | 기존 그대로 |
| 보관 자리가 차 있음, 암호화를 쓸 수 없음 | `ARTIFACT_VAULT_UNAVAILABLE` | 기존 그대로 |


- `keep(Long ownerUserId, Long conversationId, VaultOrigin origin, String path, byte[] bytes, String title, String description, Duration wait)` 를 더한다. `origin` 은 `PUT`(대화 폴더에서 읽은 파일), `PUT_WORKSPACE`(실행 공간에서 읽은 파일)다. 형식(`VaultFileType`)과 머리 바이트, 파일 하나 상한, 사용자 용량을 보고 기존 `save` 와 같은 잠금·트랜잭션으로 뿌리 행 하나를 넣는다. `PUT_WORKSPACE` 의 지문은 `/workspace/` 를 붙인 경로로 만든다. 결과는 보관 항목과 `already` 여부를 담은 record `ArtifactKept`(`application/dto`, 신규)다
- 대화 폴더의 묶음 보관에 origin 과 제목·설명, 기다리는 시간을 받는 공개 메서드 `keepBundle(Long ownerUserId, Long conversationId, String relativePath, String title, String description, Duration wait)` 를 두어 넣기 도구가 HTML 묶음을 `PUT` 으로 넣게 한다. 결과는 `ArtifactKept` 다. 공개 `vault`, `vaultAutomatically` 의 동작은 그대로다
- 보관 자리(`running`)는 `wait` 가 있으면 `tryAcquire(wait)` 로 기다린다
- 같은 지문의 살아 있는 행이 있으면 `already` 다. 그 행의 `vault_origin` 이 `PUT`, `PUT_WORKSPACE` 면 `vault_message_id` 를 비운다(다음 답에 다시 묶인다). 지운 이력이 있으면 `revive` 규칙대로 지우고 새로 만든다
- 제목 처리는 `ArtifactVaultTitles` 가 맡는다. 쓸 제목과 설명은 이 순서로 정한다. 부르는 쪽이 준 값이 있으면 그것, 없으면 그 대화·경로의 결과물 행(`vault_user_id` 가 빈 행) 가운데 `vault_meta` 가 있는 가장 새 행의 값을 풀어 쓴다(phase 02 의 `artifact_write` 가 그 행을 만든다. 파일이 지워진 행도 후보다). 둘 다 없으면 `vault_meta` 를 비운다
- 정한 제목이 같은 대화에서 그 사용자의 살아 있는 보관 뿌리 행의 제목과 겹치면 ` 2`, ` 3` 을 붙인다. 같은 경로와 같은 origin 의 행은 겹침에서 뺀다. 사용자별 잠금 안에서 정하고, 정한 제목으로 새로 암호화해 뿌리 행에 둔다. 조회는 projection 이다
- `content` 가 내줄 형식을 `VaultFileType` 에서 읽는다(지금은 `ArtifactBundleReader.contentTypeOf` 가 새 형식에서 예외를 던진다). `requireVaultable` 은 그대로 둔다. `ArtifactVaultController.body` 는 `inline` 이 거짓인 형식을 `download` 값과 상관없이 `attachment` 로 준다
- `forgetVaulted` 의 `set` 에 `vault_meta`, `vault_meta_key_id` 비우기를 더한다
- `findByVaultUserIdAndVaultFingerprint` 와 `findLiveVaultCopies` 를 본문을 뺀 projection 으로 바꾸고 부르는 곳(`ArtifactVaultService`, `GeneratedImageService.details`)을 맞춘다. `findLiveVaultCopies` 는 `PUT_WORKSPACE` 행을 뺀다
- `listVaulted` 가 `vault_meta`, key 번호, origin 을 함께 읽고, `ArtifactVaultItem` 과 `VaultItemView` 에 `title`, `description`, `kind` 를 더한다

### 5. 상한

`ArtifactVaultProperties.DEFAULT_MAX_FILE_BYTES` 를 25MiB 로 올리고 Javadoc 의 「결과물 쓰기 도구의 한 파일 상한과 같다」 를 고친다. `artifact_write` 의 5MiB 는 그대로다.

### 6. 테스트 `ArtifactVaultFileTypeTest`(신규), `VaultFileTypeTest`(신규), 기존 `ArtifactVaultTest`

- `ArtifactVaultFileTypeTest` 에 더할 사례: 용량이 찬 뒤에도 있던 파일은 `read` 로 열린다. 다른 사용자가 그 번호를 `read` 하면 `ARTIFACT_NOT_FOUND` 다. `vault_meta` 를 가진 결과물 행을 직접 넣어 두고 「보관」(`vault`)하면 보관본의 제목이 그 값이다. `keep` 에 제목을 주면 결과물 행의 제목보다 앞선다

- `VaultFileTypeTest`: 확장자별 종류, 받지 않는 확장자(zip, sh), 머리 바이트가 맞지 않는 xlsx 와 pdf, NUL 이 든 csv
- `ArtifactVaultFileTypeTest`(`@BackendIntegrationTest`): `keep` 으로 xlsx 를 넣고 `read` 한 바이트가 같다. 25MiB 는 들어가고 1바이트 큰 것은 거절된다. 용량 초과는 `ARTIFACT_VAULT_FULL` 이다. 같은 제목 두 번이 「제목」, 「제목 2」 다. 같은 경로를 고쳐 다시 넣으면 번호가 늘지 않는다. DB 의 `vault_meta` 에 평문 제목이 없다. 같은 바이트를 두 번 넣으면 한 건이고 `already` 다. `FOLDER` 와 `WORKSPACE` 의 같은 경로·본문이 두 건이다. 지우면 `vault_meta` 가 비고 다시 `keep` 하면 새로 생긴다. 목록 응답에 제목, 설명, 종류가 있다. xlsx 본문 응답이 `attachment` 다
- `ArtifactVaultTest` 의 5MiB 경계 사례가 있으면 새 상한에 맞춘다

## 검증

```bash
cd backend && ./gradlew test --tests '*VaultFileTypeTest' --tests '*ArtifactVaultFileTypeTest' --tests '*ArtifactVaultTest' --tests '*ArtifactVaultBundleTest' --tests '*ArtifactAutoVaultTest' --tests '*GeneratedImageTurnTest' --tests '*ArchitectureRules*'
node --test test/repo
```

같은 맥에서 여러 worker 가 도므로 Gradle, 브라우저 테스트, `check-local.sh` 줄은 저장소 공통 지시서의 무거운 검사 대기열(`orch slot heavy -- <명령>`)로 감싸 실행한다. 기대값은 모두 통과다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074419__chat_artifact_file_space.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/VaultFileType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/ChatArtifact.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactVaultMeta.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/type/VaultOrigin.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactVaultTitles.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/dto/ArtifactKept.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ChatArtifactRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactVaultRow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactVaultService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/GeneratedImageService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/dto/ArtifactVaultItem.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/config/ArtifactVaultProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/presentation/ArtifactVaultController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/presentation/ArtifactVaultDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/artifact/domain/VaultFileTypeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/artifact/application/ArtifactVaultFileTypeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/artifact/application/ArtifactVaultTest.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `test/repo/migration-checksums.json` | 수정 |
