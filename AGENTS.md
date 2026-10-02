# AGENTS.md

가족용 AI 비서다.
Hermes Agent 를 Agent Runtime 으로 두고 이 저장소는 Control Plane 과 웹, Hermes 에 설치하는 plugin 과 profile 틀을 맡는다.

## 읽기 순서

| 문서 | 언제 보는지 |
| --- | --- |
| [`docs/README.md`](docs/README.md) | 문서 전체의 색인. 제품 범위, 경계, Hermes 연동, ADR 을 여기서 찾는다 |
| [`backend/AGENTS.md`](backend/AGENTS.md) | Control Plane 을 고칠 때 |
| [`web/AGENTS.md`](web/AGENTS.md) | 화면을 고칠 때 |

## 용어

**한 낱말이 한 가지만 가리키게 한다.**
`구성원` 이 사람을 뜻하는지 권한 등급을 뜻하는지 갈려 실제로 혼란이 있었다.

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

`MEMBER` 는 코드의 값이므로 그대로 쓰되, 사람을 가리킬 때는 쓰지 않는다.

**이미 적용된 마이그레이션 파일은 용어를 바꾸려고 고치지 않는다.** Flyway 가 주석까지 체크섬에 넣어 운영 기동이 멈춘다. `test/unit/migration-immutable.test.ts` 가 막는다.

층이 넷이고 서로 다르다.

```
사용자 (app_user)
 └ 그 사용자의 profile 들
     └ 각 profile 을 가리키는 에이전트 (agent 표)
         └ 그 에이전트로 시작한 대화 (conversation)
```

**한 사용자가 profile 을 여럿 가질 수 있다.**
기본 profile 하나에 역할 profile 을 더한다.
둘은 만드는 방법이 달라 설정도 다르다.
`fos-home-infra` 가 그 차이를 소유한다.

## 지켜야 할 것

- **이 저장소는 공개 저장소다. 홈서버의 운영 정보를 적지 않는다.** 아래 「공개 저장소」 를 본다.
- Hermes core 를 고치지 않는다. profile, API server, plugin hook 만 쓴다.
  고쳐야 할 것 같으면 ADR-001 의 검토 순서를 따른다.
- Hermes 에 설치하는 plugin 과 profile 틀은 `hermes/` 가 갖는다. 운영 값은 설치할 때 받고 코드에 두지 않는다.
- 비밀값을 데이터베이스에 넣지 않는다. profile key 는 홈서버 파일에 둔다.
- 실행할 profile 은 요청자의 바인딩에서만 꺼낸다. 요청 본문이 profile 을 정하지 못한다.
- Memory 접근 권한은 Control Plane 이 정한다. Hermes 내장 memory 도구는 주지 않는다.
  제목만 주입한 항목은 Control Plane 이 응답을 고르는 MCP 도구로만 읽는다.
  실행 밖의 서비스는 사용자에 묶인 서비스 토큰으로 문서만 읽는다(ADR-056).
- 실행 기록은 실패해도 남긴다.

## 공개 저장소

이 저장소는 누구나 읽는다.
Hermes 를 쉽게 쓰는 화면으로 공개하는 것을 검토하고 있어 앞으로도 공개로 둔다.

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
이 문서에서 실제로 그 실수를 두 번 했다.

대신 이렇게 쓴다.

- 무엇을 확인해야 하는지만 적고, 실행 방법은 `fos-home-infra` 를 가리킨다
- 측정한 **결과 수치**는 적어도 된다. 그것을 얻은 명령을 적지 않는 것이다
- 조사 기록은 저장소 밖에 둔다. `.omc/` 는 `.gitignore` 에 있으므로 그 아래도 괜찮다

### 주제가 아니라 내용으로 나눈다

**Hermes 가 어떻게 동작하는지는 이 저장소가 소유한다.**
[`docs/hermes/README.md`](docs/hermes/README.md) 가 그 자리다.
우리 환경의 값만 비공개 저장소로 간다.

| 성격 | 어디 | 예 |
| --- | --- | --- |
| Hermes 의 동작 계약 | 이 저장소 | 어느 응답의 어느 칸이 무엇을 뜻하는가 |
| 우리 환경의 값 | `fos-home-infra` | 포트, 컨테이너 이름, 경로, 계정 상태 |

`/v1/runs` 가 무엇을 받는지에는 비밀이 없다.
그것을 비공개에 두면 이 저장소를 고치는 사람이 근거를 보지 못한다.

**실제로 그 실수를 했다.** 조사를 맡기며 「공개 저장소니 거기 적지 마라」고만 말해
동작 지식까지 비공개로 갔고, 계획서가 읽을 수 없는 문서를 근거로 가리켰다.

내보내기 전에 검사한다.

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## tasks 는 구현 문서만 담는다

`tasks/` 는 이 대화를 보지 못한 구현자가 읽고 실행하는 곳이다.
**구현이 끝난 계획서는 지운다.**
남겨 두면 다음에 읽는 사람이 끝난 것과 앞으로 할 것을 구분하지 못하고,
이미 바뀐 설계를 그대로 읽는다.
오래 남을 것은 지우기 전에 `docs/` 나 ADR 로 옮긴다.

