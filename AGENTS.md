# AGENTS.md

가족용 AI 비서다.
Hermes Agent 를 Agent Runtime 으로 두고 이 저장소는 Control Plane 과 웹, Hermes 에 설치하는 plugin 과 profile 틀을 맡는다.

## 읽기 순서

| 문서 | 언제 보는지 |
| --- | --- |
| [`docs/prd.md`](docs/prd.md) | 제품의 목적과 범위, 범위 밖, 아직 만들지 않은 것 |
| [`docs/code-architecture.md`](docs/code-architecture.md) | 모듈의 경계, 사용자와 profile, 에이전트, 대화의 층, 비밀값을 두는 곳 |
| [`docs/features/`](docs/features/) | 기능마다 요구와 화면, Control Plane, Hermes 를 가로지르는 흐름 |
| [`docs/adr/INDEX.md`](docs/adr/INDEX.md) | 되돌리기 어려운 결정과 그 근거 |
| [`backend/AGENTS.md`](backend/AGENTS.md) | Control Plane 을 고칠 때 |
| [`web/AGENTS.md`](web/AGENTS.md) | 화면을 고칠 때 |
| [`hermes/AGENTS.md`](hermes/AGENTS.md) | Hermes 에 설치하는 plugin 과 profile 틀, 커넥터를 고칠 때 |

기능 파일은 `docs/features/` 에 두고, 모듈 `docs/` 에는 `code-architecture.md` 와 backend 의 `data-schema.md`, hermes 의 `hermes-contract.md` 만 둔다.
새 주제는 새 파일 대신 기능 파일의 절로 더한다. 배치는 [ADR-20261009 / feature-docs](docs/adr/ADR-20261009-feature-docs.md), 정해진 파일 밖의 예외와 까닭은 [ADR-20261009 / docs-per-module](docs/adr/ADR-20261009-docs-per-module.md) 이 갖고, `test/unit/doc-files.test.ts` 가 지킨다.

## 용어

**한 낱말이 한 가지만 가리키게 한다.**

| 무엇 | 쓰는 말 | 쓰지 않는 말 |
| --- | --- | --- |
| 가족 한 사람 | **사용자** | 구성원, member |
| 사용자들이 모인 단위 | **그룹**, 코드는 `group` | 가족, `family` |
| 관리자가 아닌 권한 등급 | **`MEMBER` 역할** | 구성원 |
| Hermes 쪽 격리 단위 | **profile** | |
| 대화를 시작할 때 고르는 것 | **에이전트** | |
| 사용자를 추가할 때 만드는 profile | **기본 profile** | 구성원 profile |
| 역할용으로 따로 만든 profile | **역할 profile** | |
| 에이전트 실행이 부모와 자식으로 이어진 흐름 | **실행 트리**, 줄여서 **트리** | 실행 나무, 나무 |
| 그 트리의 맨 위 실행 | **루트 실행**, 줄여서 **루트** | 뿌리 |
| 사용자가 해야 하거나 끝나기를 기다리는 일 한 줄 | **할 일**, 코드는 `follow_up` | 추적 목표, 챙길 일 |
| #161 의 Living View | **지금 화면**, 주소는 `/now` | Living View |
| `notification` 표의 줄. 알림 단추와 알림 화면에 보이는 것 | **알림** | 알림 줄 |
| 대화 안에 끼우는 안내 줄(`SYSTEM` 메시지) | **알림 줄** | |
| 에이전트에게 쥐어 주는 도구 묶음. plugin 의 `connector.json` 이 선언한다 | **커넥터** | 커넥터 에이전트(옮겨 가기 설명 밖) |
| 사용자가 커넥터 하나에 계정을 연결한 것 | **연결**, 코드는 `connector_connection`. 그 화면(`/connections`)과 메뉴는 「외부 서비스 연결」 | 화면 이름으로 「연결」 |
| 에이전트에 연결을 붙인 것 | **바인딩**, 화면에서는 「붙이기」, 코드는 `agent_connector_binding` | |
| 매 실행에 본문까지 싣는 짧은 개인 기억의 구역 | **개인 사실 구역**, 코드는 `MEMORY_FACTS` | 프로필 구역, 사용자 프로필 |

`MEMBER` 는 코드의 값이므로 그대로 쓰되, 사람을 가리킬 때는 쓰지 않는다.

**이미 적용된 마이그레이션 파일은 용어를 바꾸려고 고치지 않는다.** Flyway 가 주석까지 체크섬에 넣어 운영 기동이 멈춘다.

사용자, profile, 에이전트, 대화가 어떻게 이어지는지는 [`docs/code-architecture.md`](docs/code-architecture.md) 의 「사용자와 profile, 에이전트, 대화」 가 갖는다.

## 지켜야 할 것

