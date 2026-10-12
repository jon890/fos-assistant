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
   이 job은 공개 정보 검사와 checkout을 공유하고 필수 검사 이름 `public-safe`로 보고한다.
   규칙은 `scripts/ci/ci-paths.mjs` 의 `RULES` 가 갖는다. 위에서부터 처음 맞는 규칙 하나만 쓴다.
   바뀐 파일은 PR merge 커밋과 그 첫 부모(base)의 차이다. 이름을 바꾼 파일은 옛 경로와 새 경로를 모두 본다.
2. 대상은 `backend`, `web`, `browser`, `e2e`, `hermes` 와 quality 의 무거운 단계다.
   `unit`, `public-safe` 와 quality 의 파일 길이 검사는 언제나 돈다. 문서를 읽는 테스트가 unit 에 있어서다.
3. 규칙은 보수적으로 둔다.
   - 어느 규칙에도 맞지 않는 파일은 모든 job 을 돌린다. 새 디렉터리와 루트 설정 파일이 여기 든다.
     알 수 없는 새 스크립트도 전체 검사를 돌린다. 간접 호출 여부를 모르는 상태에서 브라우저를 생략하지 않는다.
   - `ci.yml` 과 `ci-paths.mjs` 를 바꾸면 모든 job 을 돌린다.
   - 모듈 디렉터리 안의 파일은 설정과 의존 목록, Dockerfile 까지 그 모듈 코드로 본다.
     문서 디렉터리와 README·AGENTS 등 정해진 설명 문서만 건너뛴다. 낯선 Markdown 입력도 전체 검사를 돌린다.
     Hermes 계약 문서와 커넥터 README 존재 여부는 hermes 검사를, 실행에 주입하는 스킬 본문은 관련 검사를 돌린다.
   - 여러 job 이 함께 읽는 `test/support`, `test/fixtures`, `test/resources` 는 모든 job 을 돌린다.
     예외로 커넥터 인자 catalog 는 backend 와 hermes 계약 검사의 입력이므로 그 두 검사만 돌린다.
   - 바뀐 파일을 하나도 찾지 못하면 모든 job 을 돌린다.
   - 브라우저 shard 는 웹 코드와 설정, `test/browser`, Control Plane 코드와 빌드 설정, 공유 E2E 대역, 브라우저 실행 스크립트를 바꾸면 돌린다.
     backend 테스트 소스만 바꾸면 건너뛰되, 브라우저가 쓰는 `testsupport` 와 테스트 리소스는 브라우저도 돌린다.
     Hermes 코드만 바꾸면 브라우저는 건너뛰되, 커넥터 카드 검사가 읽는 manifest 와 아이콘은 브라우저도 돌린다.
     workflow 와 경로 판정 자체를 바꾸면 전체 검사를 돌린다.
4. 정기 실행과 수동 실행(`workflow_dispatch`)은 언제나 모든 job 을 돌린다. 규칙이 놓친 의존은 정기 실행에서 드러난다.
   main push 는 가벼운 검사만 돈다(아래 「main push 는 가벼운 검사만 돈다」).
5. `backend`, `web`, `e2e`, `hermes`와 폭별 브라우저 필수 검사 job은 결과를 판정한다.
   `web`은 quality에 합친 웹 단계의 결과를 받고, quality job 자체도 성공했는지 확인한다.
   규칙은 `ci-paths.mjs gate`가 갖는다.
   checkout 과 Node 설정을 각각 수행하고 `if: always()` 를 유지한다. 취소 때문에 판정까지 생략해 통과로 보고하지 않기 위해서다.
   - `changes` job 이 성공하지 않았으면 실패다. 판정 job 이 실패하면 뒤 job 이 생략되는데, 그것을 건너뛴 것으로 읽지 않으려는 것이다.
   - 돌려야 했던 job 은 `success` 만 통과다.
   - 건너뛰기로 한 job 은 `skipped` 만 통과다.
   필수 검사의 이름은 그대로라 브랜치 보호를 바꾸지 않는다.