조사를 phase 로 만들지 않는다.
조사 절차를 phase 에 적으면 구현 문서에 홈서버 접근 명령이 섞이고,
그 phase 가 끝나도 결론이 어디에 남는지 정해지지 않는다.

조사는 `orchestration` 으로 워커에 직접 맡기고, 그 결과를 ADR 이나 `docs/` 에 남긴다.
그 결론이 나온 뒤에 구현 계획을 세운다.

`docs/` 와 코드는 계획서를 번호로 가리키지 않는다. 계획서는 지워지고 지난 계획은 git 이력에서 찾는다.

## 확인

아래 검사를 한 번에 돌린다.

```bash
# cwd: 저장소 root
scripts/check-local.sh
```

처음 받은 checkout 에서도 그대로 돈다.
처음 실패한 단계에서 멈추고 그 로그의 끝을 보인다.

`scripts/quality.sh check` 는 파일을 바꾸지 않고 검사하고, `fix` 는 기계가 고칠 수 있는 위반만 고친다. 새 위반은 기준에 더하지 않는다.
자세한 것은 [`backend/AGENTS.md`](backend/AGENTS.md) 와 [`web/AGENTS.md`](web/AGENTS.md) 에 있다.

스크립트가 차례로 돌리는 명령은 아래와 같다.

```bash
cd backend && ./gradlew test
scripts/check-mysql-migration.sh
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
python3 -m unittest discover -s hermes/tests
scripts/check-public-safe.sh
scripts/quality.sh check
```

**위 검사가 모두 통과하면 머지한다. 머지마다 승인을 받지 않는다.**
다만 통과를 **직접 돌려 확인한 것**이라야 한다. 브라우저 검사만은 PR 의 CI(`browser-mobile`, `browser-desktop`)가 통과한 것을 확인으로 본다.
로컬에서는 고친 화면과 관련된 spec 만 돌린다. 전체 브라우저 검사는 한 번에 10분 가까이 걸리고 여러 작업이 나란히 돌면 이 머신의 자원이 모자라 흔들린다.
워커의 보고를 읽는 것은 확인이 아니다.
실제로 워커가 통과했다고 보고한 것이 전체로 돌리니 실패한 적이 있다.

GitHub Actions 의 [CI](.github/workflows/ci.yml) 도 위 검사를 돌린다. job 구조와 shard, 매일 실행, 실패 이슈는 그 파일이 갖는다.
PR 에서는 대상 브랜치와 합친 결과인 merge ref 를 검사한다.
필수 검사 `browser-mobile` 과 `browser-desktop` 은 해당 폭의 shard 가 모두 성공해야 통과한다.
**브라우저 검사는 PR 에서도 CI 가 돌리고, 그 결과가 머지 전 확인이다.**
PR 실패는 그 PR 에서 고친다. 모인 실패 이슈는 고치거나 까닭을 적어 닫는다.

공개 정보 검사의 값 목록은 repository secret `PUBLIC_REPO_DENYLIST` 다. `fos-home-infra` 의 목록이 바뀌면 secret 도 다시 넣는다.

**위 명령을 적힌 순서대로 모두 돌린다.**

**브라우저 검사는 운영과 같은 빌드 결과를 띄워 검사한다.**
`pnpm test:browser` 가 웹 서버를 띄우기 전에 빌드하므로, 따로 빌드하지 않아도 옛 화면을 검사하지 않는다.
개발 서버는 링크를 미리 읽지 않아 운영과 다르게 움직이고, 개발 서버에서만 통과하던 검사가 실제로 있었다.
화면을 고치며 같은 검사를 되풀이할 때만 `BROWSER_WEB_SERVER=dev` 로 개발 서버를 띄운다.
머지 전 확인은 기본값으로 돌린다. 자세한 것은 [`web/AGENTS.md`](web/AGENTS.md) 에 있다.

디렉터리마다 걸리는 함정은 [`backend/AGENTS.md`](backend/AGENTS.md) 와
[`web/AGENTS.md`](web/AGENTS.md) 가 갖는다.
`gradlew` 의 위치와 `pnpm build` 가 요구하는 환경 변수가 거기 있다.

`scripts/check-mysql-migration.sh` 는 Docker 로 일회용 MySQL 8.4 를 띄워 마이그레이션과 스키마 검증, 저장소 쿼리를 실제 MySQL 에서 돌린다.
CI 에서는 `backend` job 이 함께 돌린다. 까닭은 [`docs/backend/schema/README.md`](docs/backend/schema/README.md) 의 「마이그레이션 작성 규칙」 에 있다.
저장소 쿼리 검사는 [`backend/AGENTS.md`](backend/AGENTS.md) 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」 에 있다.

