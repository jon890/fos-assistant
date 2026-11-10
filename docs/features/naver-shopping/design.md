# 네이버 쇼핑 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(REQ1 부터 REQ6)을 구현할 수 있는 깊이로 쓰고, PR2 부터 PR4 는 경계와 방향만 정한다.
뒤 PR 은 착수할 때 그 PR 의 절을 이 파일에서 고치고 독립 검토를 다시 받는다.

## 요구사항 연결

| ID | PR | 해결 방법 |
| --- | --- | --- |
| REQ1 | PR1 | 새 플러그인 `hermes/connectors/naver-shopping/` 과 저장소 등록(아래 「저장소 등록」). 입력 칸 없이 `owner_browser_env` 하나를 선언하고, 확인 도구 `check_shopping_login` 이 네이버 로그인 쿠키만 본다 |
| REQ2 | PR1 | `search_products` 가 빈 탭을 열어 처리기를 건 뒤 검색 주소 하나로 이동하고, 페이지에 처음 실린 상태 JSON 을 고정 식으로 꺼내 플러그인 안의 순수 함수가 결과로 바꾼다 |
| REQ3 | PR1 | `get_product_detail` 이 주소를 「주소 분류」 표로 먼저 나누고(거절, 링크만, 열기), 스마트스토어·브랜드스토어와 가격비교 묶음만 탭을 열어 읽는다. 도구 층 테스트가 주소마다 탭 열기 횟수를 본다 |
| REQ4 | PR1 | 모든 탭 열기가 같은 「화면 분류」 를 거친다. 로그인·캡차·기기 인증이면 아무것도 누르지 않고 탭을 남긴 채 그 종류의 오류로 끝난다 |
| REQ5 | PR1 | `hermes/tests/test_connectors_contract.py` 에 도구 이름 모양, 설명 첫 문장의 영어 요지, 플러그인 사이 도구 이름 중복 검사를 더한다 |
| REQ6 | PR1 | 도구 선언에 범용 칸 `attended_only` 를 더한다. turn 을 열 때 실행 줄에 「사람이 없는 실행」 표식을 남기고, Control Plane 의 도구 판정이 그 트리의 호출을 거절한다 |
| REQ7 | PR2 | 화면 넘기기 카드와 알림. 아래 「PR2 방향」 |
| REQ8 | PR2 | 「다 했어요」 가 다음 메시지를 보낸다. 아래 「PR2 방향」 |
| REQ9 | PR3 | `app_user` 의 상한 컬럼 둘과 연결 화면. 아래 「PR3 방향」 |
| REQ10 | PR3 | 금융 준비 틀의 서버 요약 카드. 아래 「PR3 방향」 |
| REQ11 | PR3 | 승인 뒤 한 번 실행하는 주문서 채우기. 아래 「PR3 방향」 |
| REQ12 | PR3 | 주문서 최종 금액을 다시 읽어 견준다. 아래 「PR3 방향」 |
| REQ13 | PR3 | REQ6 의 표식 검사를 금융 준비 가로채기보다 앞에 두고, 주문서 준비 도구에 `attended_only` 를 둔다 |
| REQ14 | PR4 | 아래 「PR4 방향」 |

## 책임과 경계

| 자리 | 책임 | 바뀌는 PR |
| --- | --- | --- |
| `hermes/connectors/naver-shopping/` | 네이버 화면을 여는 법, 상태 JSON 을 결과로 바꾸는 법, 사람이 해야 하는 화면의 분류. 네이버 이름은 여기와 그 검사·문서에만 둔다(rule-65) | PR1~PR4 |
| 대시보드 plugin `connector_policy.py`, 카탈로그 | `attended_only` 칸의 검증과 카탈로그 전달. 서비스 이름을 모른다 | PR1 |
| Control Plane `chat` 모듈의 turn 열기 | 실행 줄을 만들 때 그 turn 이 사람이 없는 실행인지 표식을 남긴다 | PR1 |
| Control Plane `usage` 모듈의 실행 줄 | 표식 컬럼 하나(`agent_execution`)를 갖는다. 새 테이블은 없다 | PR1 |
| Control Plane `connector` 모듈의 도구 판정 | 트리 루트의 표식을 읽어 `attended_only` 도구를 거절한다. 판정 함수는 지금처럼 `ToolPolicyDecision.decide` 하나다. `task` 모듈을 읽지 않는다 | PR1 |
| 사용자 브라우저(`browser` 모듈)와 중계 | 바뀌지 않는다. 플러그인은 기존 중계 주소로만 닿는다([사용자 브라우저](../user-browser.md)) | PR2 에서 확인 창 처리 |