6. merge queue 는 쓰지 않는다. main 이 움직였을 때는 `pr-merge` 스킬 5단계의 규칙을 그대로 쓴다.
   뒤에 들어온 main 커밋이 PR 과 겹치지 않고, 빌드 설정·공유 계약·마이그레이션·저장소 전체 검사도 바꾸지 않았을 때만 직전 CI 를 쓴다.

`test/unit/ci-paths.test.ts` 가 규칙의 대표 경우와 판정의 모든 조합을 확인한다.
`ci.yml` 의 job 이 부르는 스크립트가 그 job 을 돌리는 규칙에 있는지도 같은 파일이 확인한다.
실제 Git merge 를 만든 테스트로 첫 부모 기준 비교와 이름 변경을 확인하고, PR 밖 실행이 모든 검사를 고르는지도 확인한다.

### 대안 기각

- **workflow 의 `on.pull_request.paths`**: workflow 가 아예 돌지 않으면 필수 검사가 보고되지 않아 PR 이 머지되지 못한다.
- **job 의 `if` 만 두고 판정 job 없이 건너뛰기**: GitHub 은 건너뛴 job 을 통과로 본다.
  판정 job 이 실패해서 생략된 것과 규칙대로 건너뛴 것을 가리지 못한다. `matrix` job 은 건너뛰면 이름도 펼쳐지지 않는다.
- **`dorny/paths-filter` 같은 외부 action**: 규칙은 glob 으로 줄어들지만 「규칙에 없는 파일은 모두 돌린다」 를 표현하기 어렵다.
  테스트로 규칙을 확인하기도 어렵다. 외부 action 하나를 더 고정해 관리해야 한다.
- **merge queue**: 위 「맥락」 대로 개인 계정의 저장소에는 열리지 않는다.
  organization 으로 옮기면 쓸 수 있지만 저장소 주소와 secret, 연동이 모두 바뀐다.
- **auto-merge(`gh pr merge --auto`)**: 개인 계정의 공개 저장소에서도 쓸 수 있다.
  다만 브랜치 보호의 `strict` 를 끈 상태라 main 이 움직였는지 보지 않고 머지한다.
  5단계의 「뒤 커밋이 PR 파일을 건드렸는가」 판단을 대신하지 못해 지금은 켜지 않는다.

### 감수할 것

- 규칙이 실제 의존보다 좁으면 PR 에서 깨진 것을 다음 정기 실행에서야 본다. 새 job 입력을 만들면 `RULES` 를 함께 고친다.
- 브라우저 서버는 `smokeRun` 의 테스트 classpath 로 기동한다. 테스트 지원 API 와 리소스도 브라우저 입력으로 관리한다.
- 커넥터 카드 검사가 읽는 manifest 와 아이콘은 Hermes 안에 있어도 브라우저 입력으로 관리한다.
- PR CI 가 판정한 바뀐 파일은 그 CI 를 돌린 시점의 main 기준이다. 뒤에 main 이 움직여도 PR 이 바꾼 파일은 같으므로 판정은 그대로 쓴다.

### 경로 필터 적용 뒤 측정

2026-10-12의 성공한 PR CI 8개를 비교했다.
전체 시간은 run 생성부터 마지막 실제 job 종료까지다.
생략된 job은 개수에서 뺐고, 실행 시간은 job 시작부터 종료까지다.
대기 시간은 GitHub jobs API의 `created_at`부터 `started_at`까지다.
dependency가 끝날 때 job이 생성되는지에 따라 대기값에 dependency 대기가 섞일 수 있어, 동시 실행 제한만의 효과로 해석하지 않는다.
서로 다른 코드와 캐시, 동시 PR 수를 가진 표본이므로 인과 비교나 속도 보장은 아니다.

