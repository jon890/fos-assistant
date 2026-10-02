# Phase 07. backend 지침에서 설정 파일이 소유한 것을 덜어 낸다

**Execution profile**: standard

## 목표

`backend/AGENTS.md` 에서 설정 파일과 같은 내용을 옮겨 적은 표와, 조건이 맞을 때만 읽는 절차를 덜어 낸다.
지침이 매번 읽히는 파일이라, 규칙을 바꾸는 사람이 여는 파일과 따로 고쳐야 해서 낡기 때문이다.

커밋 제목의 범위는 `backend` 다. `docs(backend): …` 로 쓴다.

**범위 외**

- 규칙 자체를 바꾸지 않는다. ArchUnit 규칙, Checkstyle 설정, 기준 파일의 내용은 그대로다.
- `web/AGENTS.md` 는 phase 08, 루트 `AGENTS.md` 는 phase 09 다.
- `backend/build.gradle.kts` 의 `smokeRun` 주석은 고치지 않는다.

## 컨텍스트

소유자를 이렇게 정했다.

| 무엇 | 소유자 |
| --- | --- |
| 구조 규칙의 이름과 뜻 | `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 Javadoc |
| 코드 규칙의 심각도와 까닭 | `backend/config/checkstyle/checkstyle.xml` 의 주석 |
| 예외의 까닭 | `backend/config/checkstyle/suppressions.xml` 의 주석 |
| 왜 이 도구와 이 방식인가 | `docs/adr/ADR-042-*.md` |
| 기준을 갱신하는 방법, 뺀 규칙, suppress 줄의 모양 | `docs/backend/quality.md`(이 phase 가 만든다) |

층 방향 규칙과 패키지 표는 앞 phase 가 만든 `docs/backend/packages.md` 에 있다.

위 경로는 작업 전에 `git ls-files backend | grep -i 'ArchitectureRules\|checkstyle\|suppressions'` 로 실제 위치를 확인한다.

지금은 소유를 서로 가리키는 고리가 있다.
`backend/AGENTS.md` 가 `ArchitectureRules.java` 를 소유자로 들고, 그 Javadoc 여러 곳이 「근거: `backend/AGENTS.md` 구조 규칙」 으로 돌아온다. 그 절에는 표의 한 줄 말고 다른 근거가 없다.
`backend/AGENTS.md` 가 ADR-042 를 가리키고, ADR-042 가 「규칙 목록과 갱신 방법은 두 `AGENTS.md` 가 갖는다」 로 돌아온다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `AGENTS.md` 의 「확인」 절

## 의도 메모

- 규칙 표를 `docs/backend/quality.md` 로 옮겨 남기는 안은 버렸다. Javadoc 과 이중 관리가 그대로 남는다.
- `backend/AGENTS.md` 에는 설정으로 강제하지 못하는 규칙과 「위반을 만났을 때 무엇을 하는가」 만 남긴다.
- 지우기 전에 표의 각 줄이 소유자 파일에 같은 뜻으로 있는지 줄마다 확인한다. 소유자 쪽에 없는 까닭이 표에만 있으면 그 까닭을 소유자 파일의 주석으로 옮긴 뒤 지운다.

## 작업 항목

### 1. `backend/AGENTS.md` 의 규칙 표 둘을 지운다

- 「구조 규칙」 절의 ArchUnit 규칙 표(규칙 이름과 뜻)를 지우고 「규칙의 이름과 뜻은 `ArchitectureRules.java` 의 Javadoc 이 갖는다」 한 줄로 가리킨다.
- 「코드 규칙」 의 「넣은 규칙」 표(심각도와 까닭)를 지우고 「규칙과 까닭은 `checkstyle.xml` 의 주석이 갖는다」 한 줄로 가리킨다.
- `suppressions.xml` 의 예외 셋을 되풀이한 문장을 지운다.
- 「패키지 배치」 의 층 방향 세 줄은 `../docs/backend/packages.md` 를 가리키는 한 줄로 바꾼다. 그 문서에 같은 세 줄이 있는지 먼저 본다.
- 「코드로 옮기지 않은 문장」 절: 중첩 결과 타입 규칙을 `ArchitectureRules.java` 가 이미 강제하면(그 규칙 상수와 Javadoc 을 읽어 확인한다) 이 절과 「데이터 클래스는 컨트롤러 안에 두지 않는다」 에서 그 문장을 지운다.

### 2. `docs/backend/quality.md` 를 만든다

조건이 맞을 때만 읽는 절차를 `backend/AGENTS.md` 에서 옮긴다. 문장은 그대로 옮기고 h1 과 소개 두 줄만 새로 쓴다.
`backend/AGENTS.md` 에는 `### 기준 파일` 이 둘 있다. 한 문서에 같은 헤딩을 둘 두지 않으므로 옮기며 이름을 `## 구조 규칙의 기준 파일`, `## 코드 규칙의 기준 파일` 로 바꾼다. 나머지 헤딩은 `##` 로 둔다.

