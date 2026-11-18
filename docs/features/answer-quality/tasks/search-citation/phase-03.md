# Phase 03. 문서와 profile 틀에 검색 서비스와 평가를 적는다

**Execution profile**: standard

## 목표

phase 01 과 02 가 바꾼 동작과, Hermes v0.23.2 소스에서 확인한 검색 서비스의 동작을 책임 문서에 적는다. 다음에 읽는 사람이 계획서 없이 지금 동작과 평가 방법을 알게 하려는 것이다.

**범위 외**: 검색 서비스 값을 정해 틀에 넣기와 그 ADR(평가 뒤 후속 PR), 운영 profile 고치기와 기본 에이전트에 `web` 켜기(운영 저장소와 관리자 화면).

## 컨텍스트

- 기능 파일 `docs/features/answer-quality.md` 는 지금 「아직 구현 전」 한 줄뿐이다. 기능 파일이 갖는 것은 `docs/adr/ADR-20261009-feature-docs.md` 와 `docs/adr/ADR-20261102-feature-loop.md` 가 정한다. 머리의 `covers:` 는 `scripts/ci/feature-covers.test.ts` 가 추적 파일과 대조한다
- Hermes 의 동작 계약은 `hermes/docs/hermes-contract.md` 가 갖는다. toolset 표는 「toolset 의 목록과 접근 범위」 에 있다
- profile 틀은 `hermes/profile-template/config.yaml.template` 이다. 틀의 주석은 대부분 영어다. 새 주석도 영어로 쓴다. 틀을 읽는 테스트는 `hermes/tests/test_bundle.py` 이고 `check-local.sh --fast` 밖이다. 주석만 더하므로 CI 의 hermes job 결과로 확인한다
- 공통 답변 지침을 설명하는 곳: `docs/features/memory.md` 의 「범위와 조립」, `backend/docs/code-architecture.md` 의 지침 차례 표(`ContextAssembler.withResponseInstructions` 를 가리키는 줄), `docs/features/chat.md` 의 「에이전트가 물을 때」(turn 전용 형식 안내)
- 블로그 평가 문서 `docs/features/naver-blog-connector.md` 의 「블로그 사진 평가」 는 파일 표에 클라이언트를 적지 않는다. 옮긴 경로 때문에 고칠 줄이 있는지만 확인한다
- 공개 저장소다. 운영 주소, 계정, 접속 방법, 토큰을 만드는 방법을 적지 않는다. 운영에서 확인한 것은 결과 수치만 적는다
- 문서는 계획서를 가리키지 않는다. 금지 낱말은 `korean-check` 의 매핑 표를 따른다

**요구사항**: REQ5, REQ6

**근거 문서**: `docs/features/answer-quality/spec.md`(REQ5, REQ6, 「배포 뒤 확인」), `docs/features/answer-quality/design.md`(「upstream 소스로 확인한 사실」, 「PR1 의 순서」, DECISION4, DECISION8)

## 의도 메모

- 이 PR 은 검색 서비스 값을 틀에 넣지 않는다. 값은 배포 뒤 평가로 정한다. 틀에는 자리와 두 값의 뜻만 주석으로 둔다. 값이 없으면 Hermes 는 지금처럼 스스로 고른다
- REQ6 은 코드가 없다. 관리자가 에이전트 도구 화면에서 켠다(스펙 DECISION11). 기능 파일의 배포 뒤 확인 항목으로 둔다
- 새 ADR 을 만들지 않는다. 평가 방식은 ADR-20261020 의 틀을 따르고, 검색 서비스 선택의 ADR 은 숫자가 나온 뒤 후속 PR 이 쓴다

## 작업 항목

### 1. `docs/features/answer-quality.md`

「아직 구현 전」 줄을 지우고 아래 절을 쓴다. 스펙의 결정 표를 복사하지 않고 링크한다.