`test/e2e` 는 Hermes 대역을 같은 프로세스에 띄워 홈서버 없이 전체 흐름을 검사한다.
시나리오는 `test/e2e/scenarios/` 에 하나씩 나뉘어 있고 `run.ts` 가 차례로 돌린다.
Node 의 TypeScript 실행을 쓰므로 설치할 의존성이 없다. 필요한 Node 버전은 `scripts/check-local.sh` 가 검사한다.

## 머지는 PR 로 한다

브랜치를 push 하고 PR 을 연다. main 에 로컬에서 바로 머지하지 않는다.
**계획서만으로 PR 을 열지 않는다.** 계획서(`docs/`, `tasks/`)와 그 구현을 한 브랜치에서 끝낸 뒤 한 PR 로 올린다.
예외는 하나다. 아래 셋이 모두 맞을 때만 ADR 과 계획서만 담은 PR 을 연다.

- 서로 기대는 구현 PR 이 둘 이상이다
- 그 PR 들이 함께 따르는 ADR 이나 계약을 먼저 정해야 뒤의 구현이 앞의 구현을 뒤집지 않는다
- 그 ADR 마다 `status` 와 `docs/adr/INDEX.md` 에 「아직 구현 전이다」 를 적는다. 구현한 PR 이 그 글을 지운다

이 예외로 먼저 들어온 계획서도 그 plan 을 구현한 PR 에서 지운다. 구현 순서는 계획서의 `README.md` 가 적는다.
구현이 하나뿐인 계획은 이 예외에 들지 않는다. 그 구현과 한 PR 로 올린다.

PR 의 merge ref 에서 CI 의 `backend`, `web`, `browser-mobile`, `browser-desktop`, `e2e`, `unit`, `hermes`, `public-safe`, `quality` 가 모두 통과했는지 확인한다.
main 은 브랜치 보호가 켜져 있고 위 job 이 필수 검사다.
PR 을 열면 `.github/workflows/claude-code-review.yml` 이 Claude 코드 리뷰를 돌린다.
리뷰 기준은 `.github/workflows/code-review-prompt.txt` 가 갖고, 그 파일은 이 문서와 `docs/` 를 가리킨다.
이 문서의 절 이름을 바꾸면 `.github/workflows/` 의 프롬프트 둘도 함께 고친다.

| 무엇 | 어떻게 |
| --- | --- |
| 리뷰를 다시 돌린다 | PR 에 `/review` 댓글을 단다. 저장소 주인과 멤버와 협업자의 댓글만 받는다 |
| 🔴 P1 치명이 남았다 | 머지하지 않는다. 고친 뒤 `/review` 로 다시 돌린다 |
| 🟠 P2 높음이 남았다 | 머지 전에 고친다. 이번에 고치지 않으면 그 까닭을 PR 에 한 줄 남긴다 |
| 🟡 P3 부터 ⚪ P5 까지만 남았다 | 반영할지 판단해 머지해도 된다 |
| 머지 방식 | `gh pr merge --merge`. 이력을 한 줄로 합치지 않는다 |

**리뷰가 통과했다고 「확인」 절의 검사를 건너뛰지 않는다.** 리뷰는 읽기만 하고 실행하지 않는다.
리뷰가 도는 동안 그 검사를 직접 돌리면 기다리는 시간이 겹친다.

PR 본문과 제목도 공개된다. 「공개 저장소」 절이 그대로 걸린다.

**위험 라벨은 바뀐 경로가 정한다.** `scripts/pr-risk-labels.sh` 가 마이그레이션, 보안, Hermes 연동, 배포 설정 경로를 보고 라벨을 단다.
리뷰는 라벨이 붙은 관점을 한 번 더 본다. 2026-10-11 까지는 라벨만 달고 머지 규칙은 바꾸지 않는다.
그 뒤 라벨이 실제 위험과 맞았는지 보고, 라벨이 붙은 PR 의 머지 전에 사람 확인을 받을지 정한다.

## 주간 점검

`.github/workflows/claude-architecture-audit.yml` 이 main 전체를 보고 찾은 것을 이슈로 연다.

점검 이슈는 결함 후보다. 고치기 전에 코드에서 사실인지 확인한다. 사실이 아니면 까닭을 적고 닫는다.

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

**그 저장소의 디렉터리 구조와 홈서버에 붙는 방법을 여기 적지 않는다.**
그것까지 적으면 비공개로 둔 뜻이 사라진다.
어디를 봐야 하는지는 그 저장소를 열면 알 수 있고,
워커에게는 지시문으로 준다.

무엇을 적지 않는지는 위의 「공개 저장소」 절이 정한다.

### Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다

배포 확인 항목은 운영 저장소가 갖는다.

**테스트가 모두 통과해도 운영에서 동작하지 않을 수 있다.**
가짜 Hermes 가 실제와 다른 형태를 보내도록 쓰여 있으면 테스트는 통과한다.
실제로 그렇게 스트리밍이 통째로 동작하지 않은 채 배포된 적이 있다.
Hermes 와 주고받는 것을 바꿨으면 배포한 뒤 실제 실행을 한 번 왕복시켜 본다.