- 구조 규칙의 「기준 파일」 절(기준을 갱신하는 방법)
- 코드 규칙의 「뺀 규칙」, 「기준 파일」 절
- suppress 줄의 모양을 적은 문단
- 「자동으로 고치기」 절의 OpenRewrite 버전 고정 설명

h1 은 `# backend 품질 검사` 다. `backend/AGENTS.md` 의 원래 자리에는 「기준 파일을 갱신하거나 규칙을 뺄 때는 `../docs/backend/quality.md` 를 읽는다」 한 줄을 둔다.
`docs/README.md` 의 backend 표에 `backend/quality.md` 한 줄을 더한다.

OpenRewrite 플러그인 버전을 고정한 까닭은 `backend/gradle/libs.versions.toml` 의 그 버전 줄 위에 한국어 주석 한 줄로도 적는다. 버전을 올리는 사람이 여는 파일이 그것이다.

### 3. Javadoc 의 돌아오는 「근거」 를 직접 적는다

`ArchitectureRules.java` 의 Javadoc 이 `backend/AGENTS.md` 를 가리키는 자리를 찾는다.

```bash
# cwd: 저장소 root
git grep -n 'AGENTS.md' -- 'backend/src/test/java/**/ArchitectureRules.java' backend/config backend/build.gradle.kts scripts/quality.sh
```

자리마다 가리키는 절에 따라 다르게 한다.

