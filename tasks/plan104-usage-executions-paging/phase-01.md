# Phase 01. 실행 기록 커서 페이징

**Execution profile**: deep

## 목표

자기 루트 실행 기록을 최근 50개부터 마지막 쪽까지 더 보기로 읽는다.

**범위 외**: Hermes 연동, 운영 접근, 사용자 간 조회, 스키마 변경, 기존 배열 API 변경, 머지.

## 컨텍스트

`RootExecutionQuery.page(Long userId, int size)`는 기존 배열 목록의 조회다. `UsageController.myExecutions(int limit)`는 배열 응답을 유지한다. `UsageScreen`과 `ExecutionList`는 일반 화면과 관리자 화면이 공유한다. `ConversationCursor`의 URL-safe Base64 방식을 참고하되 실행용 커서는 usage 패키지에 둔다. 새 의존성은 없다.

**근거 문서**: `docs/backend/conversation.md`의 「실행 기록의 페이징」, `docs/frontend/structure.md`의 「사용량 화면의 탭」, ADR-063.

## 의도 메모

- 기존 배열 소비자가 많아 새 `/executions/page` 경로로 래퍼를 제공한다.
- 전체 건수는 세지 않고 limit+1로 다음 쪽을 판단한다.
- 순수 페이지 병합은 실제 요청 중복과 재시도에서 행 중복을 막는 용도로 쓴다.

## 작업 항목

### 1. backend 커서 목록

`AgentExecutionRepository`에 사용자와 루트만 고르는 keyset 쿼리를 추가한다. 정렬은 startedAt desc, id desc, 조건은 `(startedAt < cursor.startedAt) OR (startedAt = cursor.startedAt AND id < cursor.id)`다. 첫 쪽과 다음 쪽은 모두 limit+1로 읽는다. `ExecutionCursor`는 마지막으로 반환한 줄의 Instant와 양수 id를 encode/decode하며 잘못된 값은 VALIDATION_FAILED다. `RootExecutionPage`에 nextCursor를 추가하고 기존 `RootExecutionQuery.page` 시그니처와 동작을 유지하면서 cursor 인자 overload를 더한다. 새 페이지의 부가 정보 조회는 반환한 items에만 수행한다.

`UsageDtos.ExecutionPageView(List<ExecutionView> items, String nextCursor)`와 `UsageController.myExecutionPage(int limit, String cursor)`를 `/executions/page`에 추가한다. 기존 배열 API와 같은 변환 helper를 공유해 MEMBER 내부 값 가림, 에이전트/스킬/대화 일괄 조회를 유지한다. limit 기본 50이며 1~200 범위로 제한한다.

### 2. 화면과 호출

`web/src/lib/usage-paging.ts`에 UsageExecutionPage, 요청 함수, 순서를 유지하며 id 중복을 제거하는 페이지 병합 함수를 둔다. 타입 import만 execution-list를 참조한다. `web/eslint.config.mjs`의 NODE_TEST_READ_FILES에 새 lib 파일을 등록한다. 요청 함수는 `/api/usage/executions/page`를 쓰고 cursor를 URLSearchParams로 인코딩한다. `web/src/app/api/usage/executions/page/route.ts`는 cursor와 limit만 전달하고 기존 서버 인증을 이용한다.

`PagedExecutionList`는 initialPage와 isAdmin을 받고 기존 ExecutionList 위에 상태를 둔다. 더 보기, 불러오는 중, 실패 안내와 재시도를 구현한다. ref로 동시 클릭을 막고 finally에서 풀며 실패 때 rows와 cursor를 유지한다. 마지막 쪽이면 단추를 없앤다. initialPage가 바뀌면 새 페이지로 재설정하는 키나 동등한 처리를 한다. Notice와 Button을 재사용한다. `UsageScreen`은 새 페이지 API를 읽고 실행 기록 탭에 이 부품을 쓴다. 표/카드/트리는 변경하지 않는다.

### 3. 테스트

`ExecutionPagingTest`는 실제 저장소로 시작 시각 역순, 같은 시각 여러 줄의 id 역순, 커서 경계 배제, limit+1, 정확한 마지막 쪽과 빈 목록, 페이지 사이 최신 줄 삽입, 잘못된 커서, 타 사용자 커서로도 자기 자료만 반환, MEMBER 가림과 ADMIN 값 유지, 기존 배열 응답을 확인한다. 필요한 fixture는 클래스 안에 둔다.

`usage-executions-paging.test.ts`는 병합 순서, 겹친 id, 마지막 쪽 커서와 빈 목록을 확인한다. `usage-executions-paging.spec.ts`는 기존 test-support seed API와 독립된 사용자를 써서 50개를 넘는 기록을 심고 두 폭에서 더 보기로 끝까지 읽고 마지막 단추가 사라지는지 확인한다. 실패 응답 뒤 이전 기록 유지와 재시도, 빠른 두 번 클릭의 중복 방지, 일반 화면의 내부 값 비표시도 확인한다. SSR 초기 목록은 실제 서버를 읽고 브라우저 후속 조회 실패만 route로 대역한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ExecutionPagingTest' --tests '*RootExecutionQueryTest' --tests '*UsageControllerTest' --tests '*SkillUsageQueryTest'
node --test test/unit/usage-executions-paging.test.ts
cd web && pnpm typecheck
```

각 명령의 기대값은 종료 코드 0이다. 포맷은 기능 커밋 뒤 별도 커밋으로 처리하며 team-lead가 누적 품질 검사를 수행한다.

```bash
scripts/quality.sh check
``` 브라우저 spec은 team-lead가 공용 heavy-lock으로 묶어 실행한다.

```bash
pnpm --dir web test:browser usage-executions-paging.spec.ts --repeat-each=3 --retries=0
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/RootExecutionQuery.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/RootExecutionPage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionCursor.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionPagingTest.java` | 신규 |
| `web/src/lib/usage-paging.ts` | 신규 |
| `web/eslint.config.mjs` | 수정 |
| `web/src/app/api/usage/executions/page/route.ts` | 신규 |
| `web/src/components/usage/paged-execution-list.tsx` | 신규 |
| `web/src/components/usage/usage-screen.tsx` | 수정 |
| `test/unit/usage-executions-paging.test.ts` | 신규 |
| `test/browser/usage-executions-paging.spec.ts` | 신규 |
| `docs/backend/conversation.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