| 구분 | PR 작업 | run | 실제 job | 전체(초) | job 대기 중앙값 / 최대(초) |
| --- | --- | --- | ---: | ---: | ---: |
| 필터 전 | 커넥터 문구 | [38216495294](https://github.com/jon890/fos-assistant/actions/runs/38216495294) | 19 | 544 | 3 / 4 |
| 필터 전 | 원고 저장 | [38222761839](https://github.com/jon890/fos-assistant/actions/runs/38222761839) | 19 | 1162 | 341 / 636 |
| 필터 전 | 금융 producer | [38224658297](https://github.com/jon890/fos-assistant/actions/runs/38224658297) | 19 | 1256 | 562 / 1016 |
| 필터 후 | 원고 저장 | [38227751292](https://github.com/jon890/fos-assistant/actions/runs/38227751292) | 14 | 767 | 99 / 214 |
| 필터 후 | 금융 producer | [38228561356](https://github.com/jon890/fos-assistant/actions/runs/38228561356) | 23 | 924 | 103 / 234 |
| 필터 후 | 재시작 회귀 | [38230397120](https://github.com/jon890/fos-assistant/actions/runs/38230397120) | 23 | 1179 | 112 / 307 |
| 필터 후 | Hermes 대역 | [38231630983](https://github.com/jon890/fos-assistant/actions/runs/38231630983) | 22 | 865 | 32 / 445 |
| 필터 후 | PR 절차 문서 | [38231814367](https://github.com/jon890/fos-assistant/actions/runs/38231814367) | 10 | 458 | 295 / 377 |

동일 이름의 job별 중앙값을 비교했다.
browser 행은 shard별 중앙값 중 최솟값과 최댓값이다.
필터 뒤 web, e2e, hermes는 판정 job이고 실제 작업은 `*-build`, `*-tests`로 옮겨졌다.

| job | 필터 전 실행 / 대기(초) | 필터 후 실행 / 대기(초) |
| --- | ---: | ---: |
| changes | 없음 | 12 / 56 |
| public-safe | 8 / 619 | 7 / 80 |
| unit | 44 / 428 | 44 / 30 |
| quality | 160 / 156 | 164 / 70 |
| backend-unit | 414 / 362 | 423.5 / 15 |
| backend-mysql | 467 / 90 | 550.5 / 178.5 |
| backend | 3 / 87 | 11 / 81 |
| web | 58 / 606 | 10 / 233 |
| web-build | 없음 | 51 / 118.5 |
| e2e | 396 / 207 | 12 / 25 |
| e2e-tests | 없음 | 404 / 105 |
| hermes | 490 / 427 | 12 / 93 |
| hermes-tests | 없음 | 478 / 69.5 |
| browser-mobile-shard-* | 337–421 / 279–356 | 315–426 / 32–201 |
| browser-desktop-shard-* | 311–449 / 340–466 | 337–370 / 37–182 |
| browser-mobile | 12 / 3 | 12 / 130 |
| browser-desktop | 12 / 3 | 13 / 109 |

필터는 문서와 원고 저장 PR에서 실제 job을 줄였다.
전체 검사를 고르면 판정 job이 늘어 19개에서 23개가 됐고, 문서 PR조차 짧은 판정 job을 기다려 458초가 걸렸다.

### 짧은 job 통합과 shard 조정

`changes`는 실제 판정이 0초이고 checkout과 Node 준비가 6–7초였다.
`public-safe`는 실제 검사가 약 2초이고 checkout이 2초였다.
두 작업을 `changes` 하나에 합치고 필수 이름 `public-safe`로 보고한다.
공개 정보 검사에 실패하면 경로 판정의 job도 실패하므로 뒤의 필수 판정이 통과하지 않는다.

웹 검사는 테스트 3–5초, typecheck 6–7초, 빌드 18–22초였고 설치가 약 14–16초였다.
웹 전체 작업이 설치보다 길지만 개별 짧은 검사보다 설치가 길고, quality가 같은 pnpm 설치를 반복하고 있어 합친다.
quality만 필요한 경우와 웹만 필요한 경우를 구분해 Java는 quality에만, pnpm은 둘 중 하나가 필요하면 설치한다.
웹 단계가 생략되면 `skipped`, 실행되면 실제 `outcome`을 web 판정에 전달한다.
pnpm 설치가 성공하고 실행이 취소되지 않았으면, 앞의 quality 검사에 실패해도 웹 검사를 실행한다.
quality 실패와 취소도 web 판정의 실패가 된다.

backend 두 suite와 E2E, Hermes는 실제 검사가 수백 초라 독립 병렬 실행을 유지한다.
브라우저 shard는 설치가 약 40–55초, 본 검사가 약 247–438초였다.
폭마다 2개로 줄이면 검사량이 두 배가 되어 전체 시간을 늘릴 가능성이 커, 3개로 줄인다.
분산이 같다면 각 shard 검사량은 약 4/3배가 된다.
본 검사와 안정성 반복, 폭별 결과 판정은 모두 3개를 기준으로 한다.
테스트 개수와 재시도, 변경 spec 반복 횟수는 줄이지 않는다.

| 경우 | 통합 전 실제 job | 통합 후 실제 job | 예상 실행 시간 |
| --- | ---: | ---: | --- |
| 문서 PR | 10 | 9 | 초기 공개 정보 검사 약 15초, unit 약 44초, 짧은 판정 각각 약 10–14초 |
| 원고 저장 표본과 같은 경로 | 14 | 13 | backend, E2E, Hermes의 병렬 검사 시간 유지 |
| 전체 검사 PR | 23 | 19 | 웹과 quality 약 170–210초, 브라우저 shard 약 370–640초 |

예상 시간은 설치 공유와 검사량 비례로 계산한 값이며 대기 시간을 제외한다.
실제 전체 시간은 캐시, shard 분배, 안정성 반복과 다른 PR의 실행량에 따라 달라진다.
필수 검사 이름 9개는 바꾸지 않고 브랜치 보호도 바꾸지 않는다.
workflow 회귀 검사는 필수 이름과 job 수, 경로별 설치, 웹 단계의 실패·취소·생략 전파, shard 분모를 확인한다.

### 통합 PR의 실제 CI 결과

[통합 PR CI 38235708766](https://github.com/jon890/fos-assistant/actions/runs/38235708766)은 실제 job 19개와 필수 검사 9개가 모두 통과했다.
생략된 `browser-failures`는 실제 job 수에서 뺐다.
전체 시간은 2030초, job 대기 중앙값은 276초, 최대 대기는 1020초였다.
통합한 quality는 201초였고, 그 안의 웹 테스트와 typecheck, 빌드는 36초였다.

| 폭 | shard 1 실행(초) | shard 2 실행(초) | shard 3 실행(초) |
| --- | ---: | ---: | ---: |
| mobile | 361 | 408 | 444 |
| desktop | 468 | 420 | 368 |

폭마다 3개인 실제 검사는 최장 468초로 끝났다.
job 수가 23개에서 19개로 줄어든 것은 확인했지만, 이 표본의 전체 시간은 앞의 표본보다 길었다.
동시 PR과 main CI의 실행량, 캐시와 코드가 달라 전체 시간 단축이나 대기 감소를 이 결과로 입증할 수 없다.
짧은 최종 판정도 runner를 기다렸으므로, 실행 시간과 대기 시간을 나눠 계속 판단한다.

### main push 는 가벼운 검사만 돈다

GitHub Free 의 동시 job 20개를 PR CI 와 main push CI 가 나눠 쓴다.
머지마다 도는 main push 의 전체 검사가 job 약 20개를 차지해, 이어진 PR CI 가 줄을 섰다. main push run 하나가 34분 대기한 것을 실측했다.

main 에 들어오는 커밋은 최신 main 을 포함한 head 로 PR CI 를 통과했거나 `pr-merge` 스킬 5단계의 예외를 만족한 것이다.
같은 검사를 push 에서 다시 돌리는 가치가 낮아 다음과 같이 나눈다.

| 실행 | 도는 것 | 실제 job 수 |
| --- | --- | ---: |
| main push | `public-safe`, `unit`, `quality`(웹 테스트·typecheck·빌드 포함)와 판정 job 6개 | 9 |
| 정기 실행, 수동 실행 | 모든 검사 | 전체 검사 PR 과 같다 |

- 판정 job 은 `backend`, `e2e`, `hermes`, `browser-*` 가 생략되면 통과로 보고하는 짧은 job 이다. push 의 실제 job 은 전체 검사 때의 19개에서 9개로 줄고, 그중 오래 도는 것은 quality 하나다.
- 정기 실행은 하루 한 번(한국 시간 04:23)으로 둔다. 전체 검사가 한 번 도는 데 30분 안팎이 걸리고, 이 시간대는 PR 이 거의 없어 PR CI 와 한도를 다투지 않는다.
  push 에서 뺀 검사가 깨지면 늦어도 하루 안에 드러난다. 더 자주 돌릴 이유는 아직 없다.
- 필수 검사 이름 9개는 PR 에서 그대로 결과를 낸다. main push 는 필수 검사 대상이 아니므로 브랜치 보호는 바꾸지 않는다.
- 정기 실행이 실패하면 실패한 run 이 Actions 에 남고, 브라우저 실패는 기존 `browser-failures` job 이 이슈로 모은다.
  백엔드, e2e, Hermes 실패에 이슈를 따로 열지는 않는다. 새 외부 연동을 만들지 않는다는 제약을 지키고, 이슈 자동화는 같은 실패가 반복된 것을 본 뒤에 더한다.
  이 job 은 정기 실행과 수동 실행의 main 실패만 모은다. push 는 browser 를 돌리지 않으므로 대상에서 뺐다.

되돌릴 때는 `scripts/ci/ci-paths.mjs` 의 `PUSH_JOBS` 와 `ci.yml` 의 `browser-failures` 조건을 함께 고친다.

### PR 은 브라우저와 E2E 를 정기 실행으로 미룬다

main push 를 줄인 뒤에도 PR CI 하나가 30분 안팎 걸렸고, 그중 브라우저 shard 6개와 E2E 가 가장 길었다.
PR 이 여러 개 열리면 동시 job 20개를 이 검사들이 먼저 채워 다른 PR 의 CI 가 줄을 섰다.
구현, 확인, 머지, 배포를 빨리 돌리려고 PR 에는 코드 단위의 빠른 검사만 남긴다(2026-10-12 사용자 결정).

| 실행 | 브라우저 shard, E2E |
| --- | --- |
| PR | 기본으로 건너뛴다. 판정 job 은 「대상 아님」 으로 통과한다 |
| PR 에 `검사:전체` 라벨 | 라벨을 붙인 뒤의 다음 push 부터 경로 판정대로 돈다 |
| 정기 실행(하루 한 번), 수동 실행 | 모두 돈다 |

- 필수 검사 이름 9개는 그대로 두고 브랜치 보호는 바꾸지 않는다. 건너뛴 까닭은 changes job 의 요약 표에 남는다.
- 감수할 것: 화면과 E2E 회귀가 PR 에서 바로 드러나지 않고, 늦어도 다음 정기 실행에서 드러난다. 화면 흐름을 크게 바꾸는 PR 은 작성자나 코디네이터가 `검사:전체` 라벨을 붙인다.
- `labeled` 이벤트로는 CI 를 돌리지 않는다. `pr-size` workflow 가 PR 마다 규모 라벨을 붙여서, 받으면 진행 중인 CI 가 취소되고 처음부터 다시 돈다. 라벨만 붙이고 바로 돌려야 하면 빈 커밋을 push 한다. run 을 다시 실행하면 처음 이벤트의 라벨 목록을 써서 새 라벨이 보이지 않는다.
- 라벨이 붙어 있는 동안에는 이후 push 마다 브라우저와 E2E 가 돈다. 확인이 끝나면 라벨을 뗀다.
- 되돌릴 때는 `scripts/ci/ci-paths.mjs` 의 `withoutSlow` 호출을 빼고 `ci.yml` 의 `FULL_LABEL` 을 지운다.
