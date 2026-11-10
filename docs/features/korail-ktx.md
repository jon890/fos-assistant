# 코레일 KTX 플러그인

사용자가 쓰는 「내 브라우저」 로 코레일 화면에서 KTX 열차를 한 번 조회하는 범용 플러그인의 개발 문서다.

covers: `hermes/connectors/korail-ktx/`

코드는 [`hermes/connectors/korail-ktx/`](../../hermes/connectors/korail-ktx) 에 있다. 사용자 안내는 [코레일 KTX 플러그인 README](../../hermes/connectors/korail-ktx/README.md) 가 갖는다.
요구와 결정은 스펙 [`korail-ktx/spec.md`](korail-ktx/spec.md), 구조는 설계 [`korail-ktx/design.md`](korail-ktx/design.md), 결정의 근거는 [ADR-20261110 / korail-ktx-connector](../adr/ADR-20261110-korail-ktx-connector.md) 가 갖는다.
사람이 연 대화에서만 부르는 도구(`attended_only`)와 도구 이름 검사는 [네이버 쇼핑 플러그인](naver-shopping.md)이 만든 공통 기반이다.

**예매하거나 결제하지 않는다.** 지금은 조회뿐이다. 좌석 확보와 결제 화면 넘기기는 스펙의 PR2 가 더한다.

## 등록 칸

사용자가 넣는 칸은 없다. 운영자가 주는 값(`operator_env`)도 없다.

| env | 선언 | 값 |
| --- | --- | --- |
| `KORAIL_KTX_BROWSER_URL` | `owner_browser_env` | 주인의 「내 브라우저」 로 가는 Control Plane 중계 주소다. 중계가 꺼졌으면 빈 값이다 |

`owner_browser_login_url` 은 `https://www.korail.com/ticket/login` 이다. 확인 도구는 `check_korail_login` 이다.

## 도구와 정책

MCP 서버 이름은 `korail-ktx` 다. 두 도구 모두 `READ`, 승인 없음, `attended_only` 다.

| 도구 | 인자 | 결과 |
| --- | --- | --- |
| `check_korail_login` | 없음 | `{browser: "connected", logged_in: "unknown"}`. 코레일 쿠키가 하나도 없으면 `KORAIL_LOGIN_REQUIRED`. 탭을 열지 않는다 |
| `search_trains` | `departure`, `arrival`(역 이름), `date`(`YYYY-MM-DD`), `time_from`(`HH:MM`), `adults`, `children`, `seniors`(합 1~9), `seat_class`(`general`, `first`) | `{query, count, trains: [{train_ref, train_type, train_no, departs_at, arrives_at, duration_min, seat_status, status_text, adult_fare_krw}]}` |

- **로그인 판정**: 코레일은 로그인 전에도 `JSESSIONID`, `WMONID` 쿠키를 둔다. 로그인했을 때만 생기는 쿠키를 아직 찾지 못해 연결 확인은 코레일 쿠키가 있는지만 보고 `logged_in` 을 `unknown` 으로 답한다. 조회는 로그인 없이도 되고, 로그인이 필요한 화면은 다음 도구가 로그인 화면을 만나 `KORAIL_LOGIN_REQUIRED` 로 알린다
- **역 이름**: `src/stations.ts` 의 표와 끝의 「역」 만 떼고 정확히 같아야 한다. 표에 없으면 `KORAIL_UNKNOWN_STATION` 이다
- **결과**: 첫 화면의 열차만이다. `time_from` 보다 이른 열차는 뺀다. `seat_status` 는 고른 좌석 칸에 누를 수 있는 「예매」 단추가 있을 때만 `available` 이고, 그 밖(매진, 예약대기, 입석, 모르는 글)은 `sold_out` 이다. `adult_fare_krw` 는 좌석 칸에 보인 운임이고 없으면 `null` 이다. `train_ref` 는 날짜, 출발 시각, 열차 종류, 열차 번호, 출발·도착역을 `|` 로 이은 글이고 PR2 의 좌석 확보 준비가 받는다
- **`attended_only`**: 먼저 살펴보기, 매일 깨우기, 사건 감지, 예약 작업과 그 트리가 맡긴 일의 결과 turn 은 이 도구를 부르지 못한다. 판정은 [플러그인 도구 정책](connector-policy.md)의 「도구 호출 판정」 이 갖는다
- **`READ` 로 둔 까닭**: 예약과 결제 상태를 바꾸지 않는다. 로그인한 브라우저로 조회하면 코레일이 그 계정의 조회로 셀 수 있고 매크로 탐지의 대상이 된다. 사람이 직접 조회할 때와 같은 기록이라 감수한다

