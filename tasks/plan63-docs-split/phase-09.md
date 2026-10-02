# Phase 09. 루트 지침을 실제와 맞추고 스크립트가 소유한 설명을 덜어 낸다

**Execution profile**: standard

## 목표

루트 `AGENTS.md` 에서 실제와 어긋난 문장을 고치고, 스크립트와 워크플로가 소유한 설명을 덜어 낸다.
모든 작업이 이 파일을 먼저 읽으므로, 여기가 틀리거나 길면 모든 작업이 그 비용을 낸다.

커밋 제목의 범위는 `docs` 다.

**범위 외**

- 「공개 저장소」 절의 금지 목록과 「머지는 PR 로 한다」 절의 규칙을 바꾸지 않는다.
- 위험 라벨의 기한 문장(2026-10-11)은 그대로 둔다. 그날 뒤에 따로 본다.
- `tasks` 절의 `orchestration` 이라는 스킬 이름은 그대로 둔다.
- `scripts/check-public-safe.sh` 의 값 목록 기본 경로는 고치지 않는다. 운영 저장소와 함께 고쳐야 한다.
- 검사의 순서와 내용을 바꾸지 않는다.

## 컨텍스트

`CLAUDE.md` 가 `AGENTS.md` 와 같은 내용이다. 심볼릭 링크인지 `ls -l CLAUDE.md` 로 보고, 따로 있는 파일이면 같은 변경을 양쪽에 한다.

소유자다.

| 무엇 | 소유자 |
| --- | --- |
| 검사의 순서와 설치 단계 | `scripts/check-local.sh` |
| `check` 와 `fix` 가 하는 일 | `scripts/quality.sh` 의 머리 주석 |
| CI 의 job 구조와 shard, 매일 실행, 실패 이슈 | `.github/workflows/ci.yml` |
| 주간 점검의 일정과 관점 | `.github/workflows/claude-architecture-audit.yml` |
| 문서 색인 | `docs/README.md` |

`.github/workflows/code-review-prompt.txt` 와 `.github/workflows/architecture-audit-prompt.txt` 가 이 문서의 절을 이름으로 가리킨다.
절 이름을 바꾸거나 절을 지우면 그 프롬프트도 함께 고친다. `test/unit/doc-references.test.ts` 가 「」 로 가리킨 절 이름을 잡는다.

**근거 문서**: `docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md`, `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- 「확인」 절의 명령 목록을 통째로 지우는 안은 버렸다. 「적힌 순서대로 모두 돌린다」 와 「직접 돌려 확인한 것이라야 한다」 는 이 저장소의 머지 규칙이라 남는다. 지우는 것은 스크립트 주석과 글자까지 같은 설명이다.
- 실제로 있었던 실수를 적은 문장(「실제로 그 실수를 했다」 류)은 규칙의 까닭이면 남긴다. 까닭이 앞 문장에 이미 있으면 지운다.

## 작업 항목

### 1. 실제와 어긋난 문장을 고친다

| 자리 | 지금 | 고칠 내용 |
| --- | --- | --- |
| 머리말 | 「이 저장소는 Control Plane 과 웹을 맡는다」 | 같은 문서가 `hermes/` 도 이 저장소가 갖는다고 적는다. 「Control Plane 과 웹, Hermes 에 설치하는 plugin 과 profile 틀을 맡는다」 로 고친다 |
| 「tasks 는 구현 문서만 담는다」 | 두 프롬프트가 「계획서를 번호로 가리키지 않는다」 를 이 절에서 인용하는데 이 절에 그 문장이 없다 | 「`docs/` 와 코드는 계획서를 번호로 가리키지 않는다. 계획서는 지워지고 지난 계획은 git 이력에서 찾는다」 를 더한다 |
| 「확인」 | 「아래 여덟 검사」, 「위 여덟 검사」 | 검사 수를 숫자로 적지 않는다. 「아래 검사」 로 고친다. `scripts/check-local.sh` 의 단계 수와 어긋나 있다 |
| 「확인」 | Node 의 최소 버전을 숫자로 적은 문장 | `scripts/check-local.sh` 가 그 버전을 검사하면 숫자를 지우고 「필요한 Node 버전은 `scripts/check-local.sh` 가 검사한다」 로 고친다. 검사하지 않으면 그대로 둔다 |

### 2. 스크립트와 워크플로가 소유한 설명을 덜어 낸다

지우기 전에 같은 내용이 소유자 파일에 있는지 읽어 확인한다. 소유자 파일에 없는 설명은 지우지 않고 남긴다. 이 phase 는 스크립트와 워크플로를 고치지 않는다.

| 자리 | 지울 것 | 남길 것 |
| --- | --- | --- |
| 「확인」 의 설치 단계 설명 | `scripts/check-local.sh` 머리 주석과 같은 다섯 줄(웹 의존성과 Playwright 설치, 자리표시자 환경 변수, hermes 단계의 SDK 설치) | 「처음 받은 checkout 에서도 그대로 돈다. 처음 실패한 단계에서 멈추고 그 로그의 끝을 보인다」 |
| 「확인」 의 `quality.sh` 설명 | `check` 와 `fix` 가 돌리는 도구를 나열한 두 문장 | 「`check` 는 파일을 바꾸지 않고 검사하고, `fix` 는 기계가 고칠 수 있는 위반만 고친다. 새 위반은 기준에 더하지 않는다」 와 두 `AGENTS.md` 링크 |
| 「확인」 의 CI 구조 | shard 수, job 수, runner 마다 띄우는 것, artifact, cron 시각, 실패 이슈를 모으는 방법 | 머지 판단에 쓰는 문장만 남긴다: PR 은 merge ref 를 검사한다, 필수 검사 `browser-mobile` 과 `browser-desktop` 은 그 폭의 shard 가 모두 성공해야 통과한다, 브라우저 검사는 PR 의 CI 결과가 머지 전 확인이다, PR 실패는 그 PR 에서 고친다, 모인 실패 이슈는 고치거나 까닭을 적어 닫는다. 나머지는 `.github/workflows/ci.yml` 을 가리킨다 |
| 「확인」 의 fork PR 문단 | secret 을 받지 못하는 실행의 동작 설명 | 「값 목록은 repository secret `PUBLIC_REPO_DENYLIST` 다. 운영 저장소의 목록이 바뀌면 secret 도 다시 넣는다」 |
| 「주간 점검」 | 요일과 시각, 관점 목록, 돌지 않는 조건 | 「점검 이슈는 결함 후보다. 고치기 전에 코드에서 사실인지 확인한다. 사실이 아니면 까닭을 적고 닫는다」 와 워크플로 파일 링크 |

스크립트가 차례로 돌리는 명령의 코드 블록은 남긴다. 한 단계만 다시 돌릴 때 쓴다.

### 3. e2e 함정 문장

「확인」 절의 `this agent code is already used` 문장을 다룬다.
`backend/AGENTS.md` 에 같은 문장이 남아 있는지 본다. 남아 있지 않으면 앞 phase 가 e2e 를 두 번 이어 돌려 통과를 확인하고 지운 것이다. 그러면 여기서도 지운다.
남아 있으면 아래 명령을 돌린다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts && node test/e2e/run.ts
```

