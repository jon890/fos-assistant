# Phase 04. 「서버 비밀값」 화면과 저장소 규칙 문서

**Execution profile**: standard

## 목표

관리자 영역에 「서버 비밀값」 화면을 더해 중계 서명 비밀의 상태를 보고 새로 만들고 지운다. 비밀값을 두는 곳의 저장소 규칙과 문서를 이번 결정에 맞춘다.

**범위 외**: VAPID, profile key, provider key 줄(뒤 PR).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4

**근거 문서**: `docs/features/secret-store/spec.md`, `docs/features/secret-store/design.md`, `docs/adr/ADR-20261120-server-secret.md`

- 관리자 화면 본보기: `web/src/app/admin/browsers/page.tsx`(서버 컴포넌트가 로그인과 `redirectMemberHome` 확인), `web/src/components/browser/admin-browser-list.tsx`(목록 읽기, `describeAdminError`, `Notice`, `EmptyState`), 서버 라우트 `web/src/app/api/admin/browsers/route.ts`(`callControlPlane`, `errorResponse`), API 모듈 `web/src/lib/browser-api.ts`.
- 메뉴는 `web/src/components/shell/admin-shell.tsx` 의 링크 목록이다. 「사용자와 자원」 묶음(`id: "resources"`)의 「파일 공간」 아래에 「서버 비밀값」(`/admin/secrets`)을 둔다.
- 오류 문구는 `web/src/components/error-message.ts` 의 `describeAdminError` 에 `SECRET_STORE_UNAVAILABLE` → 「KEK 가 없어 저장할 수 없습니다」 를 더한다.
- 화면 문구와 용어는 `docs/code-rules/rule-58.md`, 상태와 입력 수명은 `docs/code-rules/rule-69.md` 를 따른다.
- 브라우저 테스트 본보기: `web/test/browser/user-browser.spec.ts` 의 관리자 목록 부분(`page.route` 로 응답을 고정).

## 의도 메모

- 이 PR 의 화면에는 원문 입력 칸이 없다(중계 서명 비밀은 서버가 만든다). 입력 칸 비우기(REQ3)는 PR3 의 provider key 에서 다룬다.
- 다시 만들기 확인 창은 스펙 REQ18 대로 이미 값이 있을 때만 띄운다. 값이 비어 있으면 확인 없이 만든다. 바인딩 수를 읽지 못했으면(`bindingCount` null) 확인 창 문구의 수 자리를 「붙은」 으로 바꾼다.
- 다시 만들기 결과의 `catalogUnavailable` 이 참이면 「비밀은 바뀌었지만 플러그인 목록을 읽지 못해 바인딩을 다시 설치하지 못했어요. Hermes 가 돌아온 뒤 다시 만들어 주세요」 를 보인다.
- 다시 만든 뒤 결과 `reinstall` 을 「N개를 다시 설치했어요. 반영 완료 전까지 브라우저 플러그인은 동작하지 않습니다」 로 보이고, 실패가 있으면 「M개는 다시 설치하지 못했어요. 바인딩 화면에서 확인해 주세요」 를 더한다.

## 작업 항목

### 1. 화면

- `web/src/app/admin/secrets/page.tsx`: 브라우저 관리 페이지와 같은 서버 확인 뒤 `AdminSecretList` 를 그린다.
- `web/src/components/admin/admin-secret-list.tsx`: 「브라우저 중계 서명 비밀」 줄. 설명, 「입력됨」 이나 「비어 있음」 배지, 앞부분, 마지막으로 바꾼 사람과 시각(`formatWhen`), 「새로 만들기」 와 「지우기」 단추. `storeAvailable` 이 거짓이면 단추를 끄고 「KEK 가 없어 저장할 수 없습니다」 를 보인다. 중계 주소가 설정되지 않았으면 「중계 주소가 설정되지 않아 값이 있어도 중계는 꺼져 있어요」 를 보인다.
- `web/src/components/admin/secret-confirm-dialog.tsx`: 다시 만들기와 지우기 확인 창.
- `web/src/components/shell/admin-shell.tsx`: 메뉴 한 줄(아이콘은 lucide `KeyRound`).
- `web/src/components/error-message.ts`: 위 오류 문구.