## 화면 다루기

1. 중계의 `/json/new?about:blank` 로 빈 탭을 연다. 기존 탭은 읽지도 바꾸지도 않는다
2. 대화상자 처리기를 걸고 페이지 사건을 켠 뒤 조회 조건 화면(`/ticket/search/general`)으로 이동한다. 우리 탭의 JS 대화상자는 글을 남기고 닫기로 넘긴다
3. 공지 층을 닫고, 출발역, 도착역, 날짜와 시, 인원을 넣은 뒤 「열차 조회」 를 한 번 누른다
4. 목록 화면(`/ticket/search/list`)에서 공지 층을 닫고 「서울·용산 - 수서 함께 보기」(안 골랐을 때만), 「KTX/KTX-산천」 탭, 특실(고른 때만)을 누른다
5. 고정 식 하나로 목록을 꺼내 플러그인 안에서 결과로 바꾼다
6. 로그인·매크로 탐지·보안 문자 화면이면 그 탭을 남기고 끝난다. 그 밖의 결과는 성공이든 실패든 그 탭을 닫는다. 전체 제한 시간은 60초, 단계 하나가 자리를 기다리는 시간은 10초다

- 자리는 `src/fields.ts` 의 CSS 선택자나 XPath 로 CDP `DOM.performSearch` 가 찾고, 누르기와 글 넣기는 `Input.*` 로 한다. 단계는 `src/steps.ts` 의 표이고 예매·결제·「다음」 단추의 자리가 없다
- 탭에서 부르는 식은 `src/expressions.ts` 의 상수뿐이다. `Runtime.enable` 을 부르지 않는다
- 사람 속도 간격과 조회 간격 제한을 두지 않는다. 자동 재시도도 없다(spec DECISION9)
- 기다림은 하나의 함수가 맡고 기다리는 동안 매번 화면을 분류한다. 사람이 해야 하는 화면은 시간 초과보다 먼저 잡힌다
- 자리 표의 상당 부분은 실제 화면으로 확인하지 않은 가설이다. 무엇을 봤고 무엇이 가설인지는 `src/fields.ts` 의 주석과 설계의 「공개 화면 확인 결과」 가 갖는다. 필수 자리가 없거나 목록 칸이 비면 `KORAIL_PAGE_CHANGED` 로 끝나고 표준 오류에 `{"korail_diagnostic":{"tool","stage","missing"}}` 한 줄을 남긴다. 단계 이름과 칸 이름만 싣고 역 이름, 날짜, 화면 글은 싣지 않는다

### 화면 분류

위에서 아래 순서로 본다.

| 분류 | 판정 | 결과 |
| --- | --- | --- |
| 로그인 | 주소가 `/ticket/login` 이다 | `KORAIL_LOGIN_REQUIRED`, 탭을 남긴다 |
| 매크로 탐지 | 「안내 메시지」 층에 「매크로」 와 `CODE : -8xxx` 가 있다 | `KORAIL_BOT_CHECK`, 탭을 남긴다 |
| 보안 문자 | 보안 문자 요소가 보인다 | `KORAIL_CAPTCHA`, 탭을 남긴다 |
| 거절 | JS 대화상자가 떴거나 「안내 메시지」 층이 위가 아닌 글을 보인다 | `KORAIL_REJECTED`, 그 글을 200자까지 `message` 로 싣는다 |
| 정상 | 위가 아니다 | 다음 단계를 잇는다 |

캡차 그림을 읽거나 답을 넣는 코드, 탐지 안내의 「확인」 을 누르는 코드는 없다.

## 오류