| 가리키는 절 | 할 일 |
| --- | --- |
| 「구조 규칙」 을 근거로 든 Javadoc(여섯 곳) | 그 규칙이 왜 있는지 한 문장으로 바꾼다. 지우는 표의 「뜻」 칸에 있던 문장이 그 근거다 |
| 「패키지 배치」 를 근거로 든 Javadoc(층 방향 규칙 둘) | 층 방향 세 줄이 `docs/backend/packages.md` 로 가므로 `backend/AGENTS.md` 부분을 빼고 `docs/backend/packages.md` 의 그 절만 남긴다 |
| 「기술 주의점」, 「데이터 클래스는 컨트롤러 안에 두지 않는다」, 「테스트」 | 남는 절이다. 그대로 둔다. 다만 1번에서 그 절의 해당 문장을 지웠으면 근거를 한 문장으로 직접 적는다 |
| 클래스 Javadoc 의 「기준 파일을 갱신하는 방법은 … 「구조 규칙」 절에 있다」 | `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 로 바꾼다 |
| `backend/config/checkstyle/*.xml`, `backend/build.gradle.kts`, `scripts/quality.sh` 의 주석 | 옮겨 간 절(기준 파일, 뺀 규칙, suppress 줄, 자동으로 고치기)을 가리키면 `docs/backend/quality.md` 와 그 절 이름으로 바꾼다. 남는 절을 가리키면 그대로 둔다 |

### 4. ADR-042 의 소유 문장을 고친다

`docs/adr/ADR-042-*.md` 에서 「규칙 목록과 갱신 방법은 두 `AGENTS.md` 가 갖는다」 는 뜻의 문장을 「규칙 목록은 설정 파일이, 기준을 갱신하는 방법은 `docs/backend/quality.md` 가 갖는다」 로 고친다.
web 쪽 규칙 목록은 `web/eslint.config.mjs` 가 갖는다고 함께 적는다.

### 5. 배포 확인 목록을 지운다

`backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」 절 안이나 그 근처에 배포 뒤 확인 항목(컨테이너 생성 시각, 기동 로그의 기동 완료 문구, 스키마 검증)을 되풀이한 문장이 있으면 지우고 「배포 확인은 운영 저장소가 갖는다」 한 줄로 가리킨다.
엔티티와 마이그레이션이 어긋나면 기동이 실패한다는 설명은 남긴다. 그것은 이 저장소의 규칙이다.

### 6. e2e 함정 문장을 확인한 뒤 지운다

`backend/AGENTS.md` 의 「테스트」 절이 `this agent code is already used` 로 e2e 가 실패할 수 있다고 적는다.
`test/e2e/run.ts` 는 실행마다 임시 디렉터리를 만들어 그 아래에 H2 파일을 쓰므로, 앞선 실행의 데이터가 남을 길이 정적으로는 없다.

`gradlew test` 를 건너뛴 채 e2e 를 두 번 이어 돌린다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts && node test/e2e/run.ts
```

두 번 다 통과하면 그 문장을 지운다. 한 번이라도 그 오류로 실패하면 문장을 남기고 결과 보고에 적는다.
루트 `AGENTS.md` 의 같은 문장은 phase 09 가 지운다. 이 확인의 결과를 결과 보고에 적어 phase 09 가 쓰게 한다.

## 검증

```bash
# cwd: 저장소 root
# 1. 지침이 줄었다
wc -l backend/AGENTS.md docs/backend/quality.md

# 2. 지워진 절을 가리키는 주석이 없다. 출력이 없어야 한다
git grep -n 'AGENTS.md} 「구조 규칙」\|AGENTS.md} 의 「구조 규칙」\|AGENTS.md} 「패키지 배치」' -- backend/src/test/java backend/config

# 3. 구조 규칙 테스트가 그대로 통과한다. Javadoc 만 바뀌었다
(cd backend && ./gradlew test --tests '*Architecture*')

# 4. 품질 검사, 문서 경로 검사, 링크. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
scripts/quality.sh check
node --test 'test/unit/**/*.test.ts'
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr backend/AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs
scripts/check-public-safe.sh
```

기대값: 1번 `backend/AGENTS.md` 가 작업 전보다 100줄 넘게 줄고 `quality.md` 는 400줄 이하. 2번 출력 없음. 3번과 4번 종료 코드 0, `static_check.py` 만 예외다. 출력에 `깨진 링크`, `없는 앵커` 로 시작하는 줄이 0건이다. 종료 코드는 보지 않는다. `INDEX_DESYNC` 는 검사기의 알려진 오탐이고 그것 때문에 종료 코드가 늘 1 이다.
`node --test` 는 `test/unit/doc-references.test.ts` 와 `test/unit/quality-script.test.ts` 를 포함한다.

`gradlew` 는 `backend/` 에 있다. 저장소 root 에서 `./gradlew` 를 부르면 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/AGENTS.md` | 수정 |
| `docs/backend/quality.md` | 신규 |
| `docs/README.md` | 수정 |
| `docs/adr/ADR-042-*.md` | 수정 |
| `backend/src/test/java/**/ArchitectureRules.java` | 수정 |
| `backend/config/checkstyle/*.xml` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/gradle/libs.versions.toml` | 수정 |
| `scripts/quality.sh` | 수정 |
