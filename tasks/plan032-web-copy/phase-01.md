# Phase 01. 사용자 화면의 문구를 고친다

**Execution profile**: standard

## 목표

모든 사용자가 보는 화면(대화, 사이드바, 기억, 사용량, 실행 상세, 로그인, 오류 안내, web API 경로의 응답 문구)의 한국어를 `review-table.md` 대로 고친다.
말투가 평서체, 합쇼체, 해요체로 섞여 있고 내부 용어가 화면에 드러나 있다.

**범위 외**: `ADMIN` 만 보는 관리 화면(`components/admin/**`, `app/admin/**`, 에이전트 관리 절)과 `components/agent/**` 는 phase 02 가 고친다. 코드 주석, docs, backend 문구는 고치지 않는다. 문구가 아닌 동작과 배치는 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/prd.md` 첫 절(그룹), `docs/code-architecture.md` 「web 화면 구조」

- 정본은 같은 폴더의 `review-table.md` 다. 맨 위 「사용자 결정」 이 그 아래 「사용자가 정할 것」 을 대신한다
- 규칙은 `web/AGENTS.md` 「화면 문구」 절이다. 표에 없는 문구를 새로 만나면 그 규칙으로 고친다
- 표의 `파일:줄` 은 `c4f4335` 기준이다. 그 뒤에 파일이 바뀌었으면 지금 문구로 찾는다. 찾지 못한 행은 보고서에 적는다
- 이 phase 가 다루는 표의 절: `app/api/**`, `app/layout.tsx`, `app/signin`, `app/usage`, `app/agents/page.tsx`(목록의 빈 상태), `components/chat/**`, `components/chat-panel.tsx`, `components/error-message.ts`, `components/execution/**`, `components/memory/**`, `components/shell/**`, `components/ui/**`, `components/usage/**`, `lib/stream.ts`, 그리고 「뜻이 틀리거나 오해를 부르는 안내」 가운데 이 파일들에 있는 것

## 의도 메모

- 「무엇을 도와줄까요」 는 두 곳(`components/chat/start-screen.tsx`, `components/chat/composer.tsx`)에서 「무엇을 도와드릴까요」 가 된다
- 오류 안내는 할 일만 알린다(사용자 결정). 원인은 로그와 관리 화면에 남는다
- 브라우저 검사와 단위 검사가 옛 문구를 비교하면 새 문구로 고친다. 검사의 뜻은 바꾸지 않는다

## 작업 항목

### 1. 표대로 문구를 고친다

위 절의 「바꿀 것」 행과 이 파일들에 해당하는 결정을 모두 반영한다. 「그대로 둘 것」 은 건드리지 않는다

### 2. 검사가 비교하는 문구를 따라 고친다

`test/browser/**`, `test/unit/**`, `test/e2e/**` 에서 이 phase 가 바꾼 문구를 비교하는 곳을 새 문구로 고친다

### 3. 이 phase 를 검증하는 단위 검사 `test/unit/ui-copy.test.ts`

`web/src` 의 `.ts`, `.tsx` 에서 주석을 뺀 문자열과 JSX 텍스트를 줄 단위로 읽어, 한국어 문구가 평서체로 끝나는 곳(`했다.`, `없다.`, `한다.`, `된다.`, `않았다.` 처럼 `다` 나 `다.` 로 끝나는 한글 문장)을 찾는다. 이 phase 가 맡은 경로에서 0 건이어야 한다. 관리 화면 경로는 phase 02 가 끝날 때 목록에서 뺀다(허용 목록을 두고 phase 02 가 비운다).
- 정상: 해요체 문구만 있는 파일은 통과한다
- 실패 쪽: 시험용 문자열 「아직 대화가 없다.」 를 넣으면 실패한다(검사 안에서 함수 단위로 확인한다)

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
python3 /Users/nhn/personal/fos-skills/korean-check/scripts/korean-style-check.py web/AGENTS.md
```

- 모두 통과한다
- 보고서에 표의 행 가운데 반영한 수, 찾지 못한 행, 표에 없어 규칙으로 고친 문구를 적는다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/**/*.ts` | 수정 |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/app/signin/page.tsx` | 수정 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/components/chat/**/*.tsx` | 수정 |
| `web/src/components/chat/**/*.ts` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/execution/*.tsx` | 수정 |
| `web/src/components/memory/*.tsx` | 수정 |
| `web/src/components/shell/*.tsx` | 수정 |
| `web/src/components/ui/*.tsx` | 수정 |
| `web/src/components/usage/*.tsx` | 수정 |
| `web/src/lib/stream.ts` | 수정 |
| `test/browser/*.spec.ts` | 수정 |
| `test/unit/ui-copy.test.ts` | 신규 |