| 서버 코드 | 공통 어휘 | 복구 | 언제 |
| --- | --- | --- | --- |
| `KORAIL_INVALID_INPUT` | `invalid_input` | | 인자 모양이 틀리다 |
| `KORAIL_UNKNOWN_STATION` | `invalid_input` | | 역 표에 없는 역이다 |
| `KORAIL_DATE_UNAVAILABLE` | `invalid_input` | | 날짜가 오늘(KST)보다 이르다 |
| `KORAIL_REJECTED` | `invalid_input` | | 코레일이 대화상자나 안내 층으로 거절했다 |
| `KORAIL_BROWSER_UNREACHABLE` | `unavailable` | `retry_later` | 중계에 닿지 않거나 비었다 |
| `KORAIL_LOGIN_REQUIRED` | `credential_rejected` | `reconnect` | 코레일 쿠키가 없거나 로그인 화면으로 넘어갔다 |
| `KORAIL_CAPTCHA` | `unavailable` | | 보안 문자 화면이다 |
| `KORAIL_BOT_CHECK` | `unavailable` | | 매크로 탐지 화면이다 |
| `KORAIL_PAGE_CHANGED` | `unavailable` | | 자리나 목록 칸이 기대한 모양이 아니다 |
| `KORAIL_UNAVAILABLE` | `unavailable` | `retry_later` | 시간 초과와 그 밖의 실패 |

오류 글에 중계 주소, 역 이름, 날짜, 브라우저가 준 원문을 싣지 않는다. 예외는 `KORAIL_REJECTED` 의 `message` 하나다. 읽기 도구라 그 글이 모델에게 가고 에이전트가 사용자에게 까닭을 전한다.

## 보안

- 탭을 여는 주소는 코레일 조회 조건 화면 하나다. 모델이 주소를 정하지 못한다
- 넣는 글은 역 표의 이름뿐이고, 자리 틀에 끼우는 값은 확인을 통과한 날짜 숫자, 0~23 의 시, 인원 종류 이름뿐이다
- 조회 결과는 대화에만 남고 플러그인은 아무것도 저장하지 않는다
- 좌석 상태 글과 거절 글은 외부 데이터다. 스킬이 지시로 따르지 않게 한다

## 배포 확인

- 쇼핑 PR1 의 Control Plane(`attended_only` 판정)이 배포됐는지 본다. 그 전에 이 묶음만 올라가면 옛 Control Plane 이 예약 작업의 호출을 막지 않는다. 배포 전에 붙은 바인딩이 없어 감수한다

## 실제 계정으로 확인하기

배포한 뒤 사용자가 자기 계정으로 직접 돈다. 역과 날짜는 어디에도 적지 않는다.

1. **플러그인 조회 한 번이 `-8003` 안내 없이 끝나는지 본다.** 「외부 서비스 연결」 에서 코레일 KTX 를 연결해 에이전트에 붙이고, 대화에서 흔한 구간 하나를 조회해 달라고 한다. `KORAIL_BOT_CHECK` 로 끝나면 「내 브라우저」 의 남은 탭을 보고, 더 조회하지 말고 그 결과로 이 기능을 다시 정한다
2. 결과가 「내 브라우저」 에서 같은 조건으로 본 목록과 같은 열차, 시각, 좌석 상태, 운임인지 본다
3. `KORAIL_PAGE_CHANGED` 가 나오면 그 profile 의 MCP 표준 오류 기록에서 `korail_diagnostic` 줄의 `stage` 와 `missing` 을 보고 `src/fields.ts` 의 가설 자리를 고친다. 기록을 읽는 방법은 `fos-home-infra` 가 갖는다
4. 「서울·용산 - 수서 함께 보기」 로 나온 수서발 열차가 결과에 들고 어느 탭에 있는지 본다
5. 로그인 전후 쿠키 이름을 비교한다. 「내 브라우저」 에서 로그아웃한 채 코레일을 연 때와 로그인한 뒤의 코레일 쿠키 이름을 비교해, 로그인했을 때만 생기는 쿠키가 있으면 연결 확인이 그 쿠키로 로그인을 가리도록 고친다(spec DECISION17)
6. 예약 작업 하나에 열차를 조회하라고 지시해 돌려 보고, 도구가 `ATTENDED_ONLY` 로 거절되는지 본다
