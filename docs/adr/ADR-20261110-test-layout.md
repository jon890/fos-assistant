## ADR-20261110 / test-layout: 테스트는 주인 모듈 안에 두고, 여러 모듈에 걸친 것만 루트 test/ 에 남긴다

- **status**: `accepted`
- Date: 2026-11-10

### 맥락

루트 `test/` 에 주인이 다른 테스트가 섞여 있었다.

- `test/web` 은 웹 API 라우트와 화면 함수를 확인하는데 웹의 `node_modules` 를 빌려 쓰고, `web/package.json` 의 `test:node` 가 돌렸다.
- `test/browser` 와 `test/screenshots` 는 웹을 띄워 화면을 테스트한다. `web/package.json` 이 `../test/browser/playwright.config.ts` 로 가리켰다.
- `test/unit` 에는 웹 순수 함수 테스트, `scripts/` 검사, 저장소 규칙 검사(문서 링크, 파일 배치, 마이그레이션 불변과 버전, ADR 과 코드 규칙 목록)가 한 폴더에 있었다.
- 그 결과 웹 코드를 고치는 사람이 테스트를 찾으려면 모듈 밖의 루트까지 올라가야 했고, 웹 테스트의 import 가 `../../web/src/...` 로 모듈 경계를 거슬렀다.
  `docs/code-architecture.md` 의 표도 `test/unit` 을 「화면이 쓰는 순수 함수」 로만 적어 실제와 달랐다.

### 결정

1. **테스트는 그 테스트가 확인하는 대상의 주인 모듈에 둔다.**

   | 새 자리 | 옛 자리 | 담는 것 |
   | --- | --- | --- |
   | `web/test/unit/` | `test/unit` 의 웹 순수 함수 테스트 | `web/src` 만 부르는 테스트 |
   | `web/test/node/` | `test/web` | 웹 API 라우트와 화면 함수. 웹의 `node_modules` 를 쓴다 |
   | `web/test/browser/` | `test/browser` | Playwright 화면 테스트와 그 설정, 서버 기동 코드 |
   | `web/test/screenshots/` | `test/screenshots` | 화면 둘러보기 스크린샷 |
   | `scripts/<폴더>/*.test.ts` | `test/unit` 의 스크립트 테스트 | 그 스크립트 옆. 스크립트 하나를 확인하는 파일은 그 스크립트가 있는 폴더에 둔다 |
   | `test/repo/` | `test/unit` 의 나머지 | 문서 링크와 파일 배치, 마이그레이션 불변과 버전, ADR 과 코드 규칙 목록처럼 여러 모듈의 파일을 읽는 저장소 규칙 |
   | `test/e2e/unit/` | `test/unit` 의 가짜 Hermes 테스트 | E2E 부품의 단위 테스트 |
   | `test/support/*.test.ts`, `test/model-eval/blog/*.test.ts` | `test/unit` 의 미디어 부품과 평가 판정 테스트 | 확인하는 부품 옆 |

2. **루트 `test/` 에는 웹을 띄우지 않는 것과 여러 모듈이 함께 쓰는 것만 남긴다.**
   `test/e2e`(Control Plane 의 응답과 권한과 기록), `test/model-eval`, `test/support`, `test/fixtures`, `test/resources`, `test/repo` 다.
   backend 와 hermes 의 테스트가 `test/fixtures` 와 `test/resources` 를 읽으므로 자리를 바꾸지 않는다.
3. **한 테스트가 문서나 다른 모듈도 읽으면 루트 `test/repo/` 에 둔다.**
   문서만 바꾸는 PR 은 CI 의 web 검사를 건너뛴다([ADR-20261012 / ci-path-filter](ADR-20261012-ci-path-filter.md)).
   그래서 문서를 읽는 테스트는 언제나 도는 루트 단위 검사에 있어야 한다.
   웹 함수의 문구를 `docs/features/attention.md` 와 맞춰 보는 `attention.test.ts` 가 그 예다.
4. **웹 모듈은 그 안의 `test/` 를 운영 코드처럼 다루지 않는다.**
   `web/tsconfig.json` 은 `test` 를 제외해 Next 빌드와 `pnpm typecheck` 에 들어가지 않고,
   `web/eslint.config.mjs` 는 `test/**` 를 무시하고, `web/.prettierignore` 가 `test` 를 뺀다.
   `scripts/pr/pr-size.mjs` 는 경로의 `test/` 와 `.test.` 이름을 테스트로 세므로 운영 크기에 들어가지 않는다.
   이 도구들이 옛 `test/` 를 보지 않던 것과 같은 상태를 지킨다.
5. **CI 와 로컬 검사는 새 자리를 그대로 부른다.**
   - 루트 단위 검사: `node --test 'test/**/*.test.ts' 'scripts/**/*.test.ts'`
   - web 검사: `pnpm test:node` 가 `web/test/node` 와 `web/test/unit` 을 돌린다.
   - 경로 필터는 `web/test/node`·`web/test/unit` 을 web 검사의, `web/test/browser`·`web/test/screenshots` 를 브라우저 검사의 입력으로 본다. `test/repo` 와 스크립트 옆 테스트는 입력이 아니다.

### 대안 기각

- **`test/e2e` 도 `web/` 으로 옮긴다.** 웹을 띄우지 않고 백엔드와 가짜 Hermes 만 띄우는 테스트가라 웹이 주인이 아니다.
  가짜 Hermes 는 브라우저 테스트와 E2E 가 함께 쓴다. 이것을 웹 안으로 옮기면 backend 의 CI 입력이 `web/` 으로 흩어진다.
- **`test/` 를 모두 없애고 모듈마다 둔다.** `test/fixtures` 와 `test/resources` 는 backend 와 hermes 가 함께 읽는 공유 자료다.
  한 모듈에 넣으면 다른 모듈이 그 모듈 안을 들여다보게 된다.
- **스크립트 테스트를 `test/repo` 에 둔다.** 스크립트를 고치는 사람이 테스트를 찾으러 모듈 밖으로 나가야 한다. 스크립트 옆이 더 가깝다.
- **옛 폴더 이름(`test/unit`)을 저장소 규칙 테스트의 이름으로 남긴다.** 웹 단위 테스트(`web/test/unit`)과 이름이 같아 「단위 테스트는 어느 쪽인가」 를 되묻게 한다.
  남은 것이 저장소 규칙이라는 점이 드러나도록 `test/repo` 로 바꿨다.

### 감수할 것

- 웹 단위 테스트는 이제 web 검사에서만 돈다. 문서만 바꾼 PR 은 돌지 않으므로 문서를 읽는 테스트는 `test/repo` 에 둔다는 3번을 지켜야 한다.
- 웹 안의 테스트는 `web/tsconfig.json` 이 제외하므로 타입 검사를 받지 않는다. 옛 `test/web`, `test/unit`, `test/browser` 도 받지 않았다.
  타입 검사를 더하려면 `web/test` 용 tsconfig 를 따로 둔다.
- 이동한 날 함께 돌던 PR 이 옛 자리에 더한 테스트는 main 을 합칠 때 새 자리로 옮겨야 한다.
- 이동 이전의 ADR 과 이력은 옛 경로를 적고 있을 수 있다. 이 ADR 이 그 대응을 갖는다.
