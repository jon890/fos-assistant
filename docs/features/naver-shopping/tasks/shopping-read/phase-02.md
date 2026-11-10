# Phase 02. naver-shopping 플러그인 틀과 확인 도구

**Execution profile**: standard

## 목표

연결하고 에이전트에 붙일 수 있는 `naver-shopping` 플러그인을 만든다. 확인 도구 `check_shopping_login` 이 「내 브라우저」 의 네이버 로그인 쿠키를 본다.

**범위 외**: 검색과 상세 읽기(phase 3), 선언 검사 강화(phase 4).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/naver-shopping/design.md` 의 「플러그인 선언」, 「저장소 등록」, 「오류」, `hermes/connectors/README.md`

- 본보기는 `hermes/connectors/naver-blog/`: `src/cdp.ts`, `src/session.ts`, `src/errors.ts`, `src/server.ts`, `scripts/build.ts`, `scripts/check-bundle.ts`, `scripts/test.ts`, `tests/fake-cdp.ts`, `.mcp.json`, `.claude-plugin/plugin.json`, `tsconfig.json`, `package.json`
- 의존성 버전은 블로그 `package.json` 과 같게 두고 `bun install` 로 lockfile 을 만든다
- 테스트 실행은 Gmail 처럼 `bun test` 하나다. 블로그의 Docker Chrome 실행기(`scripts/test.ts`)는 실제 Chrome 이 필요한 편집기 테스트 때문이라 옮기지 않는다
- 플러그인 폴더에 `node_modules/` 를 무시하는 `.gitignore` 를 둔다
- 계약 검사는 모든 플러그인의 `dist/<id>-mcp.js` 를 요구한다. 새 작업 공간에서는 플러그인마다 `bun install --frozen-lockfile && bun run build` 를 먼저 한다
- `.mcp.json` 서버의 env 는 선언(`owner_browser_env`)의 합과 같아야 한다. READ 도구는 서버에서 `readOnlyHint` 를 참으로 둔다

## 의도 메모

- CDP 코드는 블로그에서 import 하지 않고 이 플러그인의 오류 코드로 새로 둔다(design DECISION3)
- 확인 도구는 탭을 열지 않는다. 8초 안에 끝난다

## 작업 항목

### 1. 선언과 서버

`assistant.json`(design 의 표와 오류표, 도구마다 `attended_only: true`, 설명은 영어 요지 문장으로 시작), `.mcp.json`(`--no-env-file` 과 묶음 파일), `.claude-plugin/plugin.json`, `icon.svg`(첫 글자 SVG, 이름공간 선언), `ICON-SOURCE.md`, `src/server.ts`, `src/cdp.ts`, `src/session.ts`, `src/errors.ts`. 이 phase 의 서버는 세 도구를 모두 등록하되 검색과 상세는 phase 3 에서 채운다(그 전에는 `NAVER_SHOPPING_UNAVAILABLE`).

### 2. 저장소 등록

`hermes/connectors/Dockerfile` COPY 줄, `.gitignore`, `hermes/connectors/.dockerignore`, `.github/CODEOWNERS`, `hermes/README.md` 플러그인 표, 계약 검사의 `expected_args` 에 `naver-shopping` 의 `--no-env-file`.

### 3. 사용자 문서와 스킬

`hermes/connectors/naver-shopping/README.md`(「도구 목록」 표는 title 과 summary), `skills/naver-shopping/SKILL.md`(검색 한 번, 다음 페이지 없음, 사람이 해야 하는 화면 코드별 안내, 바로 살 수 없는 상품은 링크만, PR1 에는 구매 도구가 없음).

### 4. 테스트

`tests/naver-shopping.test.ts`: 쿠키 있음, 없음, 중계 비어 있음, 주소 모양 틀림. 계약 검사에 카탈로그의 `owner_browser_login_url` 이 네이버 로그인 주소인지 보는 단언을 더한다.

## 검증

```bash
cd hermes/connectors/naver-shopping && bun install --frozen-lockfile && bun run typecheck && bun run test && bun run check:bundle
cd hermes/connectors/naver-shopping && bun test tests/naver-shopping.test.ts
for c in hermes/connectors/*/; do (cd "$c" && bun install --frozen-lockfile && bun run build); done
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-shopping/` 아래 전부 | 신규 |
| `hermes/connectors/naver-shopping/tests/naver-shopping.test.ts` | 신규 |
| `hermes/connectors/Dockerfile` | 수정 |
| `hermes/connectors/.dockerignore` | 수정 |
| `.gitignore` | 수정 |
| `.github/CODEOWNERS` | 수정 |
| `hermes/README.md` | 수정 |
| `hermes/tests/test_connectors_contract.py` | 수정 |