새 서비스, 새 테이블, 새 의존성은 없다. 데이터의 원본은 사용자의 네이버 계정과 「내 브라우저」 프로필이고, PR1 이 저장하는 것은 실행 줄의 표식 하나뿐이다.

connector 가 `task` 의 저장소를 읽지 않는 까닭은 `task` 가 이미 `connector` 를 import 해(사건 감지) 순환이 생기고, 얼려 둔 최상위 패키지 순환 검사(`TopLevelPackageCycles`)가 실패하기 때문이다.
`connector` 는 이미 실행 줄(`usage`)을 읽으므로 표식을 실행 줄에 두면 새 의존이 없다.

### 저장소 등록(PR1)

플러그인 하나를 더하면 아래를 함께 고친다. 빠지면 묶음 빌드나 계약 검사가 실패한다.

| 자리 | 더하는 것 |
| --- | --- |
| `hermes/connectors/Dockerfile` | 의존성 설치 층의 `COPY naver-shopping/package.json naver-shopping/bun.lock` 줄 |
| `.gitignore`, `hermes/connectors/.dockerignore` | 생성 entry `dist/naver-shopping-mcp.js` 와 블로그와 같은 무시 줄 |
| `.github/CODEOWNERS` | `/hermes/connectors/naver-shopping/` 와 `tests/` 줄 |
| `hermes/README.md` | 플러그인 표의 한 줄 |
| `docs/features/naver-shopping.md` | 플러그인 개발 문서. `covers:` 가 플러그인 디렉터리를 가리킨다 |
| `hermes/connectors/naver-shopping/README.md` | 사용자 안내와 「도구 목록」 표(계약 검사가 title, summary 줄을 대조한다) |

플러그인 코드는 네이버 블로그 플러그인과 나누지 않는다. CDP 연결 부분(`cdp.ts`)을 같은 모양으로 새로 둔다.
블로그의 `cdp.ts` 는 블로그 오류 코드에 묶여 있고, 플러그인은 각자 `package.json` 과 묶음 파일을 갖는 배포 단위라 서로 import 하면 한쪽의 변경이 다른 쪽 묶음을 바꾼다.
같은 코드 두 벌이 생기는 대신 플러그인 하나를 고칠 때 다른 플러그인의 검사를 다시 돌릴 일이 없다.

## 인터페이스와 데이터

### 플러그인 선언(PR1)

`assistant.json` 은 `schema: 2`, `id: naver-shopping`, `fields: []`, `verify.tool: check_shopping_login`, `default_tool_policy: deny` 다.
`owner_browser_env` 는 `NAVER_SHOPPING_BROWSER_URL`, `owner_browser_login_url` 은 네이버 로그인 화면이다.
`.mcp.json` 은 `bun` 과 `${CLAUDE_PLUGIN_ROOT}/dist/naver-shopping-mcp.js` 하나이고, 블로그처럼 `--no-env-file` 을 앞에 둔다(작업 디렉터리의 `.env` 를 읽지 않게 한다). 계약 검사의 `expected_args` 를 이 플러그인에도 넓힌다.

| 도구 | 위험도 | `attended_only` | 인자 | 결과 |
| --- | --- | --- | --- | --- |
| `check_shopping_login` | `READ` | 참 | 없음 | `{browser: "connected", logged_in: true}`. 쿠키가 없으면 `NAVER_SHOPPING_LOGIN_REQUIRED` |
| `search_products` | `READ` | 참 | `query`(1~100자, 한 줄) | `{query, count, products: [...]}` |
| `get_product_detail` | `READ` | 참 | `url`(검색 결과의 상품 주소, 2,048자까지) | 종류별 결과(아래) |

확인 도구도 `attended_only` 다. 에이전트에게도 MCP 도구로 보여 예약 작업이 부르면 꺼진 브라우저를 켠다. 연결 확인은 도구 판정을 거치지 않는 대시보드 직접 호출이라 칸이 있어도 막히지 않는다.

`products` 의 항목은 `{rank, title, price_krw, store, url, kind, naver_pay, ad}` 다.

- `kind` 는 아래 「주소 분류」 의 `store`, `catalog`, `external` 가운데 하나다. `external` 은 링크만 보인다(spec DECISION7)
- `naver_pay` 는 `yes` 나 `unknown` 이다(spec DECISION18)
- 40개까지, 광고 포함(spec DECISION19). 글은 200자, 판매처는 50자에서 자른다. 주소가 「주소 분류」 에서 거절이면 그 항목을 뺀다

`get_product_detail` 의 결과다.

