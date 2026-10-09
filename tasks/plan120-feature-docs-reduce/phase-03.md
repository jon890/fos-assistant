# Phase 03. 커넥터 연결과 커넥터 정책 문서를 줄인다

**Execution profile**: deep

## 목표

기능 파일 `docs/features/connector.md` `docs/features/connector-policy.md` 을 「기능 파일의 모양」 으로 다시 짜고, 코드를 말로 옮긴 부분과 중복과 낡은 내용을 지운다.
목표 줄 수: 두 파일 합계 750줄 이하(지금 약 1,300줄). 파일 하나는 500줄 이하다.

**범위 외**: 다른 기능 파일과 `backend/docs/data-schema.md`(다른 phase). 코드와 테스트, ADR 본문.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-feature-docs.md`, `docs/adr/ADR-20261009-docs-per-module.md` 의 「코드와 설정이 이미 가진 값을 문서에 옮겨 적지 않는다」

앞 PR 이 `backend/docs/flow.md`, `web/docs/flow.md`, `web/docs/prd.md`, `docs/flow.md` 의 절을 문장 그대로 `docs/features/` 로 옮겼다.
그래서 한 기능 파일 안에 같은 흐름이 원재료마다 한 번씩(화면 요구, 전체 흐름, 화면 흐름, backend 흐름) 적혀 있고, 코드 값의 복사본도 그대로 남아 있다.
기능 파일 안에서 원재료 차례는 루트 prd 절, 옛 web prd 절, 옛 루트 flow 절, 옛 web flow 절, 옛 backend flow 절이다. 헤딩 끝의 `(화면)`, `(화면 흐름)`, `(전체 흐름)` 은 겹친 헤딩을 나누려고 붙인 표시다.

사용자 원칙(2026-10-09): 로직은 코드가 말한다. 문서에는 **코드만 봐서는 알 수 없는 것**만 남긴다.
의사결정과 그 까닭, 흐름이 갈리는 지점(실패, 빈 상태, 동시 요청, 재시작), 외부 계약(Hermes, MCP, plugin, 웹과 backend 사이의 약속)이다. 코드를 말로 옮긴 부분은 지운다.

### 기능 파일의 모양

```markdown
# <제목>

<이 기능이 무엇인지 한 문장>

## 요구
무엇을 하고 무엇을 하지 않는가, 무엇을 관측하면 충족인가. 화면이 누구에게 무엇을 보이는가.

## 흐름
화면 → backend → Hermes 의 정상 흐름 하나를 mermaid sequenceDiagram 하나로 그린다. 참여자는 화면, Control Plane, Hermes(필요하면 대시보드 plugin, 외부 서비스)다.

## <기존 기능 절들>
갈리는 지점(실패, 빈 상태, 동시 요청, 재시작)과 외부 계약. 결정은 다시 쓰지 않고 ADR 을 링크한다.
```

- `## 요구` 와 `## 흐름` 은 파일 머리에 새로 둔다. 같은 이름의 헤딩이 이미 있으면 그 절을 그 자리로 옮겨 쓴다.
- 원재료마다 따로 적힌 같은 흐름은 `## 흐름` 의 그림 하나와 갈리는 지점 절 하나로 합친다. 합치며 빠뜨리는 분기가 없게 한다.
- 원재료에 이미 mermaid 가 있으면 그것을 다듬어 쓴다. 새 그림은 코드의 호출 순서와 대조한다.

### 지우는 것

