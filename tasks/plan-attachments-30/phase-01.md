# Phase 01. 사진 첨부 30장 상한 적용

**Execution profile**: standard

## 목표

대화 한 번에 사진 30장을 안전하게 올리고 보내며, 31번째 사진과 첨부 번호는 서버와 화면에서 거절한다.

**범위 외**: 한 장 10MB 상한, multipart 요청 크기, 30일 보관, 사진 형식, Hermes API 형식과 새 저장소·의존성 추가.

## 컨텍스트

`AttachmentProperties.maxFiles()` 는 올리기와 `requireAttachable` 의 공통 장수 판정값이다.
`AttachmentService.upload` 는 아직 메시지에 묶이지 않은 행만 세고, `requireAttachable` 은 메시지 요청의 번호 수와 중복을 함께 검사한다.
`Composer.handleFiles` 는 `MAX_ATTACHMENTS` 에서 남은 자리를 계산해 초과 파일을 올리지 않고 `attachment-notice` 에 장수 안내를 보인다.

사진은 `AttachmentService.agentInput` 이 만든 안내 목록으로만 Hermes 실행 입력에 들어간다.
올린 이름은 제어 문자를 공백으로 바꾸고 최대 255자로 제한하므로, 사진 30장은 목록 30줄만 선형으로 늘어난다.
`assistant.context.max-chars` 는 대화 문맥 조립의 상한이고, `assistant.user-execution.max-running` 은 동시에 도는 실행 수의 상한이므로 이 목록 길이와 별개다.

**근거 문서**: `docs/backend/attachment.md` 의 「사진을 올려 보낼 때」와 「갈리는 지점」, `docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md` 의 「결정」과 「디스크가 찬다」.

## 의도 메모

- 장수의 단일 소스는 `AttachmentProperties.maxFiles()` 와 화면의 `MAX_ATTACHMENTS` 두 곳이다. 화면은 먼저 막고 서버는 직접 요청도 같은 상한으로 막는다.
- multipart 요청 크기를 30장 합계로 넓히지 않는다. 사진은 고를 때 한 장씩 별도 요청으로 올리고, 메시지는 첨부 번호만 보낸다.
- 기존 ADR-020을 대체하지 않는다. 사진 저장 방식과 보관 정책은 유지하고, 날짜와 사용 근거를 그 ADR의 `더해진 부분`으로 남긴다.

## 작업 항목

### 1. `backend/src/main/resources/application.yml`와 `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java`의 장수 기본값을 30으로 맞춘다

`assistant.attachment.max-files` 와 설정을 생략한 `AttachmentProperties` 생성자의 `DEFAULT_MAX_FILES` 를 30으로 바꾼다.
`max-bytes`, `retention-days`, Spring multipart `max-file-size`, `max-request-size`, Tomcat `max-swallow-size` 는 바꾸지 않는다.

### 2. backend 첨부 시험에서 30장 경계를 서버와 서비스에 함께 고정한다

`AttachmentServiceTest` 에서 설정을 생략해 만든 `AttachmentProperties` 의 `maxFiles()` 가 30인지 단언한다.
같은 시험의 업로드와 `requireAttachable` 경계는 30개의 서로 다른 첨부를 수용하고 31번째 업로드 또는 31개 번호 요청을 `VALIDATION_FAILED` 로 거절하며, 거절 뒤 남은 행이 30개인지 확인한다.
`AttachmentUploadLimitTest` 는 실제 HTTP multipart 요청으로 30개를 차례로 `200`으로 올리고 31번째를 `400`과 `VALIDATION_FAILED` 로 확인해, 서비스 단위 시험만으로는 놓치는 요청 경로를 확인한다.

### 3. `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`에서 사진 30장의 Hermes 입력 목록을 확인한다

30개 첨부를 한 메시지에 보내고 대역 Hermes 가 받은 `HermesRunCommand.input()` 에 1번째부터 30번째까지의 순번, 저장 파일 이름, 올린 이름이 모두 한 줄씩 들어 있는지 단언한다.
저장한 사용자 본문은 안내 목록을 포함하지 않고 원문 그대로인지도 기존 한 장 시험과 같은 경계에서 확인한다.

### 4. `web/src/components/chat/composer.tsx`와 `test/browser/chat-attachment.spec.ts`에서 화면 상한과 안내를 30장으로 맞춘다

`MAX_ATTACHMENTS` 를 30으로 바꾸고, 기존 10장과 11장, 6장과 6장의 browser 시나리오를 각각 30장과 31장, 16장과 16장으로 바꾼다.
30개 미리보기가 모두 업로드를 끝내고 오류 안내가 없으며, 31번째 파일 또는 두 번째 선택의 초과 파일은 올리지 않고 `attachment-notice` 에 `30장까지` 안내를 보이는지 확인한다.
지운 뒤 한 장을 다시 올리는 시나리오는 30장을 채운 뒤 한 장을 지우고 30번째 자리를 다시 채워 서버 상한을 계속 차지하지 않는지 확인한다.

### 5. 문서 계약을 코드와 같은 값으로 유지한다

`docs/backend/attachment.md` 에 한 번 보낼 수 있는 사진 30장, 10장 33MB 실측을 비례로 계산한 약 100MB, 모든 사진이 한 장 상한일 때 최대 300MiB라는 저장 영향을 적는다.
`docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md` 의 표를 30장으로 고치고, 2026-10-05의 사용 근거와 한 장 10MB·요청 크기·30일 보관을 유지한다는 내용을 `더해진 부분`으로 남긴다.

## 검증

```bash
cd backend && ./gradlew test --tests com.bifos.assistant.chat.AttachmentServiceTest --tests com.bifos.assistant.chat.AttachmentUploadLimitTest --tests com.bifos.assistant.chat.ChatAttachmentTurnTest

cd web && pnpm test:browser chat-attachment
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/application.yml` | 수정 후 첨부 장수 설정을 30으로 변경 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java` | 수정 후 설정 생략 시 장수 기본값을 30으로 변경 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java` | 수정 후 기본값, 업로드, `requireAttachable`의 30개 수용과 31번째 거절을 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitTest.java` | 수정 후 실제 HTTP 업로드 30개 수용과 31번째 `400`을 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 후 Hermes 입력에 사진 30개 목록 전체가 전달되는지 검증 |
| `web/src/components/chat/composer.tsx` | 수정 후 화면 첨부 상한과 초과 안내를 30장 기준으로 변경 |
| `test/browser/chat-attachment.spec.ts` | 수정 후 30장 선택, 31번째 초과, 삭제 뒤 재선택을 browser에서 검증 |
| `docs/backend/attachment.md` | 수정 후 30장 상한과 저장 영향을 계약에 반영 |
| `docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md` | 수정 후 30장 변경의 날짜와 이유를 기존 ADR 갱신 형식으로 기록 |
