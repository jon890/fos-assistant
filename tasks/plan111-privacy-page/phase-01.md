# Phase 01. 로그인 없이 열리는 안내 화면 /privacy 를 만든다

**Execution profile**: standard

## 목표

초대받은 사람이 가입하기 전에 읽을 수 있는 안내 화면 하나를 만든다. 주소는 `/privacy` 다.
서버 운영자가 볼 수 있는 것과 볼 수 없는 것, 지우면 무엇이 사라지고 무엇이 남는지를 사실대로 적는다.
로그인 화면에서 이 화면으로 가는 링크를 둔다.

**범위 외**: 문구를 사용자마다 바꾸는 것, 동의를 받는 것, 사이드바의 주 메뉴(`web/src/components/shell/main-nav.tsx`)에 링크를 더하는 것. 주 메뉴는 화면 목록이라 안내 링크를 넣으면 `test/browser/nav.spec.ts` 의 메뉴 단언과 화면 정체성 규칙을 함께 바꿔야 한다. 문구의 원본은 `docs/privacy.md` 이고 이 화면은 그 「대화가 서버에 어떻게 남는가」 와 「연결을 끊고 지우는 법」 을 짧게 옮긴다.

## 컨텍스트

**근거 문서**: `docs/privacy.md`, `backend/docs/adr/ADR-20261008-data-encryption.md` 의 「위협 모델」, `backend/docs/adr/ADR-20261008-conversation-purge.md`, `web/docs/code-architecture.md` 의 화면 목록

- 페이지는 `web/src/app/**/page.tsx` 이다. `signin` 말고는 모든 페이지가 `auth()` 로 로그인을 확인하고 없으면 redirect 한다(예: `web/src/app/page.tsx`). middleware 는 없다. 그래서 새 페이지에서 `auth()` 를 부르지 않으면 로그인 없이 열린다
- 로그인 화면은 `web/src/app/signin/page.tsx` 다. `Card` 와 `Button` 부품(`web/src/components/ui/`)을 쓰고, 앱 이름은 `appName()`(`web/src/lib/app-name.ts`)으로 실행할 때 읽는다
- 브라우저 시험은 저장소 root 의 `test/browser/*.spec.ts` 이고 `pnpm --dir web test:browser <spec>` 로 돈다. 로그인 없는 화면은 `test/browser/access-revoked.spec.ts` 의 `/signin` 확인을 본보기로 한다
- 화면 문구는 존댓말 안내문이다. 기존 화면 문구의 말투를 따른다

## 의도 메모

- 문구에 넣을 사실은 아래 표가 전부다. 화면이 문서보다 앞서 약속하지 않는다. 문서가 바뀌면 화면도 같은 PR 에서 바꾼다

  | 항목 | 문구에 담을 사실 |
  | --- | --- |
  | 운영자가 볼 수 있는 것 | 서버 관리자는 마음먹으면 대화를 읽을 수 있다. 마스터 key 가 같은 서버에 있고 서버 프로그램이 본문을 풀기 때문이다. 대화 제목과 일부 기록은 아직 암호화하지 않는다 |
  | 막는 것 | 데이터베이스 파일이나 백업만 가진 사람, 데이터베이스만 조회하는 사람은 메시지 본문을 읽지 못한다 |
  | AI 공급자 | 대화는 서버 관리자가 정한 AI 모델 공급자에게 요청의 일부로 전달된다 |
  | 지우기 | 대화를 지우면 몇 분 안에 메시지와 사진, 결과물, AI 런타임의 대화 기록을 지운다. 사용량 기록(토큰 수와 금액)은 본문 없이 남고, 이미 만든 백업에는 남는다 |
  | 연결 | 연결을 해제하면 그 연결의 비밀값을 지운다. 외부 서비스의 권한은 그 서비스에서도 끊을 수 있다 |

- 화면은 정적 글이다. API 를 부르지 않는다. 그래서 Control Plane 이 꺼져 있어도 열린다

## 작업 항목

### 1. `web/src/app/privacy/page.tsx`

로그인을 확인하지 않는 서버 컴포넌트다. 제목은 「`<앱 이름>` 이 내 데이터를 다루는 방법」 이고 위 표의 다섯 항목을 소제목과 목록으로 그린다. 앱 이름은 `appName()` 으로 읽고 `await connection()` 으로 요청마다 그린다(`signin/page.tsx` 와 같다).

### 2. 링크

`web/src/app/signin/page.tsx` 의 로그인 단추 아래에 「데이터를 어떻게 다루나요」 링크(`/privacy`)를 둔다.

### 3. 시험 `test/browser/privacy-page.spec.ts`(신규)

- 로그인하지 않은 브라우저로 `/privacy` 를 열면 `/signin` 으로 가지 않고 제목과 다섯 소제목이 보인다
- 로그인 화면에 `/privacy` 로 가는 링크가 있다
- 좁은 폭(390px)에서 가로로 넘치지 않는다

### 4. 문서

`web/docs/code-architecture.md` 의 화면 목록에 `/privacy` 를 더하고 로그인 없이 열린다고 적는다.

## 검증

```bash
pnpm --dir web typecheck
pnpm --dir web lint
pnpm --dir web test:browser privacy-page.spec.ts
scripts/check-local.sh privacy-page
```

넷 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/privacy/page.tsx` | 신규 |
| `web/src/app/signin/page.tsx` | 수정 |
| `test/browser/privacy-page.spec.ts` | 신규 |
| `web/docs/code-architecture.md` | 수정 |