- **이 저장소는 공개 저장소다. 홈서버의 운영 정보를 적지 않는다.** 아래 「공개 저장소」 를 본다.
- Hermes core 를 고치지 않는다. profile, API server, plugin hook 만 쓴다.
  고쳐야 할 것 같으면 ADR-001 의 검토 순서를 따른다.
- 비밀값을 데이터베이스에 넣지 않는다. profile key 는 홈서버 파일에 둔다.
- 실행할 profile 은 요청자의 바인딩에서만 꺼낸다. 요청 본문이 profile 을 정하지 못한다.
- Memory 접근 권한은 Control Plane 이 정한다. Hermes 내장 memory 도구는 주지 않는다.
  제목만 주입한 항목은 Control Plane 이 응답을 고르는 MCP 도구로만 읽는다.
  실행 밖의 서비스는 사용자에 묶인 서비스 토큰으로 문서만 읽는다(ADR-056).
- 실행 기록은 실패해도 남긴다.

## 공개 저장소

이 저장소는 누구나 읽는다.

**아래를 어느 파일에도 적지 않는다.** 코드, 문서, `tasks/`, 커밋 메시지, PR 본문이 모두 해당한다.

- 홈서버의 주소와 계정
- 우리가 정한 포트 번호
- 우리가 이름을 정한 컨테이너와 Docker 네트워크
- 컨테이너 안의 마운트 경로와 홈서버의 디렉터리 경로
- 애플리케이션 데이터베이스 이름과 접속 방법
- key 나 토큰을 꺼내는 명령
- 그것들을 조합해 홈서버에서 무언가를 돌리는 명령

key 값 자체를 적지 않는 것은 당연하고, **그것이 어디 있고 어떻게 꺼내는지도 적지 않는다.**

**이 목록에 값을 예시로 적지 않는다.**
무엇을 감추는지 설명하려고 그 값을 적으면 감추려던 것이 공개된다.
종류만 적고 실제 값은 `fos-home-infra` 의 목록이 갖는다.

대신 이렇게 쓴다.

- 무엇을 확인해야 하는지만 적고, 실행 방법은 `fos-home-infra` 를 가리킨다
- 측정한 **결과 수치**는 적어도 된다. 그것을 얻은 명령을 적지 않는 것이다
- 조사 기록은 저장소 밖에 둔다. `.omc/` 는 `.gitignore` 에 있으므로 그 아래도 괜찮다

### 주제가 아니라 내용으로 나눈다

**Hermes 가 어떻게 동작하는지는 이 저장소가 소유한다.**
[`hermes/docs/hermes-contract.md`](hermes/docs/hermes-contract.md) 가 그 자리다.
우리 환경의 값만 비공개 저장소로 간다.

| 성격 | 어디 | 예 |
| --- | --- | --- |
| Hermes 의 동작 계약 | 이 저장소 | 어느 응답의 어느 칸이 무엇을 뜻하는가 |
| 우리 환경의 값 | `fos-home-infra` | 포트, 컨테이너 이름, 경로, 계정 상태 |

`/v1/runs` 가 무엇을 받는지에는 비밀이 없다.
그것을 비공개에 두면 이 저장소를 고치는 사람이 근거를 보지 못한다.

## tasks 는 구현 문서만 담는다

`tasks/` 는 이 대화를 보지 못한 구현자가 읽고 실행하는 곳이다.
**구현이 끝난 계획서는 그 구현 PR 에서 지운다.**
남겨 두면 다음에 읽는 사람이 끝난 것과 앞으로 할 것을 구분하지 못하고,
이미 바뀐 설계를 그대로 읽는다.
오래 남을 것은 지우기 전에 `docs/` 나 ADR 로 옮긴다.

조사를 phase 로 만들지 않는다.
조사 절차를 phase 에 적으면 구현 문서에 홈서버 접근 명령이 섞이고,
그 phase 가 끝나도 결론이 어디에 남는지 정해지지 않는다.

다른 저장소를 고치는 조사는 워커에 맡기고, 조회와 로그 확인은 현재 세션에서 한다. 그 결과를 ADR 이나 `docs/` 에 남긴다.
그 결론이 나온 뒤에 구현 계획을 세운다.

`docs/` 와 코드는 계획서를 번호로 가리키지 않는다. 계획서는 지워지고 지난 계획은 git 이력에서 찾는다.

## 확인

### 마이그레이션 버전과 ADR 식별자

새 마이그레이션의 버전 형식과 합칠 때의 규칙은 [`backend/docs/data-schema.md`](backend/docs/data-schema.md) 의 「마이그레이션 작성 규칙」 이 갖는다.