두 번 다 통과하면 지운다. 「위 명령을 적힌 순서대로 모두 돌린다」 는 남긴다.

### 4. 배포 확인 목록

「배포했다고 말하기 전에 보는 것」 절의 목록 세 줄(컨테이너 생성 시각, 기동 로그, 스키마 검증)을 지우고 「배포 확인 항목은 운영 저장소가 갖는다」 한 줄로 가리킨다.
「운영」 절의 첫 줄이 운영 절차를 이 저장소가 갖지 않는다고 적는다. 목록이 그 문장과 어긋난다.

**「테스트가 모두 통과해도 운영에서 동작하지 않을 수 있다」 문단은 남긴다.** Hermes 와 주고받는 것을 바꿨으면 배포 뒤 실제 실행을 한 번 왕복시킨다는 규칙은 이 저장소의 변경이 요구하는 확인이다.
절 제목은 남은 내용에 맞게 「Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다」 로 바꾼다.

### 5. 프롬프트와 색인을 맞춘다

- 두 프롬프트가 이 문서의 절을 이름으로 가리키는 자리를 `git grep -n 'AGENTS.md' .github/workflows` 로 찾아, 바뀐 절 이름과 맞춘다.
- 루트에 한 줄을 더한다. 「이 문서의 절 이름을 바꾸면 `.github/workflows/` 의 프롬프트 둘도 함께 고친다」. 「머지는 PR 로 한다」 절의 리뷰 기준 문장 옆에 둔다.
- `web/AGENTS.md` 와 `backend/AGENTS.md` 가 루트 「확인」 절이나 「운영」 절을 가리키는 문장을 바뀐 내용에 맞춘다. 루트가 더는 갖지 않는 설명(CI 의 shard 구조)을 루트에 있다고 적은 문장은 `.github/workflows/ci.yml` 을 가리키게 고친다.
- `docs/README.md` 가 루트 「확인」 절이나 「운영」 절을 가리키면 바뀐 제목에 맞춘다. `docs/prd.md` 가 루트 「확인」 절을 가리키는 자리도 본다.

### 6. 절 이름 참조를 `test/unit/doc-references.test.ts` 로 확인한다

이 테스트는 코드 주석과 프롬프트가 `AGENTS.md` 뒤에 「」 로 가리킨 절이 그 파일의 헤딩에 있는지 본다. 절 이름을 바꾸거나 절을 지운 뒤 이 테스트가 낸 `파일:줄` 을 새 이름에 맞춘다. 테스트 파일은 고치지 않는다.

## 검증

```bash
# cwd: 저장소 root
# 1. 지침이 줄었다
wc -l AGENTS.md

# 2. 지운 것이 남지 않았다. 출력이 없어야 한다
grep -n '여덟 검사\|04:23\|docker ps' AGENTS.md

# 3. 문서 경로와 절 이름 검사, 문서 경로 검사, 링크. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
node --test 'test/unit/**/*.test.ts'
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs
scripts/check-public-safe.sh
```

기대값: 1번 작업 전보다 30줄 넘게 줄었다. 2번 출력 없음. 3번 종료 코드 0, `static_check.py` 만 예외다. 출력에 `깨진 링크`, `없는 앵커` 가 든 줄이 0건이다. 종료 코드는 보지 않는다. `INDEX_DESYNC` 는 검사기의 알려진 오탐이고 그것 때문에 종료 코드가 늘 1 이다.
`node --test` 는 `test/unit/doc-references.test.ts` 를 포함한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `AGENTS.md` | 수정 |
| `.github/workflows/code-review-prompt.txt` | 수정 |
| `.github/workflows/architecture-audit-prompt.txt` | 수정 |
| `docs/README.md` | 수정 |
| `docs/prd.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
