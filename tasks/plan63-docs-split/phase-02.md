# Phase 02. 색인을 docs/README.md 로 모은다

**Execution profile**: standard

## 목표

문서 색인을 `docs/README.md` 하나로 모은다.
지금 색인이 루트 `AGENTS.md` 와 `docs/code-architecture.md` 에 둘 있고, 어느 쪽도 전체가 아니라서다.

**범위 외**: `AGENTS.md` 세 파일의 다른 절을 줄이는 일은 phase 07, 08, 09 다.

## 컨텍스트

앞 phase 가 문서를 `docs/`, `docs/backend/`, `docs/backend/schema/`, `docs/frontend/`, `docs/hermes/` 로 옮겼다.
색인은 문서마다 「무엇을 소유하는가」 를 한 줄로 적는다. 독자는 색인에서 고칠 주제의 파일 하나를 찾는다.

**근거 문서**: `AGENTS.md` 의 「읽기 순서」 절, `docs/code-architecture.md` 의 「문서」 절

## 의도 메모

- 색인을 층마다 따로 두는 안은 버렸다. 색인이 여럿이면 서로 어긋난다. 이번에 고치는 문제가 그것이다.
- frontend 표에 화면 관련 ADR 번호를 나열하지 않는다. ADR 이 늘 때마다 낡는다. `docs/adr/INDEX.md` 의 층 칸을 가리킨다. 층 칸은 phase 06 이 더한다.

## 작업 항목

### 1. `docs/README.md` 를 새로 쓴다

h1 은 `# 문서` 다. 그 아래 한 문단으로 층을 나눈 기준(공통은 두 층에 걸친 것, backend 는 Control Plane, frontend 는 화면, hermes 는 외부 런타임의 동작)을 적는다.

표 넷을 둔다. 칸은 「문서」, 「소유하는 것」 둘이다. 「소유하는 것」 은 그 파일의 실제 절을 읽고 한 줄로 쓴다.

- **공통**: `prd.md`, `code-architecture.md`, `flow.md`, `connectors.md`, `model-tiers.md`, `adr/INDEX.md`
- **backend**: `docs/backend/*.md` 전부와 `backend/schema/README.md`. schema 의 다섯 파일은 `schema/README.md` 가 색인하므로 여기서는 `schema/README.md` 한 줄만 둔다
- **frontend**: `docs/frontend/*.md` 전부. 표 아래에 「화면에 관한 결정은 `adr/INDEX.md` 에서 층 칸이 frontend 인 것을 본다」 한 줄
- **Hermes**: `hermes/README.md`(docs 쪽 색인)와 저장소 루트의 `hermes/README.md`(plugin 과 profile 틀) 두 줄. `docs/hermes/` 의 개별 파일은 그쪽 README 가 색인한다

### 2. `docs/backend/schema/README.md` 에 다섯 파일의 색인을 더한다

h1 바로 아래에 표를 둔다. 칸은 「파일」, 「표」 다. 각 파일의 `##` 헤딩(표 이름)을 그대로 나열한다.

### 3. `docs/code-architecture.md` 의 「문서」 절을 지운다

절 전체를 지운다. 이 절을 가리키는 링크가 있으면 `docs/README.md` 로 바꾼다.

### 4. 루트 `AGENTS.md` 의 「읽기 순서」 표를 세 줄로 바꾼다

| 문서 | 언제 보는지 |
| --- | --- |
| `docs/README.md` | 문서 전체의 색인. 제품 범위, 경계, Hermes 연동, ADR 을 여기서 찾는다 |
| `backend/AGENTS.md` | Control Plane 을 고칠 때 |
| `web/AGENTS.md` | 화면을 고칠 때 |

세 줄 모두 지금 표처럼 링크로 적는다.
`AGENTS.md` 의 다른 절이 `docs/hermes/README.md` 를 직접 가리키는 자리는 그대로 둔다.

### 5. `backend/AGENTS.md` 와 `web/AGENTS.md` 의 머리에 문서 포인터를 둔다

`backend/AGENTS.md` 는 h1 과 소개 문단 바로 아래에 목록 넷을 둔다.

- 패키지와 경계: `../docs/backend/packages.md`
- 표와 칸: `../docs/backend/schema/README.md`
- Hermes 호출: `../docs/hermes/README.md`
- 그 밖의 주제: `../docs/README.md` 의 backend 표

`backend/AGENTS.md` 본문이 `../docs/backend/packages.md` 를 이미 가리키는 자리(phase 01 이 고친 것)는 그대로 둔다.

`web/AGENTS.md` 는 같은 자리에 목록 셋을 둔다.

- 화면 구조: `../docs/frontend/structure.md`
- 화면 동작: `../docs/frontend/shell.md`, `../docs/frontend/chat.md`, `../docs/frontend/activity.md`
- 화면 부품과 색의 결정: `../docs/adr/INDEX.md` 에서 층 칸이 frontend 인 것

### 6. 프롬프트의 색인 참조를 맞춘다

`.github/workflows/code-review-prompt.txt` 와 `.github/workflows/architecture-audit-prompt.txt` 가 「읽을 문서」 로 `docs/code-architecture.md` 만 드는 자리에 `docs/README.md` 를 함께 적는다. 문장의 뜻은 바꾸지 않는다.
이 두 파일의 문장을 단언하는 테스트는 없다. 고친 뒤 두 파일을 다시 읽어 문장이 이어지는지 본다.

## 검증

```bash
# cwd: 저장소 root
# 1. 색인이 docs 의 모든 문서를 한 번 이상 가리킨다. 출력이 없어야 한다
for f in docs/*.md docs/backend/*.md docs/frontend/*.md; do
  [ "$f" = docs/README.md ] && continue
  grep -q "(${f#docs/})" docs/README.md || echo "색인에 없다: $f"
done
for f in docs/backend/schema/*.md; do
  [ "$f" = docs/backend/schema/README.md ] && continue
  grep -q "($(basename "$f"))" docs/backend/schema/README.md || echo "schema 색인에 없다: $f"
done

# 2. 링크와 앵커. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr backend/AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr web/AGENTS.md

# 3. 프롬프트를 읽는 테스트와 공개 정보 검사
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

기대값: 1번 출력 없음. 2번 출력에 `깨진 링크`, `없는 앵커` 가 든 줄이 0건이다. 종료 코드는 보지 않는다. `INDEX_DESYNC` 는 검사기의 알려진 오탐이고 그것 때문에 종료 코드가 늘 1 이다. 3번 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/README.md` | 신규 |
| `docs/backend/schema/README.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `.github/workflows/code-review-prompt.txt` | 수정 |
| `.github/workflows/architecture-audit-prompt.txt` | 수정 |