- 요구: 무엇을 하고 하지 않는지 짧게. 스펙 링크
- 지침: 「최신 정보와 출처」 절이 드는 실행 종류 표(대화, 예약, 살펴보기), 날짜 줄의 자리와 지문에 들지 않는 까닭, Hermes 내장 위임의 자식에는 가지 않는다는 것. 글의 원본은 `ContextAssembler` 라고 가리킨다
- 검색 서비스: 값의 자리(profile 설정), 두 후보와 차이(내장 검색은 검색 사건과 검색어가 남지 않는다), 아직 값을 정하지 않았고 평가로 정한다는 것
- 답변 품질 평가: 파일 표(`questions.json`, `run.ts`, `score.ts`, 클라이언트), 명령 사용법, 통과 기준(스펙 DECISION10 링크), 세는 규칙, CI 에서 돌지 않고 배포 전후에 사람이 돌린다는 것. 접속 값은 운영 저장소가 갖는다고만 적는다
- 측정 기록: 2026-11-18 의 고치기 전 운영 수치(질문 turn 101개 중 `web_search` 실행 2개, `web_extract` 2번 중 실패 2번, 모두 슬라이더의 `Medium`)와, 평가 결과를 적을 표의 머리
- 배포 뒤 확인: 스펙 「배포 뒤 확인」 의 PR1 항목(1~4)과, 플러그인이 붙은 에이전트에서 가격을 물었을 때 답 첫머리에 「확인하지 못했다」 가 붙지 않는지
- `covers:` 에 `test/model-eval/answer/`, `test/model-eval/control-plane.ts`, 날짜 줄 클래스와 실행 종류 enum 의 경로를 더한다

### 2. `hermes/docs/hermes-contract.md`

「toolset 의 목록과 접근 범위」 가까이에 「웹 검색 서비스」 절을 더한다. 설계 「upstream 소스로 확인한 사실」 표의 다섯 줄을 근거 파일과 함께 옮기고, 읽은 tag(v0.23.2)와 「운영 왕복으로는 확인하지 않았다」 를 적는다.

### 3. `hermes/profile-template/config.yaml.template`

`memory:` 절 가까이에 주석 처리한 `web:` 절을 둔다. `search_backend` 와 `extract_backend` 가 무엇을 정하는지, 비워 두면 Hermes 가 스스로 고른다는 것, `openai-native` 는 ChatGPT 구독 경로에서만 되고 검색 사건을 남기지 않는다는 것을 틀의 다른 주석과 같은 언어로 적는다. 실제 값은 넣지 않는다.

### 4. 지침을 설명하는 기존 문서

- `backend/docs/code-architecture.md` 의 지침 차례 표: 공통 답변 지침 줄에 「최신 정보와 출처」 절과 실행 종류를 반영한다
- `docs/features/memory.md` 의 「범위와 조립」: `context_chars` 와 `instructions_hash` 가 새 절을 함께 센다는 것, 날짜 줄은 들지 않는다는 것
- `docs/features/chat.md` 의 「에이전트가 물을 때」: turn 전용 지시에 날짜 줄이 함께 붙는다는 한 줄
- `docs/features/answer-quality/design.md` 의 「upstream 소스로 확인한 사실」 머리 문장을 「계약 문서로 옮겼다」 로 고친다

## 검증

```bash
node --test scripts/ci/feature-covers.test.ts test/repo/doc-files.test.ts test/repo/doc-code-references.test.ts
scripts/dev/check-local.sh --fast
```

기대값: 모두 통과. `check-local.sh` 는 무거운 검사 대기열로 감싸 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/answer-quality.md` | 수정 (지금 동작, 평가, 측정 기록) |
| `hermes/docs/hermes-contract.md` | 수정 (웹 검색 서비스 절) |
| `hermes/profile-template/config.yaml.template` | 수정 (`web` 절 주석) |
| `backend/docs/code-architecture.md` | 수정 (지침 차례 표) |
| `docs/features/memory.md` | 수정 (지문과 글자 수 설명) |
| `docs/features/chat.md` | 수정 (날짜 줄 한 줄) |
| `docs/features/answer-quality/design.md` | 수정 (옮긴 사실 표의 머리 문장) |