| `kind` | 결과 |
| --- | --- |
| `store` | `{kind, url, title, store, price_krw, options: [{name, values: [{label, extra_krw, sold_out}]}], shipping: {fee_krw, free_over_krw, regional_extra}, naver_pay_buy}`. `price_krw` 는 즉시할인을 반영한 판매가다(spec DECISION21) |
| `catalog` | `{kind, url, title, sellers: [{store, price_krw, shipping_fee_krw, url}]}`. 네이버페이로 바로 사는 판매처만 싣고, 그 `url` 은 `store` 종류다(spec DECISION28). 판매처가 없으면 빈 목록이다 |
| `external` | `{kind, url, buyable: false}`. 탭을 열지 않는다 |

옵션은 20개 묶음, 묶음마다 값 100개까지 싣는다. 넘으면 자른 수를 `options_truncated` 로 알린다.
조합형 옵션처럼 값마다 재고가 따로인 경우는 첫 단계의 값과 품절만 싣는다. 주문서 준비(PR3)가 고른 조합을 다시 읽는다.

### 주소 분류(PR1)

| 갈래 | 판정 | 처리 |
| --- | --- | --- |
| 거절 | `https` 가 아니거나, 주소로 읽히지 않거나, 2,048자를 넘거나, 사용자 정보·포트가 있다 | `NAVER_SHOPPING_INVALID_INPUT`. 검색 결과에서는 그 항목을 뺀다 |
| `store` | 호스트가 `smartstore.naver.com`, `brand.naver.com`(앞에 `m.` 을 붙인 것 포함)이고 경로가 `/<스토어>/products/<숫자>` 다 | 탭을 열어 상품을 읽는다 |
| `catalog` | 호스트가 `search.shopping.naver.com`(앞에 `m.` 포함)이고 경로가 `/catalog/<숫자>` 다 | 탭을 열어 판매처 목록을 읽는다 |
| `external` | 그 밖의 `https` 주소. 네이버 쇼핑의 바깥 이동 주소도 여기다 | 탭을 열지 않고 `{kind: "external", buyable: false, url}` |

표는 `urls.ts` 의 상수다. 탭을 여는 것은 `store` 와 `catalog` 뿐이라, 모델이 준 주소로 아무 사이트나 「내 브라우저」 에서 열 수 없다.

### 화면을 읽는 방식(PR1)

1. 중계의 `/json/new?about:blank` 로 빈 탭을 연다. 기존 탭은 읽지도 바꾸지도 않는다
2. 그 탭에 붙어 `Page.enable` 과 `Page.javascriptDialogOpening` 처리기를 건 뒤 `Page.navigate` 로 주소를 연다. 처리기를 먼저 걸어 이동 중에 뜬 대화상자도 받는다. 우리 탭에 뜬 `alert`, `confirm` 은 닫기(`accept: false`)로 넘긴다. 읽기 탭은 사람이 쓰는 탭이 아니라서다
3. `document.readyState` 가 `complete` 가 될 때까지 고정 식으로 묻는다(블로그 `editor/open.ts` 와 같은 방식). 그다음 「화면 분류」 를 한다
4. 분류가 정상이면 고정 식 하나로 페이지의 상태 JSON(검색은 Next.js 의 데이터, 상품은 미리 실은 상태)을 꺼낸다. 식은 플러그인 소스의 상수이고 모델이나 인자가 식을 정하지 못한다
5. 꺼낸 JSON 을 순수 함수(`parse-search.ts`, `parse-product.ts`)가 결과로 바꾼다. 필요한 칸이 없으면 `NAVER_SHOPPING_PAGE_CHANGED` 다
6. 정상으로 끝나면 그 탭을 `/json/close/<id>` 로 닫는다(spec DECISION20)

상태 JSON 은 서버가 처음 그린 데이터라 아래로 내려 더 실리는 상품은 들어 있지 않다. 그래서 내리지 않고 처음 실린 상품만 40개까지 돌려준다(spec DECISION19 를 이렇게 고쳤다). 40개보다 적을 수 있다. 다음 페이지 주소로 가지 않는다.

탭에서 부르는 `Runtime.evaluate` 의 식은 `expressions.ts` 의 상수 집합(준비 상태 묻기, 화면 분류, 상태 JSON 꺼내기)뿐이다. 누르기, 입력, 이동하는 식은 이 집합에 없다.

