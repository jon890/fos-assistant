# Phase 04. 평가 사용자 관리자 API 와 문서

**Execution profile**: standard

## 목표

관리자가 평가 사용자를 준비하고 상태를 읽는 API 를 열고, 관리자 사용자 목록에 「평가용」 표시를 보내며, 평가 사용자가 다른 사용자의 에이전트 고르기에 섞이지 않는 것을 확인한다. 기능 문서를 지금 상태로 맞춘다.

**범위 외**: 확인 시작·조회 API, 실행기(뒤 실행기 PR), 품질 화면(뒤 화면 PR).

## 컨텍스트

**요구사항**: REQ2

**근거 문서**: `docs/features/agent-quality/design.md` 의 「관리자 API」, 「숨기는 곳」, `backend/docs/code-architecture.md` 의 「패키지 배치 규칙」

- `admin` 은 `application`, `application.dto`, `presentation` 만 갖고 기능의 `application`, `application.dto`, `domain.type` 만 부른다. `CurrentUserProvider.requireAdmin` 은 `admin` 안에서만 부른다. 선례: `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminController.java`, `PeopleAdminDtos.java`.
- 응답은 phase 2 의 `quality/application/dto/EvaluationUserStatus` 를 옮긴다. 메일 주소는 고정값이라 담아도 되지만 profile 이름은 요청한 관리자만 본다(응답에 담는다).
- 평가 사용자의 두 에이전트는 `PRIVATE` 이라 다른 사용자의 `AgentService.readableBy` 에 나오지 않는다. 고르기·멘션·위임 대상(`chat/turn/application/DelegationTargetsInstructions` 가 `readableBy` 를 쓴다) 모두 이 조회를 쓰므로 이 조회 하나로 확인한다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/admin/quality/presentation/QualityAdminController.java`, `QualityAdminDtos.java`

| 요청 | 동작 |
| --- | --- |
| `POST /api/v1/admin/quality/evaluation-user` | 본문 `{hermesProfile}`(필수, 기존 사람 더하기의 profile 이름 검사와 같은 규칙). `EvaluationUserService.prepare` 뒤 상태 |
| `GET /api/v1/admin/quality/evaluation-user` | `EvaluationUserService.status` |

### 2. 관리자 사용자 목록의 「평가용」

phase 2 에서 `PersonAccess` 에 더한 칸을 `PeopleAdminDtos.PersonView` 의 `evaluation` 으로 보낸다. `PeopleAdminController` 의 목록 응답이 그 값을 넘긴다.

### 3. 문서

- `docs/features/agent-quality.md`: `covers:` 에 `backend/src/main/java/com/bifos/assistant/quality/`, `backend/src/main/java/com/bifos/assistant/admin/quality/` 를 적고, 「지금은 스펙만 있다」 를 「평가 사용자와 판정 규칙까지 들어갔고 실행기와 화면은 뒤 PR」 로 고친다. 평가 사용자 준비 API 와 운영 저장소가 먼저 할 일(설계 「운영 저장소에 넘길 요청」 5) 링크.
- `docs/features/users.md`: 「요구」 에 평가 전용 사용자는 로그인하지 않고 관리자 사용자 목록에 「평가용」 으로 보인다는 한 줄과 기능 파일 링크.

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/admin/quality/QualityAdminControllerTest.java`(`BackendIntegrationTest`, Hermes 대시보드 대역): `MEMBER` 는 403, 관리자 준비는 200 과 준비 상태, 두 번 불러도 같은 사용자, `hermesProfile` 이 비면 400.
- 같은 테스트에서 관리자 사용자 목록 응답의 평가 사용자 줄 `evaluation` 이 참, 다른 줄은 거짓.
- 같은 테스트에서 다른 `MEMBER` 사용자의 `AgentService.readableBy` 결과(에이전트 목록 API `GET /api/v1/agents` 응답)에 평가 사용자의 두 에이전트가 없다.

## 검증

```bash
cd backend && ./gradlew test --tests '*QualityAdminControllerTest' --tests '*EvaluationUser*'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/admin/quality/presentation/QualityAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/quality/presentation/QualityAdminDtos.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/quality/QualityAdminControllerTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminController.java` | 수정 |
| `docs/features/agent-quality.md` | 수정 |
| `docs/features/users.md` | 수정 |
