## ADR-20261012 / ci-path-filter: PR CI 는 바뀐 파일이 입력인 job 만 돌리고, 필수 검사는 건너뛴 까닭까지 판정한다

- **status**: `accepted`
- Date: 2026-10-12

### 맥락

PR 마다 CI 전체가 돈다. backend 두 suite, web, E2E, hermes, quality 와 브라우저 shard 여덟 개다.
문서만 바꾼 PR 도 같다. GitHub Free 의 동시 job 은 20개라 PR 두세 개가 함께 돌면 서로 기다린다.
main 이 움직일 때마다 다시 돌리는 일까지 겹쳐 머지가 밀린다.

GitHub merge queue 로 다시 돌리는 횟수를 줄이는 방법도 봤다.
GitHub 문서는 merge queue 를 organization 이 소유한 공개 저장소와 GitHub Enterprise Cloud 의 organization 비공개 저장소에만 연다.
이 저장소는 개인 계정이 소유한 공개 저장소라 쓸 수 없다.

### 결정

1. CI 의 `changes` job 이 PR 의 바뀐 파일로 job 마다 돌릴지를 정한다.
   규칙은 `scripts/ci/ci-paths.mjs` 의 `RULES` 가 갖는다. 위에서부터 처음 맞는 규칙 하나만 쓴다.
   바뀐 파일은 PR merge 커밋과 그 첫 부모(base)의 차이다. 이름을 바꾼 파일은 옛 경로와 새 경로를 모두 본다.
2. 대상은 `backend`, `web`, `browser`, `e2e`, `hermes` 와 quality 의 무거운 단계다.
   `unit`, `public-safe` 와 quality 의 파일 길이 검사는 언제나 돈다. 문서를 읽는 테스트가 unit 에 있어서다.
3. 규칙은 보수적으로 둔다.
   - 어느 규칙에도 맞지 않는 파일은 모든 job 을 돌린다. 새 디렉터리와 루트 설정 파일이 여기 든다.
   - `ci.yml` 과 `ci-paths.mjs` 를 바꾸면 모든 job 을 돌린다.
   - 모듈 디렉터리 안의 파일은 설정과 의존 목록, Dockerfile 까지 그 모듈 코드로 본다. 건너뛰는 것은 모듈의 `docs/` 와 모듈 바로 아래의 `.md` 뿐이다.
   - 여러 job 이 함께 읽는 `test/support`, `test/fixtures`, `test/resources` 는 모든 job 을 돌린다.
   - 바뀐 파일을 하나도 찾지 못하면 모든 job 을 돌린다.
4. push 와 정기 실행은 언제나 모든 job 을 돌린다. 규칙이 놓친 의존은 main 에서 드러난다.
5. 필수 검사 이름의 job 은 판정만 한다. 규칙은 `ci-paths.mjs gate` 가 갖는다.
   - `changes` job 이 성공하지 않았으면 실패다. 판정 job 이 실패하면 뒤 job 이 생략되는데, 그것을 건너뛴 것으로 읽지 않으려는 것이다.
   - 돌려야 했던 job 은 `success` 만 통과다.
   - 건너뛰기로 한 job 은 `skipped` 만 통과다.
   필수 검사의 이름은 그대로라 브랜치 보호를 바꾸지 않는다.
6. merge queue 는 쓰지 않는다. main 이 움직였을 때는 `pr-merge` 스킬 5단계의 규칙을 그대로 쓴다.
   뒤에 들어온 main 커밋이 PR 의 파일을 건드리지 않았으면 직전 CI 로 머지한다.

`test/unit/ci-paths.test.ts` 가 규칙의 대표 경우와 판정의 모든 조합을 확인한다.
`ci.yml` 의 job 이 부르는 스크립트가 그 job 을 돌리는 규칙에 있는지도 같은 파일이 확인한다.

### 대안 기각

- **workflow 의 `on.pull_request.paths`**: workflow 가 아예 돌지 않으면 필수 검사가 보고되지 않아 PR 이 머지되지 못한다.
- **job 의 `if` 만 두고 판정 job 없이 건너뛰기**: GitHub 은 건너뛴 job 을 통과로 본다.
  판정 job 이 실패해서 생략된 것과 규칙대로 건너뛴 것을 가리지 못한다. matrix job 은 건너뛰면 이름도 펼쳐지지 않는다.
- **`dorny/paths-filter` 같은 외부 action**: 규칙은 glob 으로 줄어들지만 「규칙에 없는 파일은 모두 돌린다」 를 표현하기 어렵다.
  테스트로 규칙을 확인하기도 어렵다. 외부 action 하나를 더 고정해 관리해야 한다.
- **merge queue**: 위 「맥락」 대로 개인 계정의 저장소에는 열리지 않는다.
  organization 으로 옮기면 쓸 수 있지만 저장소 주소와 secret, 연동이 모두 바뀐다.
- **auto-merge(`gh pr merge --auto`)**: 개인 계정의 공개 저장소에서도 쓸 수 있다.
  다만 브랜치 보호의 `strict` 를 끈 상태라 main 이 움직였는지 보지 않고 머지한다.
  5단계의 「뒤 커밋이 PR 파일을 건드렸는가」 판단을 대신하지 못해 지금은 켜지 않는다.

### 감수할 것

- 규칙이 실제 의존보다 좁으면 PR 에서 깨진 것을 main push 에서야 본다. 새 job 입력을 만들면 `RULES` 를 함께 고친다.
- PR CI 가 판정한 바뀐 파일은 그 CI 를 돌린 시점의 main 기준이다. 뒤에 main 이 움직여도 PR 이 바꾼 파일은 같으므로 판정은 그대로 쓴다.