**상태 JSON 의 칸 위치는 실제 네이버 화면으로 확인하지 않은 가설이다.** 이 작업은 실제 계정과 사용자 브라우저를 쓰지 못한다.
그래서 칸 위치를 파서의 표 하나(`fields.ts`)에 모으고, 표의 칸이 하나라도 비면 결과를 지어내지 않고 `PAGE_CHANGED` 로 끝낸다.
실패하면 표준 오류에 칸 이름과 단계만 남는 진단 줄을 쓴다(블로그의 진단 줄과 같은 제한: 주소, 검색어, 화면 글을 싣지 않는다).
사용자가 기능 파일의 「실제 계정으로 확인하기」 를 돌아 표를 맞춘다. 첫 확인에서 표가 틀릴 가능성이 높다.

### 화면 분류(PR1, PR2 가 다시 씀)

| 분류 | 판정 | PR1 의 결과 |
| --- | --- | --- |
| `login` | 탭의 주소가 네이버 로그인 호스트로 넘어갔다 | `NAVER_SHOPPING_LOGIN_REQUIRED` |
| `captcha` | 페이지에 네이버 보안 확인(캡차) 요소가 있다 | `NAVER_SHOPPING_CAPTCHA` |
| `device_check` | 주소가 새 기기 확인 경로다 | `NAVER_SHOPPING_DEVICE_CHECK` |
| `ok` | 위가 아니다 | 읽기를 잇는다 |

판정은 주소와 요소의 있고 없음만 본다. 캡차 그림을 읽거나 답을 넣는 코드는 두지 않는다. 사람이 해야 하는 분류에서는 탭을 닫지 않는다.
판정에 쓰는 호스트, 경로, 선택자는 `classify.ts` 의 상수다.

### 오류(PR1)

| 서버 코드 | 공통 어휘 | 복구 | 언제 |
| --- | --- | --- | --- |
| `NAVER_SHOPPING_INVALID_INPUT` | `invalid_input` | | 인자 모양이 틀리거나 「주소 분류」 의 거절이다 |
| `NAVER_SHOPPING_BROWSER_UNREACHABLE` | `unavailable` | `retry_later` | 중계에 닿지 않거나 비어 있다 |
| `NAVER_SHOPPING_LOGIN_REQUIRED` | `credential_rejected` | `reconnect` | 로그인 쿠키가 없거나 로그인 화면으로 넘어갔다 |
| `NAVER_SHOPPING_CAPTCHA` | `unavailable` | | 보안 확인 화면이다 |
| `NAVER_SHOPPING_DEVICE_CHECK` | `unavailable` | | 새 기기 확인 화면이다 |
| `NAVER_SHOPPING_NOT_FOUND` | `invalid_input` | | 상품이 없거나 판매가 끝났다 |
| `NAVER_SHOPPING_PAGE_CHANGED` | `unavailable` | | 상태 JSON 이 기대한 모양이 아니다 |
| `NAVER_SHOPPING_UNAVAILABLE` | `unavailable` | `retry_later` | 그 밖의 실패 |

읽기 도구는 승인 없이 돌아 오류 글이 그대로 모델에게 간다. 사람이 해야 하는 종류를 코드로 나눈 것은, 같은 코드를 PR3 의 승인한 실행에서도 쓰고 그 경로는 선언된 코드와 정수 세부만 에이전트까지 전하기 때문이다(ADR-092). 스킬이 코드마다 할 일(「내 브라우저」 에서 풀어 달라고 안내)을 정한다.

**읽기 도구도 네이버 계정의 기록을 바꿀 수 있다.** 로그인한 브라우저로 검색하고 상품을 열면 네이버의 최근 검색어와 최근 본 상품에 남는다.
그래도 `READ` 로 두는 까닭은 사용자가 만든 내용(글, 장바구니, 주문)을 바꾸지 않고, 사람이 네이버에서 직접 검색할 때와 같은 기록이기 때문이다. 블로그의 `read_draft` 는 사용자가 쓴 글이 자동저장으로 바뀔 수 있어 `WRITE` 로 두었다. 이 감수는 ADR 이 갖는다.

### 도구 이름과 설명(PR1, REQ5)

Hermes v0.21.6 의 도구 검색은 등록 이름(`mcp__naver_shopping__search_products`)과 서버가 낸 설명을 `[A-Za-z0-9]+` 로 잘라 색인한다.
그래서 이름은 영어 동작과 대상 낱말로, 설명은 영어 요지 한 문장으로 시작하고 한국어 설명을 잇는다.
서버는 `assistant.json` 의 `description` 을 그대로 MCP 설명으로 낸다(블로그와 같은 방식, 묶음 검사가 같은지 본다).

