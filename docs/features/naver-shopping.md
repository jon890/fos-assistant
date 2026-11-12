# 네이버 쇼핑 플러그인

사용자가 「내 브라우저」 에 로그인해 둔 네이버 계정으로 네이버 쇼핑 상품을 찾고 상품 정보를 읽는 범용 플러그인의 개발 문서다.

covers: `hermes/connectors/naver-shopping/`

코드는 [`hermes/connectors/naver-shopping/`](../../hermes/connectors/naver-shopping) 에 있다. 사용자 안내는 [네이버 쇼핑 플러그인 README](../../hermes/connectors/naver-shopping/README.md) 가 갖는다.
요구와 결정은 스펙 [`naver-shopping/spec.md`](naver-shopping/spec.md), 구조는 설계 [`naver-shopping/design.md`](naver-shopping/design.md), 결정의 근거는 [ADR-20261110 / naver-shopping-connector](../adr/ADR-20261110-naver-shopping-connector.md) 가 갖는다.
플러그인 공통 계약은 [플러그인 연결](connector.md) 과 [플러그인 도구 정책](connector-policy.md), 브라우저와 중계는 [사용자 브라우저](user-browser.md) 가 갖는다.

**장바구니에 담거나 주문하지 않는다.** 지금은 찾기와 읽기뿐이다. 주문서 채우기와 결제 화면 넘기기는 스펙의 뒤 PR 이 더한다.

## 등록 칸

사용자가 넣는 칸은 없다. 운영자가 주는 값(`operator_env`)도 없다.

| env | 선언 | 값 |
| --- | --- | --- |
| `NAVER_SHOPPING_BROWSER_URL` | `owner_browser_env` | 주인의 「내 브라우저」 로 가는 Control Plane 중계 주소다. 바인딩 설치는 그 바인딩의 표식 주소를, 확인 도구 호출은 요청자의 호출 표식 주소를 넣는다. 중계가 꺼졌으면 빈 값이다 |

`owner_browser_login_url` 은 네이버 로그인 화면이다. 확인 도구는 `check_shopping_login` 이다.

## 도구와 정책

MCP 서버 이름은 `naver-shopping` 이다. 세 도구 모두 `READ`, 승인 없음, `attended_only` 다.

| 도구 | 인자 | 결과 |
| --- | --- | --- |
| `check_shopping_login` | 없음 | `{browser: "connected", logged_in: true}`. 쿠키가 없으면 `NAVER_SHOPPING_LOGIN_REQUIRED`. 탭을 열지 않는다 |
| `search_products` | `query`(한 줄 1자에서 100자) | `{query, count, products: [{rank, title, price_krw, store, url, kind, naver_pay, ad}]}`. 처음 실린 상품을 40개까지 |
| `get_product_detail` | `url`(2,048자까지) | 아래 「상품 정보」 |

- **`attended_only`**: 먼저 살펴보기, 매일 깨우기, 사건 감지, 예약 작업과 그 트리가 맡긴 일의 결과 turn 은 이 도구를 부르지 못한다(`ATTENDED_ONLY`). 판정은 [플러그인 도구 정책](connector-policy.md)의 「도구 호출 판정」 이 갖는다. 연결 확인은 대시보드가 판정 없이 부르므로 막히지 않는다
- **`READ` 로 둔 까닭**: 사용자가 만든 내용(글, 장바구니, 주문)을 바꾸지 않는다. 로그인한 브라우저로 검색하고 상품을 열면 네이버의 최근 검색어와 최근 본 상품에 남는다. 사람이 직접 검색할 때와 같은 기록이라 감수한다
- **도구 이름과 설명**: 도구 검색이 영어 낱말로만 걸려 설명을 영어 요지 문장으로 시작한다. 확인 도구는 블로그의 `session_status` 와 겹치지 않게 지었다. 검사는 `hermes/tests/test_connectors_contract.py` 가 한다

### 상품 정보

| `kind` | 결과 |
| --- | --- |
| `store` | `{kind, url, title, store, price_krw, options: [{name, values: [{label, extra_krw, sold_out}]}], shipping: {fee_krw, free_over_krw, regional_extra}, naver_pay_buy}`. 옵션이 넘치면 `options_truncated: true` |
| `catalog` | `{kind, url, title, sellers: [{store, price_krw, shipping_fee_krw, url}]}`. 네이버페이로 바로 사는 판매처만이다 |
| `external` | `{kind, url, buyable: false}`. 탭을 열지 않는다 |

