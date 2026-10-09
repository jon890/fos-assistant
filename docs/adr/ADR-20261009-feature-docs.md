## ADR-20261009 / feature-docs: 문서는 기능마다 한 파일로 두고 모듈 docs 에는 모듈 안의 규칙만 남긴다

- **status**: `accepted`
- Date: 2026-10-09
- [ADR-20261009 / docs-per-module](ADR-20261009-docs-per-module.md) 의 「결정」 표 중 모듈의 `prd.md` 와 `flow.md` 를 두는 부분과, 「대안 기각」 의 「`flow/` 처럼 디렉터리 아래에 주제별 파일을 둔다」 를 대체한다.

### 결정

1. 루트 `docs/features/<기능>.md` 를 기능마다 하나 둔다.
   그 파일은 기능의 요구와 끝에서 끝까지의 흐름을 함께 갖는다.
   요구는 무엇을 하고 하지 않는지, 무엇을 관측하면 충족인지다.
   흐름은 화면에서 backend 를 거쳐 Hermes 까지 가는 순서와, 분기, 실패, 빈 상태, 동시 요청이다.
   결정은 다시 쓰지 않고 ADR 을 링크한다.
2. 모듈 `docs/` 에는 그 모듈 안에서만 의미 있는 규칙만 둔다.
   `code-architecture.md`, backend 의 `data-schema.md`, hermes 의 `hermes-contract.md` 다.
   모듈의 `flow.md` 와 `prd.md` 는 두지 않는다.
   루트 `docs/prd.md` 는 제품 전체의 목적, 범위, 범위 밖, 아직 만들지 않은 것만 갖는다.
3. 코드에서 만들 수 있는 것은 쓰지 않고 어디서 보는지(파일, 명령)만 적는다.
   API 목록, 표와 칸 목록, 커넥터 도구 목록, 설정 값 목록이 여기 든다.
4. 기능 파일 머리에 다루는 코드 경로를 `covers:` 로 적는다.
   PR 이 그 경로를 바꾸고 기능 파일을 건드리지 않으면 CI 가 경고만 낸다.
   검사는 `scripts/check-feature-covers.mjs` 가 하고 CI 의 unit job 에서 PR 에만 돈다.
5. 기능 파일은 500줄을 넘으면 알림을 낸다. 실패로 막지 않는다.

`docs/features/` 는 루트에만 둔다. 모듈 아래 `features/` 는 만들지 않는다. 기능은 여러 모듈을 가로지르기 때문이다.
기능 파일 이름은 소문자 영문과 숫자, 하이픈이다(`agent-skill.md`). `features/` 아래에 하위 디렉터리를 두지 않는다.

다섯 검사가 이 자리를 지킨다.

- `test/unit/doc-files.test.ts`: `docs/features/` 아래에는 위 이름 규칙에 맞는 `.md` 만 통과한다.
- `test/unit/doc-code-references.test.ts`: 기능 파일도 백틱으로 적은 코드 이름이 코드에 있는지 본다.
- `scripts/check-feature-covers.mjs`: covers 경로를 바꾸고 기능 파일을 고치지 않은 PR 에 경고만 낸다.
- `test/unit/feature-covers.test.ts`: 모든 기능 파일에 covers 가 있고 각 경로가 추적 파일과 맞는지 본다.
- `scripts/check-file-length.mjs`: 기능 파일이 500줄을 넘으면 `알림:` 을 낸다.

### 맥락

`docs-per-module` 뒤에도 `backend/docs/flow.md` 가 약 6,100줄이었다.
같은 기능의 흐름이 backend flow, web flow, web prd, 루트 flow 네 곳에 나뉘어 있었다.
그래서 한 기능을 고치는 사람이 네 파일을 읽어야 했고, 같은 흐름이 여러 번 적혔다.

### 대안 기각

- 모듈마다 한 파일을 유지한다: 위 맥락 그대로다.
- `backend/docs/flow/` 처럼 모듈 안에서만 나눈다: 화면과 Hermes 쪽 흐름이 여전히 다른 파일에 남는다.

`docs-per-module` 이 `flow/` 아래 주제별 파일을 기각하며 걱정한 것은 파일 수 증가였다.
기능 단위(15개 안팎)로 묶고, 새 주제는 새 파일 대신 기존 기능 파일의 절로 더해 막는다.

### 감당할 것

코드 주석과 문서가 기능 파일 이름까지 적어야 한다.
기능을 다른 파일로 옮기면 참조를 함께 고친다. `doc-references` 테스트가 놓친 참조를 잡는다.
기능 경계가 애매한 절은 주로 바뀌는 코드가 있는 기능에 둔다.
코드 값의 복사본은 앞 PR 이 줄였고, 남은 것은 그 기능을 고치는 PR 이 이어 줄인다.