| 검사 | 규칙 | 대상 |
| --- | --- | --- |
| 이름 모양 | `^[a-z][a-z0-9]*(_[a-z0-9]+)+$`. 낱말 둘 이상의 snake_case | 이 저장소의 모든 플러그인. 지금 도구는 모두 맞는다 |
| 설명 첫 문장 | 맨 앞이 영어 문장 하나다. 첫 `.` 까지가 ASCII 글자이고, 세 글자 이상의 영어 낱말이 셋 이상이며, 도구 이름의 낱말 하나 이상을 포함한다 | 새 플러그인. Gmail, 네이버 블로그, 토스증권은 예외 목록에 둔다 |
| 이름 중복 | 이 저장소의 플러그인 사이에 같은 도구 이름이 없다 | 모든 플러그인. 지금 중복이 없다. 플러그인 하나를 보는 `check_connector` 밖에 디렉터리 목록을 받는 교차 검사 함수를 따로 둔다 |

기존 세 플러그인을 예외로 두는 까닭은 플러그인마다 다르다.
토스증권은 `execution_guard` 가 있어 설명을 바꾸면 manifest SHA 가 바뀌고 열린 금융 ticket 과 승인이 무효가 된다([플러그인 도구 정책](../connector-policy.md)의 「표시 메타데이터와 보호 해시」).
Gmail 과 네이버 블로그는 SHA 문제는 없지만, 설명은 모델이 도구를 고르는 지시문이라 바꾸면 그 플러그인의 동작 확인(블로그의 모델 평가 등)을 다시 돌아야 한다. 이 PR 의 관심사가 아니다.
예외 목록은 늘리지 않는다는 주석과 함께 검사 파일에 두고, 기존 플러그인의 설명을 고치는 PR 에서 목록에서 뺀다.
이름 중복을 막는 까닭은 도구 검색이 서버 이름 없이 도구 낱말로 걸릴 때 두 플러그인의 같은 이름이 함께 나와 모델이 엉뚱한 서버를 고르기 때문이다. 확인 도구 이름을 블로그의 `session_status` 와 다르게 지은 것도 그래서다.

### `attended_only`(PR1, REQ6)

| 층 | 바뀌는 것 |
| --- | --- |
| `assistant.json` | 도구마다 선택 칸 `attended_only: true`. 참이 아니면 적지 않는다 |
| 대시보드 `connector_policy.py` | 허용 키에 더하고 boolean 이 아니면 거절한다. 정책 dict 에 `attended_only` 를 싣는다. 카탈로그가 그대로 낸다 |
| Control Plane `ConnectorCatalogTools`, `ConnectorTool`, `ToolPolicy` | 칸을 읽는다. 없으면 거짓, 있는데 boolean 이 아니면 참이다. 읽을 수 없는 선언을 닫히는 쪽으로 읽는 `outbound` 와 같은 원칙이다 |
| Control Plane 도구 판정 | 판정 입력에 「사람이 없는 실행」 을 더한다. 순서는 위험도가 열리지 않은 거절(`RISK_NOT_OPEN`) 바로 뒤, 살펴보기 읽기 경계(`READ_ONLY_RUN`) 앞이다. 선언이 있어야 칸을 알므로 선언 없음(`UNDECLARED`)과 연결 준비 안 됨(`NOT_READY`)보다 뒤다. 걸리면 `DENIED`(`ATTENDED_ONLY`)이고 승인 줄을 만들지 않는다. 모델에게는 「사람이 연 대화에서만 쓸 수 있는 도구입니다」 를 준다. `deny_reason` 컬럼 설명은 새 마이그레이션의 `MODIFY COLUMN ... COMMENT` 로 다시 적는다. 이미 적용된 DB 주석 파일(`db/comments/*.tsv`)은 Flyway 체크섬에 들어가 고치지 않는다 |

#### 사람이 없는 실행의 표식

`agent_execution` 에 컬럼 `unattended`(boolean, 기본 거짓)를 더한다. turn 을 열어 실행 줄을 만드는 `ChatTurnLifecycle.begin` 이 줄을 만든 직후, Hermes 에 보내기 전에 turn 의 의도로 정해 적는다(`ExecutionRecorder.markUnattended`). 엔티티 칸은 넣지도 고치지도 않게 두고 저장소의 쿼리로만 쓰고 읽어, 다른 흐름의 엔티티 저장이 값을 덮지 않게 한다. 그 뒤 바꾸지 않는다. 도구 호출은 Hermes 가 도는 동안에만 오므로 판정은 늘 적힌 값을 본다.