- `price_krw` 는 즉시할인을 반영한 판매가다. 쿠폰, 포인트, 지역 추가 배송비는 넣지 않는다
- 옵션은 20 묶음, 묶음마다 값 100개까지다. 첫 묶음 값만 추가 금액(그 값 조합의 가장 작은 추가 금액)과 품절(모든 조합의 재고가 0)을 싣고, 둘째 묶음부터는 이름만 싣는다
- `naver_pay_buy` 는 상품 상태가 판매 중일 때 참이다

## 주소 분류

| 갈래 | 판정 | 처리 |
| --- | --- | --- |
| 거절 | `https` 가 아니거나, 주소로 읽히지 않거나, 2,048자를 넘거나, 공백·사용자 정보·포트가 있다 | `NAVER_SHOPPING_INVALID_INPUT`. 검색 결과에서는 그 항목을 뺀다 |
| `store` | `smartstore.naver.com`, `brand.naver.com`(앞에 `m.` 포함)의 `/<스토어>/products/<숫자>` | 조회 글을 뗀 주소로 탭을 연다 |
| `catalog` | `search.shopping.naver.com`(앞에 `m.` 포함)의 `/catalog/<숫자>` | 조회 글을 뗀 주소로 탭을 연다 |
| `external` | 그 밖의 `https` 주소 | 탭을 열지 않는다 |

표는 `src/urls.ts` 가 갖는다.

## 화면 읽기

1. 앞서 사람에게 넘기며 남긴 탭이 있으면 먼저 닫는다. 그다음 중계의 `/json/new?about:blank` 로 빈 탭을 연다. 그 밖의 기존 탭은 읽지도 바꾸지도 않는다
2. 대화상자 처리기를 걸고 페이지 사건을 켠 뒤 그 탭을 주소로 이동시킨다. 우리 탭의 `alert`, `confirm` 은 닫기로 넘긴다
3. `document.readyState` 가 `complete` 가 될 때까지 묻는다
4. 화면을 분류한다. 로그인(로그인 호스트), 기기 인증(로그인 호스트의 기기 경로), 캡차(캡차 요소)면 그 탭을 남기고 `isError` 가 없는 결과에 넘기기 칸(`handoff`)을 실어 끝난다. 최종 주소가 네이버(`naver.com` 과 그 하위)가 아니면 읽지 않고 `NAVER_SHOPPING_PAGE_CHANGED` 로 끝난다
5. 고정 식 하나로 상태 JSON 을 꺼내 플러그인 안에서 결과로 바꾼다
6. 그 밖의 결과는 성공이든 실패든 그 탭을 닫는다. 전체 제한 시간은 45초다

탭에서 부르는 식은 `src/expressions.ts` 의 상수뿐이다. 칸 위치는 `src/fields.ts` 한 곳에 있고 실제 화면으로 확인하지 않은 가설이다. 필수 칸이 비면 `NAVER_SHOPPING_PAGE_CHANGED` 로 끝난다.
그때 표준 오류에 `{"naver_shopping_diagnostic":{"tool","stage","missing"}}` 한 줄을 남긴다. 칸 이름과 단계만 싣고 주소, 검색어, 화면 글은 싣지 않는다.

## 오류

| 서버 코드 | 공통 어휘 | 복구 | 언제 |
| --- | --- | --- | --- |
| `NAVER_SHOPPING_INVALID_INPUT` | `invalid_input` | | 인자 모양이 틀리거나 주소가 거절이다 |
| `NAVER_SHOPPING_BROWSER_UNREACHABLE` | `unavailable` | `retry_later` | 중계에 닿지 않거나 비었다 |
| `NAVER_SHOPPING_LOGIN_REQUIRED` | `credential_rejected` | `reconnect` | 로그인 쿠키가 없거나(`check_shopping_login`, 오류) 로그인 화면으로 넘어갔다(읽기 도구, 넘기기 결과) |
| `NAVER_SHOPPING_CAPTCHA` | `unavailable` | | 보안 확인 화면이다(넘기기 결과) |
| `NAVER_SHOPPING_DEVICE_CHECK` | `unavailable` | | 새 기기 확인 화면이다(넘기기 결과) |
| `NAVER_SHOPPING_NOT_FOUND` | `invalid_input` | | 상품 화면에 상태는 있는데 상품이 없다 |
| `NAVER_SHOPPING_PAGE_CHANGED` | `unavailable` | | 상태 JSON 이 기대한 모양이 아니다 |
| `NAVER_SHOPPING_UNAVAILABLE` | `unavailable` | `retry_later` | 시간 초과와 그 밖의 실패 |