| 종류 | 예 |
| --- | --- |
| 칸과 목록 복사본 | 요청과 응답 칸 목록, API 경로 표, 설정 키 표, enum 값 목록, 오류 코드 목록, 커넥터 도구 목록, 색인 목록. 「어디서 보는지」(파일, 클래스, 명령) 한 줄로 바꾼다 |
| 상수 값 | 시간, 개수, 한도 값. 값이 왜 그만한지가 결정이면 까닭만 남기고 수치는 코드 이름으로 가리킨다 |
| 코드 순서 서술 | 「A 가 B 를 부르고 C 를 저장한다」 처럼 메서드 순서를 말로 옮긴 문장, 「어느 클래스가 무엇을 하나」 표 |
| 중복 | 같은 흐름이나 규칙이 한 파일 안에서 원재료마다 되풀이된 것, 다른 기능 파일이나 ADR 본문, `hermes/docs/hermes-contract.md` 에 있는 것. 한 곳만 남기고 링크한다 |
| 낡은 내용 | 코드와 어긋난 문장. 남길 가치가 있는 결정이면 지금 코드에 맞게 한 문장으로 다시 쓰고, 아니면 지운다 |
| 끝난 일의 이력 | 「옛 …에서 옮겼다」, 「다음 단계」 중 이미 된 것 |

### 남기는 것

- 까닭이 붙은 결정(「…때문이다」, 「…하지 않으면 …된다」). 결정 본문이 ADR 에 있으면 ADR 링크만 둔다.
- 실패, 빈 상태, 동시 요청, 재시작에서 갈리는 지점. 분기 표는 남기되 코드 복사 칸은 뺀다.
- Hermes, MCP 도구, plugin, 웹이 기대는 외부 계약. 칸 이름이라도 상대가 기대는 약속이면 남긴다.
- 테스트가 문서를 읽어 코드와 맞춰 보는 표(`test/unit/attention.test.ts` 가 읽는 「이유 문구」 표와 「동작」 표).

### 지켜야 하는 것

- **다른 곳이 「절 이름」 으로 가리키는 헤딩은 이름을 바꾸지 않는다.** 코드 주석과 다른 문서가 그 이름으로 가리킨다. 본문이 다 지워지는 절도 헤딩은 남기고 한두 문장(결정이나 갈리는 지점, 없으면 「코드는 `<클래스>` 가 갖는다」)을 둔다.
  다른 곳이 가리키지 않는 헤딩은 합치거나 지우거나 이름을 바꿔도 된다. 가리키는지는 `node --test test/unit/doc-references.test.ts test/unit/doc-links.test.ts` 로 확인하고, 다음 줄에 「절」 이 오는 주석은 `git grep -n -A1 "docs/features/<파일>"` 로 직접 본다.
  절 이름에 붙은 `(화면)`, `(화면 흐름)`, `(전체 흐름)` 표시는 그 절을 합쳐 없애면 함께 사라진다. 다른 곳이 그 이름을 가리키면 남은 절로 링크를 고친다.
- 남기는 문장에 적힌 클래스, 메서드, 경로, 오류 코드, 설정 키는 코드에서 실제 정의를 찾아 맞는지 확인한다. `test/unit/doc-code-references.test.ts` 가 경로, API, 설정 키, `클래스.멤버`, 환경 변수를 본다. 클래스 이름 단독과 대문자 값은 알림만 내므로 직접 `git grep` 으로 확인한다.
- 옮긴 문장 중 「아래 「X」」, 「위 「X」」 처럼 이제 다른 기능 파일에 있는 절을 가리키는 곳은 그 파일로의 링크로 바꾼다.
- 동작과 코드는 바꾸지 않는다. 이 phase 의 파일 밖은 고치지 않는다. 다른 파일이 지운 절을 링크해 테스트가 실패하면 멈추고 `PHASE_BLOCKED: <파일> 이 지운 절 「<이름>」 을 가리킨다` 를 낸다.
- 새 문서 파일을 만들지 않는다.
- 공개 저장소다. 홈서버 주소, 포트, 컨테이너 이름, 경로, 계정, 실제 사람 이름을 적지 않는다.
- 한국어 문장은 조사와 어미를 생략하지 않고, 한 줄에 한 문장을 둔다. 새로 쓰는 문장에서는 「시험」 대신 「테스트」 를 쓴다.

## 의도 메모