| turn 의도 | 표식 |
| --- | --- |
| `Scheduled`(예약 작업) | 참 |
| `ProactiveCheck`(먼저 살펴보기, 매일 깨우기, 사건 감지) | 참 |
| `DelegationResults`(맡긴 일의 결과를 전하는 자동 turn) | `ChatDeliveryTurns` 가 전할 위임 결과(`AgentExecution`)의 트리 루트를 읽어 하나라도 참이면 참(`TurnIntent.DelegationResults.fromUnattended`). 승인한 동작의 결과(`AutoTurnResult`)는 사람이 승인한 뒤라 세지 않는다. 사람이 누른 다시 전달(`retry`)은 사람이 있는 turn 이라 거짓 |
| `Fresh`, `Regenerate` | 거짓 |

판정은 호출한 실행의 트리 루트 줄의 표식을 `SessionOwnerResolver.unattendedTree` 로 본다. 이 빈은 이미 실행 줄 저장소를 갖고 도구 판정이 origin 을 찾을 때 쓴다. 루트 줄을 찾지 못하면 사람이 없는 쪽(참)으로 본다. 위임 자식은 루트로 판정하므로 살펴보기나 예약 작업이 맡긴 자식도 막힌다.
기존 `CheckTrigger.unattended()` 는 단추로 연 살펴보기(`MANUAL`)를 거짓으로 두지만, 이 컬럼은 살펴보기 turn 을 모두 참으로 둔다. 쇼핑 도구는 살펴보기 전체에서 막기 때문이다(spec DECISION13). 두 이름의 뜻이 달라 `data-schema.md` 에 함께 적는다.
먼저 살펴보기는 지금의 `ProactiveCheckGuard.isCheckTree` 로도 가려지지만, `attended_only` 판정은 이 표식 하나만 본다. 살펴보기 읽기 경계(ADR-080)는 그대로 둔다.
승인 결과를 전하는 자동 turn 은 사람이 승인 카드를 누른 뒤 도는 것이라 표식이 거짓이다(spec DECISION32). 사람이 없는 실행에서는 `attended_only` 도구가 승인 줄을 만들지 않으므로 이 길로 열리지 않는다.

`task_run.execution_id` 는 turn 이 끝날 때만 채워져 도는 중의 판정에 쓸 수 없다. 그래서 그 칸을 쓰지 않는다.
이미 있는 실행 줄은 거짓으로 남는다. 배포 시점에 도는 예약 작업 turn 하나가 열린 채 지나갈 수 있지만, 이 플러그인은 그 전에 붙은 바인딩이 없다.

## 실패와 일관성

- PR1 의 도구는 모두 읽기라 다시 불러도 계정 상태가 바뀌지 않는다. 재시도와 중복 처리는 두지 않는다
- 같은 브라우저에서 읽기 둘이 겹치면 각자 새 탭을 쓴다. 잠그지 않는다. 사용자 브라우저의 한 화면 규칙은 화면 중계의 것이고 탭 수와 상관없다
- 사람이 해야 하는 화면에서 남긴 탭은 사람이 닫을 때까지 남는다. 프로필의 「이전 세션 이어서 열기」 때문에 브라우저를 껐다 켜도 돌아온다. PR1 은 사람이 해야 할 때만 남겨 쌓이는 수가 작다고 보고 감수한다. PR2 의 화면 넘기기가 사람이 마친 뒤 그 탭을 닫는다
- 제한 시간: 확인 도구 8초(대시보드 10초 안), 검색과 상세 45초. 꺼진 브라우저를 켜는 데 30초까지 걸린다
- 배포 순서: 운영은 Hermes 묶음을 먼저 올린다. 새 대시보드 plugin 은 `attended_only` 를 받고, 그사이 옛 Control Plane 은 이 칸을 몰라 막지 않는다. 이 플러그인은 새로 생겨 배포 전에는 바인딩이 없고, 두 배포 사이에 사용자가 연결을 붙이고 예약 작업이 쇼핑 도구를 부를 때만 열린다. 읽기뿐이라 감수한다
- 옛 대시보드 plugin 은 모르는 키를 거절해 이 플러그인을 카탈로그에 올리지 않는다. 닫히는 쪽이다

## 검증 전략

