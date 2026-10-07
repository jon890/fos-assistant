# Phase 01. 표와 엔티티, 상태 전이 규칙

**Execution profile**: standard

## 목표

`user_browser` 표와 엔티티, 저장소, 상태 전이 규칙을 만든다. 뒤 phase 의 서비스가 이 규칙만으로 전이를 판정하게 한다.

**범위 외**: proxy 호출, 서비스, API, 스케줄러, 화면. `user_browser_grant` 표는 단계 3 이 만든다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md`

- 계약: `docs/backend/user-browser.md` 의 「표」 와 「상태 전이」. 결정: `docs/adr/ADR-20261007-user-browser.md`
- 새 최상위 패키지 `browser` 를 만든다(`com.bifos.assistant.browser`). 안은 `application`, `domain`, `domain.type`, `infra`, `presentation` 이다(`backend/AGENTS.md` 「패키지 배치」).
  본보기는 `followup` 패키지다(`FollowUp`, `FollowUpRepository`, `followup.domain.type`)
- `docs/backend/packages.md` 의 「패키지와 책임」 과 「최상위 패키지의 층 순서」 에 `browser` 를 더한다. 자리는 `user` 와 `shared` 만 import 하는 아래쪽이며, 단계 3 에서 `connector` 가 `browser` 를 부르므로 `connector` 보다 아래다.
  층 순서 검사(`ArchitectureRules`, `TopLevelPackageOrder`)가 표를 코드로 갖고 있으면 같이 고친다
- 마이그레이션은 `backend/src/main/resources/db/migration/V<UTC YYYYMMDDHHMMSS>__user_browser.sql`. 작성 규칙은 `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」 이다. MySQL 과 H2 에 함께 있는 문법만 쓴다
- 표 문서는 `docs/backend/schema/` 의 관례대로 더한다(그 디렉터리의 README 가 어느 파일에 둘지 정한다)

## 의도 메모

- 쿠키와 열린 주소는 표에 넣지 않는다(이슈 #245 의 목표)
- `profile_key` 는 `Sha256` 유틸(`shared/util/Sha256.java`)로 `"u" + userId` 를 해시한 64자리 소문자 16진수다. 첨부 디렉터리 키(`hermes/SandboxAttachmentDirectory.java`)와 같은 계산이지만 루트가 다르다
- 낙관적 잠금은 `@Version`

## 작업 항목

### 1. 마이그레이션과 엔티티

- `user_browser`: `docs/backend/user-browser.md` 의 칸 그대로. `user_id` 유일, `app_user(id)` 외래 키, 인덱스 `(status, last_active_at)`
- `browser.domain.type.UserBrowserStatus`: `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED`
- `browser.domain.UserBrowser`: 생성 정적 메서드 `create(Long userId, String profileKey, Instant now)`(상태 `STOPPED`).
  전이 메서드 `beginStart(now)`, `markRunning(containerId, now)`, `markFailed(code, now)`, `beginStop(now)`, `markStopped(now)`, `touch(now)`.
  허용되지 않는 전이는 `ApiException(ErrorCode.BROWSER_BUSY)` 를 던진다. 허용 표는 계약 문서의 「상태 전이」 표다
- `touch` 는 마지막 기록에서 1분이 지나지 않았으면 쓰지 않는다
- `shared/error/ErrorCode` 에 `BROWSER_NOT_FOUND`(404), `BROWSER_DISABLED`(503), `BROWSER_CAPACITY`(409), `BROWSER_BUSY`(409), `BROWSER_START_FAILED`(502), `BROWSER_EXISTS`(409) 를 더한다
- `browser.infra.UserBrowserRepository`: `findByUserId`, `countByStatusIn`, `findByStatusAndLastActiveAtBefore`, `findByStatusIn`

### 2. 테스트

- `backend/src/test/java/com/bifos/assistant/browser/domain/UserBrowserTest.java`: 허용 전이 각각과 거절 전이(`RUNNING` 에서 `beginStart`, `STOPPED` 에서 `beginStop`), `touch` 의 1분 제한
- 마이그레이션과 엔티티의 일치는 `MysqlMigrationTest` 가 본다(Docker 필요). 저장소 메서드는 `RepositoryQueryMysqlTest` 가 스스로 찾는다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
node scripts/check-migration-versions.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__user_browser.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/UserBrowser.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/type/UserBrowserStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/UserBrowserRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/domain/UserBrowserTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/**` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/schema/**` | 수정 |
