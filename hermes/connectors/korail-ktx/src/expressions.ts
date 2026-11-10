/**
 * 탭에서 `Runtime.evaluate` 로 부르는 식은 이 파일의 상수뿐이다. 모델이나 인자가 식을 정하지 못한다.
 * 누르기, 입력, 이동하는 식은 두지 않는다. 누르기와 입력은 CDP 의 `Input.*` 로만 한다.
 * 테스트가 탭에 보낸 식이 모두 이 집합 안인지 본다.
 */
import {
  AVAILABLE_TEXT,
  CAPTCHA_SELECTOR,
  EMPTY_TEXT,
  NOTICE_CLOSE,
  NOTICE_DIALOG,
  NOTICE_HEADING,
  ROW,
  SUSEO_TOGETHER,
} from "./fields.ts";

const json = JSON.stringify;

/** 보이는 요소인지. 식 안에서 함께 쓰는 조각이다. */
const VISIBLE = `(e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== "hidden"; }`;

export const READY_STATE = "document.readyState";

/** 화면 분류에 쓰는 값. 「안내 메시지」 층의 글은 400자까지다. 「창닫기」 가 있는 공지 층은 닫을 것이라 거절로 보지 않는다. */
export const PAGE_PROBE = `(() => { const visible = ${VISIBLE}; const notice = [...document.querySelectorAll(${json(NOTICE_DIALOG)})].find((e) => visible(e) && (e.innerText || "").includes(${json(NOTICE_HEADING)}) && !e.querySelector(${json(NOTICE_CLOSE)})); return { href: location.href, captcha: [...document.querySelectorAll(${json(CAPTCHA_SELECTOR)})].some(visible), notice: notice ? notice.innerText.slice(0, 400) : null }; })()`;

/** 「서울·용산 - 수서 함께 보기」 가 이미 골라져 있는지. 없으면 `null` 이다. */
export const SUSEO_CHECKED = `(() => { const e = document.querySelector(${json(SUSEO_TOGETHER)}); return e ? e.checked === true : null; })()`;

/**
 * 열차 목록을 JSON 글로 꺼낸다. 줄마다 칸의 화면 글과, 좌석 칸에 누를 수 있는 「예매」 단추가 있는지를 싣는다.
 * 목록 자리가 없고 운행 열차 없음 글도 없으면 `null` 이다.
 */
export const LIST_STATE = `(() => { try { const visible = ${VISIBLE}; const t = (row, s) => { const e = row.querySelector(s); return e ? (e.innerText || "").slice(0, 200) : null; }; const seat = (row, s) => { const e = row.querySelector(s); if (!e) return null; const buttons = [...e.querySelectorAll("a, button")].filter((b) => visible(b) && !b.disabled && (b.innerText || "").trim() === ${json(AVAILABLE_TEXT)}); return { text: (e.innerText || "").slice(0, 200), bookable: buttons.length > 0 }; }; const rows = [...document.querySelectorAll(${json(ROW.row)})].filter(visible).slice(0, 40).map((row) => ({ type: t(row, ${json(ROW.type)}), no: t(row, ${json(ROW.no)}), departs: t(row, ${json(ROW.departs)}), arrives: t(row, ${json(ROW.arrives)}), general: seat(row, ${json(ROW.general)}), first: seat(row, ${json(ROW.first)}) })); const empty = (document.body.innerText || "").includes(${json(EMPTY_TEXT)}); if (!rows.length && !empty) return null; return JSON.stringify({ empty: !rows.length, rows }); } catch { return null; } })()`;

export const EXPRESSIONS: ReadonlySet<string> = new Set([
  READY_STATE,
  PAGE_PROBE,
  SUSEO_CHECKED,
  LIST_STATE,
]);