| ID | 경계 | 방법 |
| --- | --- | --- |
| REQ1 | 플러그인, 대시보드 | 가짜 CDP(블로그 `fake-cdp.ts` 와 같은 모양)로 쿠키 있음·없음·중계 비어 있음. 계약 검사가 선언과 서버 도구 목록, 읽기 표시, README 표, CODEOWNERS 를 대조하고, 카탈로그의 `owner_browser_login_url` 이 네이버 로그인 주소인지 본다. 연결 확인은 대시보드가 도구 판정 없이 부른다는 것을 기존 연결 확인 테스트의 경로로 확인한다. 실제 묶음은 CI 의 플러그인 묶음 검사가 본다 |
| REQ2, REQ3 | 플러그인 | 가짜 상태 JSON fixture 로 파서 단위 테스트(40개 자르기, 광고 표시, 묶음·외부·거절 분류, 옵션 자르기, 칸 없음 → `PAGE_CHANGED`). 주소 분류 표 테스트. 가짜 CDP 로 빈 탭 → 처리기 → 이동 순서, 우리 탭의 대화상자 닫기, 정상일 때만 탭 닫기. 로컬 Chrome 테스트(블로그의 `*.chrome.test.ts` 방식)으로 가짜 HTML 페이지에서 고정 식이 상태 JSON 을 꺼내는지 |
| REQ4 | 플러그인 | 화면 분류 단위 테스트. 사람이 해야 하는 분류마다 탭을 닫지 않는다. 가짜 CDP 의 명령 기록에 `Input.*` 가 없고, `Runtime.evaluate` 의 식이 모두 상수 집합 안에 있는지 단언한다 |
| REQ5 | 계약 검사 | 일부러 틀린 선언 사본(영어 요지 없음, 이름 한 낱말)이 실패하는지 `ContractCatchesViolationsTest` 에 더한다. 이름 중복은 두 플러그인을 임시 루트에 복사해 한쪽 이름을 다른 쪽과 같게 바꾼 뒤 교차 검사가 잡는지 본다 |
| REQ6 | 대시보드, Control Plane | `connector_policy.py` 의 칸 검증 테스트. `ToolPolicyDecisionTest` 에 사람이 없는 실행 × `attended_only` × 위험도 표와 순서. 표식은 `ChatTurnLifecycle` 테스트에서 의도마다 적히는지 보고, 예약 작업은 `TaskRunStarter` 로 연 turn 이 도는 동안 도구 판정을 불러 거절되는지 통합 테스트으로 본다(fixture 로 표식을 미리 채우지 않는다). 맡긴 일의 결과 turn 이 사람이 없는 트리의 결과면 거절되고, 보통 대화와 그 위임 자식은 허용되는지 |

실제 네이버 화면과 계정은 이 작업에서 쓰지 못한다. 기능 파일의 「실제 계정으로 확인하기」 를 사용자가 배포 뒤 돈다. 그 전까지 REQ2, REQ3 은 가짜 화면에서만 확인된 것으로 PR 본문에 적는다.

## PR2 방향(화면 넘기기)

- 화면 넘기기 카드는 Control Plane 이 만든다. 카드의 종류와 탭은 플러그인 결과에서 오고, 모델이 쓴 글로 결제 금액 같은 값을 그리지 않는다
- 읽기 도구는 승인 없이 결과가 모델에게 바로 가 Control Plane 이 결과를 보지 못한다. PR2 는 아래에서 고른다.
  - (a) 묻는 카드(ADR-026)처럼 답 끝의 태그를 Control Plane 이 저장할 때 검증한다. 모델이 태그를 빠뜨리면 카드가 뜨지 않아 REQ7 을 보장하지 못한다
  - (b) 플러그인이 Control Plane 에 알리는 경로. 플러그인에 중계 밖의 운영 주소와 인증이 필요해 `operator_env` 금지(ADR-064)와 부딪친다
  - (c) profile plugin(fos-ctx)의 도구 결과 hook 이 선언된 「사람이 해야 함」 오류 코드를 보고 Control Plane 에 전한다. 모델과 플러그인에 기대지 않아 먼저 검토한다
- 알림은 `notification` 테이블에 새 종류 하나를 더한다(새 테이블 없음, ADR-070)
- 「내 브라우저 열기」 는 `/browser` 로 가고 그 작업 탭을 고른 채 연다. 화면 중계에 탭 고르기가 이미 있다
- 확인 창(`beforeunload`, `confirm`)을 화면이 보이지도 누르지도 못해 원격 탭이 멈추는 문제를 함께 고친다(2026-11-10 사용자 결정). 화면 세션이 `Page.javascriptDialogOpening` 을 받아 화면에 그리고 사람이 고르게 한다

## PR3 방향(구매 상한과 주문서)