- 목표는 줄 수가 아니라 「코드만 봐서는 알 수 없는 것」 이다. 다만 파일 하나 500줄을 넘기면 아직 복사본이나 중복이 남았다는 신호로 보고 다시 훑는다.
- 고쳐 쓰기보다 지우기를 먼저 한다. 지우고 나서 앞뒤 문장이 끊기면 그 연결만 고친다.
- 커밋 메시지 본문에 파일마다 줄 수 전후와, 위 「지우는 것」 표의 종류별 대략의 줄 수, 코드와 어긋나 지운 낡은 내용 목록을 적는다. PR 본문의 표가 이것을 모은다.

### 이 phase 에서 더 볼 것

- `hermes/docs/hermes-contract.md` 의 「도구 hook 과 승인」, 「MCP 프로세스의 환경 값」 과 겹치는 Hermes 쪽 동작은 그 문서에 두고 링크한다. hermes-contract 는 고치지 않는다.
- 커넥터 도구 목록과 manifest 칸은 `hermes/connectors/<이름>/connector.json` 과 `hermes/connectors/README.md` 를 가리킨다.

## 작업 항목

### 1. `docs/features/connector.md`

1. 파일을 `##` 절 단위로 읽고, 절마다 그 기능의 코드(`backend/src/main/java/com/bifos/assistant/`, `web/src/`, `hermes/` 의 해당 위치)를 연다.
2. 원재료마다 되풀이된 흐름을 `## 흐름` 의 mermaid 하나와 갈리는 지점 절로 합친다. `## 요구` 를 파일 머리에 둔다.
3. 「지우는 것」 에 드는 줄을 지우고, 남는 문장의 식별자와 동작을 코드와 대조한다. 어긋나면 지우거나 지금 코드에 맞게 한 문장으로 고친다.
4. 그 기능의 테스트(`backend/src/test/java/` 의 같은 패키지, `web/src/**/*.test.ts`, `test/browser/`)가 확인하는 분기는 문서에 한 줄 결론만 남겨도 된다.

### 2. `docs/features/connector-policy.md`

1. 파일을 `##` 절 단위로 읽고, 절마다 그 기능의 코드(`backend/src/main/java/com/bifos/assistant/`, `web/src/`, `hermes/` 의 해당 위치)를 연다.
2. 원재료마다 되풀이된 흐름을 `## 흐름` 의 mermaid 하나와 갈리는 지점 절로 합친다. `## 요구` 를 파일 머리에 둔다.
3. 「지우는 것」 에 드는 줄을 지우고, 남는 문장의 식별자와 동작을 코드와 대조한다. 어긋나면 지우거나 지금 코드에 맞게 한 문장으로 고친다.
4. 그 기능의 테스트(`backend/src/test/java/` 의 같은 패키지, `web/src/**/*.test.ts`, `test/browser/`)가 확인하는 분기는 문서에 한 줄 결론만 남겨도 된다.

### 3. 이 phase 를 검증하는 테스트

새 테스트는 만들지 않는다. 문서만 바뀌므로 문서 검사 테스트(`test/unit/doc-*.test.ts`) 가 이 phase 의 검증이다.
헤딩을 지워 코드 주석의 「절」 이 끊기면 `doc-references` 가, 백틱으로 남긴 이름이 코드에 없으면 `doc-code-references` 가 실패한다.

## 검증

```bash
node --test test/unit/doc-files.test.ts test/unit/doc-code-references.test.ts test/unit/doc-references.test.ts test/unit/doc-links.test.ts test/unit/attention.test.ts
node scripts/check-file-length.mjs
wc -l docs/features/connector.md docs/features/connector-policy.md
```

- 첫 줄은 종료 코드 0 이다. 둘째 줄은 이 phase 의 파일에 대한 `알림:` 이 없어야 한다.
- 셋째 줄의 각 파일이 500줄 이하이고 합계가 목표 줄 수 안이다. 넘으면 남은 절을 다시 훑고, 그래도 넘으면 까닭을 회신에 적는다.
- `node --test test/unit/doc-code-references.test.ts` 의 `알림:` 가운데 이 phase 파일의 것은 새로 늘지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/connector.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
