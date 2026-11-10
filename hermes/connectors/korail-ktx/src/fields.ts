/**
 * 코레일 화면의 자리 표다. 공개 화면에서 본 것(2026-11-10)과 보지 못한 가설을 한곳에 모은다.
 * 설계 docs/features/korail-ktx/design.md 의 「공개 화면 확인 결과」 와 「확인할 것」 K2 가 근거다.
 * 가설이 틀리면 지어내지 않고 `KORAIL_PAGE_CHANGED` 로 끝난다. 사용자가 「실제 계정으로 확인하기」 로 맞춘다.
 *
 * 자리는 CSS 선택자나 XPath 글이고 CDP 의 `DOM.performSearch` 로만 찾는다. 탭에서 코드를 돌려 찾지 않는다.
 * `{}` 자리에는 확인을 통과한 값(역 표의 이름, 날짜 숫자, 0~23 의 시)만 끼운다.
 */

/** 본 것: 조회 조건 화면과 열차 목록 화면, 로그인 화면의 경로. */
export const SEARCH_URL = "https://www.korail.com/ticket/search/general";
export const LIST_PATH = "/ticket/search/list";
export const LOGIN_PATH = "/ticket/login";

/** 본 것: 화면을 덮는 공지 층의 닫기 단추. 두 화면 어디서나 뜰 수 있다. */
export const NOTICE_CLOSE = "button.btn_pop-close";

/** 본 것: 조건 화면의 입력과 조회 단추. */
export const DEPARTURE_INPUT = 'input[name="txtGoStart"]';
export const ARRIVAL_INPUT = 'input[name="txtGoEnd"]';
export const DATE_INPUT = 'input[name="startDate"]';
export const LOOKUP_BUTTON = "button.btn_lookup";

/** 가설: 역 이름을 넣은 뒤 고르는 자동완성 항목, 날짜 달력의 날과 시, 달력 확인 단추. */
export const STATION_OPTION = '//li[contains(@class,"station")]//*[normalize-space(text())="{}"]';
export const CALENDAR_DAY = '[data-date="{}"]';
export const CALENDAR_HOUR = '[data-hour="{}"]';
export const CALENDAR_CONFIRM = '//button[normalize-space(.)="확인"][ancestor::*[contains(@class,"calendar")]]';

/** 본 것: 인원 선택을 여는 「총 1명」. 가설: 인원 층의 종류별 더하기·빼기 단추와 확인 단추. */
export const PASSENGER_OPEN = '//a[contains(normalize-space(.),"총") and contains(normalize-space(.),"명")]';
export const PASSENGER_PLUS = '//*[@data-psg="{}"]//button[contains(@class,"plus")]';
export const PASSENGER_MINUS = '//*[@data-psg="{}"]//button[contains(@class,"minus")]';
export const PASSENGER_CONFIRM = '//button[normalize-space(.)="확인"][ancestor::*[contains(@class,"psg") or contains(@class,"person")]]';
/** 가설: 코레일 인원 층의 종류 이름. 어른은 처음에 1명이다(본 것: 「총 1명」). */
export const PASSENGER_KINDS = { adults: "adult", children: "child", seniors: "senior" } as const;

/** 본 것: 목록 화면의 「KTX/KTX-산천」 탭과 「서울·용산 - 수서 함께 보기」 고르기. 가설: 특실로 바꾸는 자리. */
export const KTX_TAB = '//button[normalize-space(.)="KTX/KTX-산천"]';
export const SUSEO_TOGETHER = 'input[name="adjStnScdlOfrFlg2"]';
export const FIRST_CLASS = '//*[normalize-space(.)="특실"][self::a or self::button or self::label]';

/** 본 것: 운행 열차가 없을 때의 글. */
export const EMPTY_TEXT = "운행하는 열차가 없습니다";

/** 본 것: 화면 안 「안내 메시지」 층. 매크로 탐지 때 이 층에 안내 글과 `CODE : -8003` 이 떴다. */
export const NOTICE_DIALOG = '[role="dialog"], .layer_pop, .pop_wrap';
export const NOTICE_HEADING = "안내 메시지";
export const BOT_CHECK_WORD = "매크로";
export const BOT_CHECK_CODE = /CODE\s*:\s*-8\d{3}/;

/** 가설: 보안 문자 입력 요소. */
export const CAPTCHA_SELECTOR = '[id*="captcha" i], [class*="captcha" i], img[src*="captcha" i]';

/**
 * 가설: 열차 목록 한 줄과 그 칸. 목록 꺼내기 식이 이 선택자로 칸마다 화면 글을 꺼낸다.
 * 좌석 칸은 고른 좌석 종류(일반실·특실)의 것이다.
 */
export const ROW = {
  row: '[class*="tckList"] > li, [class*="train_list"] > li, table.tbl_list tbody tr',
  type: '[class*="train_type"], [class*="trnGp"]',
  no: '[class*="train_no"], [class*="trnNo"]',
  departs: '[class*="dpt"] [class*="time"], [class*="start"] [class*="time"]',
  arrives: '[class*="arv"] [class*="time"], [class*="end"] [class*="time"]',
  general: '[class*="general"], [class*="normal"]',
  first: '[class*="special"], [class*="first"]',
} as const;

/** 가설: 좌석 칸에 이 글의 누를 수 있는 단추가 있으면 지금 잡을 수 있다(spec DECISION19). */
export const AVAILABLE_TEXT = "예매";