- 금융 준비 가로채기(`financial.intercept`)는 도구 판정보다 먼저 돌고 예약 작업의 호출에도 승인 줄을 만든다. 그래서 사람이 없는 실행의 거절을 그 가로채기 앞으로 옮긴다(REQ13)
- 승인 카드가 서버가 만든 요약을 보이고 승인 때·실행 직전에 상한을 다시 보는 틀은 토스의 금융 준비·승인·claim 과 같다. PR3 는 그 틀(`execution_guard`, `ConnectorFinancialIntake`, `ConnectorExecutionClaims`)에 주문서 준비 종류를 더하는 쪽을 먼저 검토한다. 실행 도구는 결제하지 않으므로 위험도 이름과 개방 조건을 그 PR 에서 정한다
- 상한 두 값은 `app_user` 에 컬럼 둘을 더한다(새 테이블 없음). 토스 상한과 같은 잠금으로 판정한다. 운영 최대치는 없다(spec DECISION31)
- 하루 합계는 승인 줄에서 센다. 결제 화면을 넘기지 못한 승인은 빼고, 결과를 모르는 승인은 센다(spec DECISION30)
- 결제 단추를 누르는 코드 경로를 두지 않는다. 주문서 화면의 결제 단추 선택자를 플러그인 소스에 두지 않고, 검사가 그 사실을 본다
- 결과를 모르는 주문서 채우기는 그 승인을 끝내고 화면 넘기기 카드를 띄운다(spec DECISION33)

## PR4 방향(주문 확인)

- 주문 내역 읽기 도구 하나(`READ`, `attended_only`). 결제 화면을 넘긴 시각 뒤의 주문만 보고 상품과 금액이 맞는지 본다(spec DECISION27). 넘긴 시각과 승인 금액은 PR3 의 승인 줄이 갖는다

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 화면을 읽는 방식 | 페이지의 상태 JSON 을 고정 식으로 꺼내 플러그인 안에서 바꾼다. DOM 글을 긁지 않는다 | 작성자 | 상태 JSON 은 화면 배치가 바뀌어도 덜 바뀌고, 가격·옵션이 숫자로 온다. 고정 식이라 모델이 식을 정하지 못한다 |
| DECISION2 | 칸 위치의 확인 | 가설로 두고 표 하나에 모은다. 비면 `PAGE_CHANGED`. 사용자가 배포 뒤 실제 계정 확인으로 맞춘다 | 작성자 | 이 작업은 실제 네이버 화면을 쓰지 못한다(지시서). 지어낸 결과보다 실패가 낫다 |
| DECISION3 | CDP 연결 코드 | 블로그와 나누지 않고 같은 모양으로 새로 둔다 | 작성자 | 플러그인은 각자 배포 단위다. 오류 코드가 플러그인마다 다르다 |
| DECISION4 | 확인 도구 이름 | `check_shopping_login` | 작성자 | 블로그의 `session_status` 와 겹치지 않게 한다(REQ5 의 이름 중복 검사) |
| DECISION5 | 사람이 없는 실행을 막는 방법 | 범용 도구 칸 `attended_only` 와 Control Plane 판정. 표식은 turn 을 열 때 실행 줄에 적는다 | 작성자 | 판정은 Control Plane 이 한다(ADR-049). 플러그인과 plugin 은 서비스와 실행 종류를 모른다(rule-65). `task_run` 은 도는 중에 실행 번호가 없고 connector 가 읽으면 순환이 생긴다 |
| DECISION6 | 영어 요지 검사의 대상 | 새 플러그인만. 기존 세 플러그인은 늘리지 않는 예외 목록 | 작성자 | 토스는 manifest SHA 가 바뀌어 금융 승인이 무효가 되고, Gmail 과 블로그는 설명이 모델 지시문이라 따로 확인해야 한다 |
| DECISION7 | 읽기 탭의 대화상자 | 우리가 연 탭에서만 닫기로 넘긴다. 사람이 쓰는 탭은 건드리지 않는다 | 작성자 | 읽기 탭은 사람이 보지 않는다. 사람 탭의 확인 창은 PR2 가 화면에 보인다 |
| DECISION9 | 검색 결과를 내려 더 읽는지 | 내리지 않는다. 처음 실린 상태 JSON 만 읽는다 | 작성자 | 내려서 실리는 상품은 상태 JSON 에 들어가지 않는다. 다른 출처(DOM, 응답 가로채기)을 섞으면 칸 위치 가설이 두 벌이 된다. spec DECISION19 를 같이 고쳤다 |
| DECISION10 | 플러그인이 여는 주소 | 「주소 분류」 의 `store`, `catalog` 만 탭을 연다 | 작성자 | 모델이 준 주소로 로그인된 브라우저가 아무 사이트나 열지 않게 한다 |
| DECISION8 | 결정 기록 | 루트 `docs/adr/ADR-20261110-naver-shopping-connector.md` 하나. 브라우저 화면을 읽는 쇼핑 플러그인과 `attended_only` 의 선택 이유를 갖는다 | 작성자 | 플러그인(hermes)과 판정(backend)에 걸쳐 루트에 둔다(ADR 배치 규칙) |
