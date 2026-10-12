# scripts

스크립트는 부르는 쪽에 따라 폴더를 나눈다. 스크립트마다 하는 일과 사용법은 각 파일의 머리말이 갖는다.

- `dev/`: 사람과 에이전트가 직접 돌리는 입구다. push 전 로컬 검사와 머지 뒤 작업 정리를 맡는다.
- `checks/`: 개별 검사와 품질 검사 보조 도구를 둔다. CI 와 사람이 직접 부르거나 `dev/check-local.sh` 가 묶어서 부른다.
- `ci/`: CI job 만 부른다. 브라우저 검사의 shard 와 재시도, PR 에서만 도는 검사처럼 CI 환경을 전제로 한다.
- `pr/`: PR 의 규모와 위험 라벨, Claude 리뷰 workflow 가 부른다.

새 스크립트는 누가 부르는지를 보고 위 폴더 중 하나에 둔다.
스크립트 안에서 저장소 root 를 자기 위치로 구할 때는 두 단계 위(`../..`)다.

`checks/check-hermes-contract.sh` 는 CI 와 사람이 직접 부르며 로컬 검사에는 들어 있지 않다.
`checks/format-connectors.mjs` 와 `checks/quality-report.mjs` 는 `checks/quality.sh` 가 부른다.
`ci/check-connector-package.sh` 는 패키지 검사 중 `checks/check-connectors.sh` 를 부른다.