### 2. API

- `web/src/lib/server-secret-api.ts`: 응답 타입과 `fetchServerSecrets`, `regenerateGatewaySecret`, `deleteGatewaySecret`.
- `web/src/app/api/admin/secrets/route.ts`(GET), `web/src/app/api/admin/secrets/browser-gateway/route.ts`(DELETE), `web/src/app/api/admin/secrets/browser-gateway/regenerate/route.ts`(POST).

### 3. 문서

- `AGENTS.md` 「지켜야 할 것」: 「비밀값을 데이터베이스에 넣지 않는다. profile key 는 홈서버 파일에 둔다.」 를 「비밀값을 데이터베이스에 원문으로 넣지 않는다. 서버 비밀값은 KEK 로 감싸 `server_secret` 에 두고, KEK 는 홈서버 파일에 둔다(ADR-20261120 / server-secret, 링크는 `docs/adr/ADR-20261120-server-secret.md`).」 로 바꾼다. profile key 는 PR2 전까지 파일이라 「profile key 는 PR2 까지 홈서버 파일이다」 처럼 시점이 드러나는 말 대신 「profile key 의 자리는 `docs/code-architecture.md` 「비밀값을 두는 곳」 이 갖는다」 로 가리킨다.
- `docs/code-architecture.md` 「비밀값을 두는 곳」: 중계 서명 비밀 줄을 더하고(서버 비밀값, `server_secret`), 「데이터베이스에는 어떤 비밀값도 원문으로 넣지 않는다」 아래에 서버 비밀값의 자리와 ADR 링크를 적는다. 아직 옮기지 않은 값(profile key, AI credential, VAPID)은 지금 자리 그대로 둔다.
- `docs/features/secret-store.md`: 「아직 구현하지 않았다」 를 지금 들어온 범위(저장소, 화면, 중계 서명 비밀)와 남은 PR 로 고치고 `covers:` 줄을 더한다.
- `web/docs/code-architecture.md` 의 관리자 화면 목록과 `docs/features/users.md` 「관리자 영역」 화면 목록에 「서버 비밀값」 을 더한다(그 목록이 화면을 하나씩 적는 경우만).

### 4. 이 phase 를 검증하는 테스트

- `web/test/browser/admin-secrets.spec.ts` 신규: 응답을 고정해 「입력됨」 과 앞부분, 이름이 보임. 바인딩 2개일 때 「새로 만들기」 가 스펙 문구의 확인 창을 띄우고 확인 뒤 결과 문구가 보임. `storeAvailable: false` 면 단추가 꺼지고 KEK 문구가 보임. `MEMBER` 세션은 `/admin/secrets` 에서 집으로 돌아감. 목록 `GET` 이 실패하면 오류 안내가 보임. `catalogUnavailable` 결과 문구가 보임.
- `web/test/browser/admin-area.spec.ts` 수정: 메뉴 링크 목록 대조에 「서버 비밀값」 을 더한다.
- `web/test/unit/server-secret-api.test.ts` 신규: 응답 타입 해석과 결과 문구 조립.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && node --test test/unit/server-secret-api.test.ts
cd web && pnpm test:browser test/browser/admin-secrets.spec.ts test/browser/admin-area.spec.ts
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts test/repo/adr-index.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/admin/secrets/page.tsx` | 신규 |
| `web/src/components/admin/admin-secret-list.tsx` | 신규 |
| `web/src/components/admin/secret-confirm-dialog.tsx` | 신규 |
| `web/src/components/shell/admin-shell.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/lib/server-secret-api.ts` | 신규 |
| `web/src/app/api/admin/secrets/route.ts` | 신규 |
| `web/src/app/api/admin/secrets/browser-gateway/route.ts` | 신규 |
| `web/src/app/api/admin/secrets/browser-gateway/regenerate/route.ts` | 신규 |
| `web/test/browser/admin-secrets.spec.ts` | 신규 |
| `web/test/unit/server-secret-api.test.ts` | 신규 |
| `web/test/browser/admin-area.spec.ts` | 수정 |
| `AGENTS.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/features/secret-store.md` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `docs/features/users.md` | 수정 |