새 ADR 은 그 결정을 지키는 코드가 있는 모듈의 `docs/adr/ADR-<YYYYMMDD>-<슬러그>.md` 로 만든다.
backend 는 `backend/docs/adr/`, 화면은 `web/docs/adr/`, `hermes/` 는 `hermes/docs/adr/` 이고, 여러 모듈에 걸치면 루트 `docs/adr/` 다.
기존 숫자 ADR 은 그대로 두며, 같은 날의 새 ADR 은 슬러그로 구분한다.
둘 곳과 제목, 링크, 목록 정렬은 [`docs/adr/INDEX.md`](docs/adr/INDEX.md) 의 규칙을 따른다.

### 로컬 검사와 PR

push 전에 `scripts/check-local.sh` 로 로컬 검사를 돌린다.
고친 화면과 그 컴포넌트를 쓰는 화면의 spec 을 인자로 준다. 인자의 뜻과 돌리는 명령은 그 스크립트의 머리말이 갖는다.

```bash
# cwd: 저장소 root
scripts/check-local.sh usage-breakdown memory-document
```

**PR 을 열거나 Ready 로 바꾸거나 머지할 때는 [`.claude/skills/pr-merge/SKILL.md`](.claude/skills/pr-merge/SKILL.md) 를 읽는다.**
Draft CI, main 합치기, 리뷰 반영, 머지 판정의 순서를 그 파일이 갖는다.

공개 정보 검사의 값 목록은 repository secret `PUBLIC_REPO_DENYLIST` 다. `fos-home-infra` 의 목록이 바뀌면 secret 도 다시 넣는다.

## 머지는 PR 로 한다

한 PR은 관심사 하나만 담는다. 리팩터링은 이동만 하는 커밋과 동작을 바꾸는 커밋을 나눈다.
운영 코드의 변경 줄 상한은 `scripts/pr-size.mjs` 가 정한다. 넘으면 설계와 구현을 함께 담은 단계별 PR로 나눈다.
`규모:예외` 라벨은 사람이나 코디네이터만 붙인다. 구현 워커는 스스로 붙이지 않는다.

Draft PR 을 열어 올린다. main 에 로컬에서 바로 머지하지 않는다.
**계획서만으로 PR 을 열지 않는다. 예외는 없다.** ADR, 설계 문서, 계획서(`docs/`, `tasks/`)와 그 구현을 한 브랜치에서 끝낸 뒤 한 PR 로 올린다.
구현이 여러 단계로 나뉘어도 설계만 먼저 머지하지 않는다. 한 PR 이 너무 커지면 구현을 단계별 PR 로 나누되, 각 PR 이 그 단계의 설계와 구현을 함께 담는다.

**Claude 리뷰는 한 번 반영하고 머지한다. 고친 뒤 `/review` 로 리뷰를 다시 돌리지 않는다.**
리뷰 기준은 `.github/workflows/code-review-prompt.txt` 가, 등급마다 무엇을 할지는 `pr-merge` 스킬의 「4. 리뷰 반영」 이 갖는다.

`scripts/pr-risk-labels.sh` 가 위험 라벨을 단다. 2026-10-11 까지는 라벨만 달고 머지 규칙은 바꾸지 않는다.
그 뒤 라벨이 실제 위험과 맞았는지 보고, 라벨이 붙은 PR 의 머지 전에 사람 확인을 받을지 정한다.

## 커밋

한국어로 쓴다.
제목은 `<type>(<범위>): <메시지>` 형식을 쓰고 범위는 `backend`, `web`, `hermes`, `docs`, `infra` 중 하나다.
변경 대상과 달라진 동작을 함께 적는다.

## 코드 주석은 한국어로 쓴다

이 저장소를 읽는 사람이 한국어 사용자다.
주석과 Javadoc 을 한국어로 쓰고, 코드 식별자와 타입과 라이브러리 이름과 명령과 경로는 원문 그대로 둔다.
문서도 한국어로 쓴다. 예외는 하나다. 루트 `README.md` 만 영어로 쓰고 `README.ko.md` 와 같은 내용을 유지한다.

## 운영

운영 절차는 이 저장소가 갖지 않는다. 비공개 저장소 `fos-home-infra` 가 소유한다.
배포와 확인, Hermes profile 과 스킬 연결이 모두 그쪽에 있다.

그 저장소의 디렉터리 구조도 「공개 저장소」 절에 따라 적지 않는다. 어디를 볼지는 워커에게 지시문으로 준다.

### Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다

배포 확인 항목은 운영 저장소가 갖는다.

**테스트가 모두 통과해도 운영에서 동작하지 않을 수 있다.**
가짜 Hermes 가 실제와 다른 형태를 보내도록 쓰여 있으면 테스트는 통과한다.
Hermes 와 주고받는 것을 바꿨으면 배포한 뒤 실제 실행을 한 번 왕복시켜 본다.