오류 글에 중계 주소, 검색어, 화면 글, 브라우저가 준 원문을 싣지 않는다.

## 화면 넘기기

로그인, 캡차, 기기 인증은 오류가 아닌 결과 `{"status": "HUMAN_ACTION_REQUIRED", "message", "handoff": {"code", "need", "tab"}}` 로 끝난다. Hermes 가 같은 서버의 `isError` 결과를 연속 3회 세면 60초 동안 서버를 막아, 사람이 보안 확인을 풀고 돌아와도 다음 호출이 거절되기 때문이다. `code` 는 위 오류 표의 서버 코드이고 `need` 는 `login`, `captcha`, `device_check` 가운데 하나이고 `tab` 은 남긴 탭의 id 다.
fos-ctx 가 이 칸을 Control Plane 에 알리면 그 대화에 화면 넘기기 카드와 알림이 생긴다. 카드의 「내 브라우저 열기」 가 그 탭을 고른 채 「내 브라우저」 를 열고, 「다 했어요」 가 다음 메시지를 보내 에이전트가 멈춘 도구를 다시 부른다. 카드는 사람이 보낸 turn 이 시작될 때 닫히고, 담당의 결과를 전하는 자동 turn 은 닫지 않는다.
남긴 탭의 id 는 MCP 서버 프로세스 메모리에만 있고 다음 탭 열기가 닫는다. 결과 모양과 흐름은 [설계](naver-shopping/design.md)의 「PR2 화면 넘기기(REQ7, REQ8)」 가 갖는다.

## 보안

- 모델이 준 주소로 탭을 여는 것은 `store` 와 `catalog` 갈래뿐이다
- 캡차 그림을 읽거나 답을 넣는 코드, 누르기와 입력 CDP 명령이 없다. 테스트가 가짜 브라우저의 명령 기록으로 본다
- 검색 결과와 상품 정보는 대화에만 남고 플러그인은 아무것도 저장하지 않는다
- 상품 이름과 판매처 글은 외부 데이터다. 스킬이 지시로 따르지 않게 한다

## 실제 계정으로 확인하기

배포한 뒤 사용자가 자기 계정으로 직접 돈다. 검색어와 상품은 어디에도 적지 않는다.

1. 「외부 서비스 연결」 에서 네이버 쇼핑을 연결하고 쓸 에이전트에 붙인다. 연결 확인이 통과하는지 본다
2. 대화에서 흔한 상품 하나를 찾아 달라고 한다. 결과가 화면의 첫 페이지와 같은 차례, 가격, 판매처인지 본다
3. 결과의 스마트스토어 상품 하나와 가격비교 상품 하나의 정보를 읽어 달라고 한다. 옵션, 배송비, 판매처가 화면과 같은지 본다
4. `NAVER_SHOPPING_PAGE_CHANGED` 가 나오면 그 profile 의 MCP 표준 오류 기록에서 `naver_shopping_diagnostic` 줄에 적힌 `missing` 목록을 보고 `src/fields.ts` 의 칸 위치를 고친다. 기록을 읽는 방법은 `fos-home-infra` 가 갖는다
5. 예약 작업 하나에 상품을 찾으라고 지시해 돌려 보고, 도구가 `ATTENDED_ONLY` 로 거절되는지 본다
6. 「내 브라우저」 에서 네이버를 로그아웃한 뒤 상품을 찾아 달라고 한다. 대화에 「로그인해 주세요」 카드와 알림이 뜨는지, 「내 브라우저 열기」 가 로그인 화면 탭을 고른 채 여는지 본다. 로그인한 뒤 「다 했어요」 를 누르면 검색이 이어지고 카드가 사라지며 남긴 탭이 닫히는지 본다
