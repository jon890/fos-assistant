import type { EditorPage } from "./page.ts";

type Region = "title" | "body" | "other" | "none";
export type FocusSample = {
  elements: number;
  sized: boolean;
  editable: boolean;
  page_focused: boolean;
  page_visible: boolean;
  hit: Region;
  anchor: Region;
  end: Region;
  active: Region;
  collapsed: boolean;
};
export type FocusDiagnostics = { attempts: number; samples: FocusSample[] };
const MAX_ATTEMPTS = 10;
const bounded = (value: unknown, max: number) =>
  typeof value === "number" && Number.isFinite(value)
    ? Math.max(0, Math.min(max, Math.floor(value))) : 0;
const region = (value: unknown): Region =>
  value === "title" || value === "body" || value === "none" ? value : "other";

/** 브라우저 응답과 예외의 추가 값을 정해진 분류와 수로 제한한다. */
export function filterFocusDiagnostics(value: unknown): FocusDiagnostics | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (!Array.isArray(raw.samples)) return undefined;
  return {
    attempts: bounded(raw.attempts, MAX_ATTEMPTS),
    samples: raw.samples.slice(0, MAX_ATTEMPTS).map((item) => {
      const s = item && typeof item === "object" ? item as Record<string, unknown> : {};
      return {
        elements: bounded(s.elements, 100), sized: s.sized === true,
        editable: s.editable === true, page_focused: s.page_focused === true,
        page_visible: s.page_visible === true, hit: region(s.hit),
        anchor: region(s.anchor), end: region(s.end), active: region(s.active),
        collapsed: s.collapsed === true,
      };
    }),
  };
}

/** 원문을 읽지 않고 현재 문단, 클릭 대상, 커서를 같은 평가에서 확인한다. */
async function probe(page: EditorPage, selector: string, scope: string) {
  const raw = await page.js<any>(`(() => {
  const focusProbe = true;
  const nodes = document.querySelectorAll(${JSON.stringify(selector)});
  const el = ${scope === ".se-documentTitle" ? "nodes.length === 1" : "nodes.length > 0"} ? nodes[0] : null;
  if (el) el.scrollIntoView({behavior: "instant", block: "center"});
  const r = el?.getBoundingClientRect(), style = el && getComputedStyle(el);
  const sized = !!r && r.width > 0 && r.height > 0;
  const editable = !!el?.isContentEditable && style.visibility === "visible" &&
    style.display !== "none" && Number(style.opacity) > 0;
  const x = r ? r.left + r.width / 2 : 0, y = r ? r.top + r.height / 2 : 0;
  const top = sized ? document.elementFromPoint(x, y) : null;
  const classify = n => {
    if (!n) return "none";
    const e = n.nodeType === 1 ? n : n.parentElement;
    if (e?.closest(".se-documentTitle")) return "title";
    if (e?.closest(".se-component.se-text")) return "body";
    return "other";
  };
  const s = getSelection(), active = document.activeElement;
  const pageFocused = document.hasFocus(), pageVisible = document.visibilityState === "visible";
  const clickable = !!el && sized && editable && pageFocused && pageVisible &&
    el.contains(top) && !!el.closest(${JSON.stringify(scope)});
  const focused = clickable && !!s?.isCollapsed &&
    el.contains(s.anchorNode) && el.contains(s.focusNode) && !!active?.isContentEditable &&
    (active.contains(el) || el.contains(active));
  return { focused, spot: clickable ? {x, y} : null, sample: {
    elements: Math.min(nodes.length, 100), sized, editable,
    page_focused: pageFocused, page_visible: pageVisible,
    hit: classify(top), anchor: classify(s?.anchorNode), end: classify(s?.focusNode),
    active: classify(active), collapsed: !!s?.isCollapsed
  }};
})()`);
  const sample = filterFocusDiagnostics({ attempts: 1, samples: [raw?.sample] })!.samples[0]!;
  const spot = raw?.spot;
  return {
    sample,
    focused: raw?.focused === true,
    spot: spot && Number.isFinite(spot.x) && Number.isFinite(spot.y)
      ? { x: spot.x as number, y: spot.y as number } : null,
  };
}

/** 기존 대기 안에서 문단을 다시 찾고, 가림을 우회하지 않으며 실제 클릭 뒤 커서를 확인한다. */
export async function focusField(page: EditorPage, selector: string, scope: string, tries = 10) {
  const diagnostics: FocusDiagnostics = { attempts: 0, samples: [] };
  for (let i = 0; i < bounded(tries, MAX_ATTEMPTS); i++) {
    diagnostics.attempts++;
    let current = await probe(page, selector, scope);
    if (current.spot) await page.mouseAt(current.spot.x, current.spot.y);
    await page.sleep(page.step(0.3));
    if (current.spot) current = await probe(page, selector, scope);
    diagnostics.samples.push(current.sample);
    if (current.spot && current.focused) return { focused: true, diagnostics };
  }
  return { focused: false, diagnostics };
}

/** boolean을 반환하는 기존 호출 경로다. */
export async function focus(page: EditorPage, selector: string, scope: string, tries = 10) {
  return (await focusField(page, selector, scope, tries)).focused;
}
