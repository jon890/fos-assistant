# web

화면이다. Next.js 와 Tailwind 를 쓴다.

화면 문서는 이 모듈의 `docs/` 에 있는 `code-architecture.md` 하나와 루트 `docs/features/` 의 기능 파일이다.

| 문서 | 언제 보는지 |
| --- | --- |
| [`docs/features/`](../docs/features/) | 기능마다 화면이 누구에게 보이는 것과 그리지 않는 것, 화면에서 하는 일마다 Control Plane 과 주고받는 순서 |
| [`web/docs/code-architecture.md`](docs/code-architecture.md) | 화면 목록과 화면 틀, 부품을 두는 자리 |
| [`web/docs/adr/INDEX.md`](docs/adr/INDEX.md) | 화면에 관한 결정 |

## 변경 전에 고를 규칙

| 변경 대상 | 규칙 |
| --- | --- |
| 서버 라우트와 API 호출 | [rule-55](../docs/code-rules/rule-55.md) |
| 테마와 Markdown | [rule-56](../docs/code-rules/rule-56.md), [rule-57](../docs/code-rules/rule-57.md) |
| 화면 문구와 제품 용어 | [rule-58](../docs/code-rules/rule-58.md) |
| import | [rule-59](../docs/code-rules/rule-59.md) |
| 포맷과 파일 길이 및 기존 위반 | [rule-42](../docs/code-rules/rule-42.md), [rule-43](../docs/code-rules/rule-43.md), [rule-44](../docs/code-rules/rule-44.md), [rule-45](../docs/code-rules/rule-45.md) |
| 브라우저 테스트와 prefetch | [rule-60](../docs/code-rules/rule-60.md), [rule-61](../docs/code-rules/rule-61.md), [rule-62](../docs/code-rules/rule-62.md) |
| 화면 상태와 입력 수명 | [rule-69](../docs/code-rules/rule-69.md) |

실행 조건은 `web/eslint.config.mjs`와 연결한 검사가 갖고, 도구 버전은 `web/package.json`이 갖는다.
기준 파일을 고칠 때는 [rule-45](../docs/code-rules/rule-45.md)의 「화면 기준 파일 관리」를 읽는다.
파일 길이의 범위와 갱신 방법은 [`backend/docs/code-architecture.md`](../backend/docs/code-architecture.md)의 「파일 길이 기준 목록」이 갖는다.

## 검사

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
# 로컬에서는 고친 화면과 관련된 spec 만 돌린다. 전체는 PR 의 CI 가 돌린다
pnpm test:browser <spec 이름 일부>
```

**`pnpm build` 는 자리표시자 환경 변수가 있어야 통과한다.**
없으면 `Failed to collect page data` 로 끝나는데, 그것은 코드 결함이 아니다.
값은 `web/Dockerfile` 의 build 단계가 갖는다.

빌드와 실행 환경의 분리는 [rule-60](../docs/code-rules/rule-60.md)을 따른다.

화면을 고치며 같은 검사를 되풀이할 때는 개발 서버를 띄운다.

```bash
# cwd: web/
BROWSER_WEB_SERVER=dev pnpm test:browser
```

브라우저 테스트를 쓰거나 고칠 때는 [`test/browser/README.md`](../test/browser/README.md) 를 읽는다.
개발 서버와 빌드한 서버의 미리 읽기 차이, 테스트의 독립성과 대기, 반복 실행, 화면 갱신이 멎을 때 확인할 것을 그 파일이 갖는다.

목록과 사용량 탭의 prefetch는 [rule-61](../docs/code-rules/rule-61.md)을 따른다.

브라우저 검사의 포트를 고정하고 싶으면 `BROWSER_WEB_PORT` 와 `BROWSER_CONTROL_PLANE_PORT` 를 준다.

테스트 전용 우회 금지는 [rule-62](../docs/code-rules/rule-62.md)를 따른다.
