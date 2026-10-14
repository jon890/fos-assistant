import type { EditorPage } from "./page.ts";
import { fieldCandidates, LAYER_DIAGNOSIS_JS } from "./fields.ts";

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
  input_frame: boolean;
  /** 클릭 지점을 차지한 문서 밖 요소. 태그와 앞 클래스 둘로만 적는다. 가리지 않으면 `null` 이다. */
  covered_by: string | null;
  /** 지금 보이는 레이어 요소. 태그와 앞 클래스 둘로만 적고 여덟 개까지다. */
  layers: string[];
};
export type FocusDiagnostics = { attempts: number; samples: FocusSample[] };
const MAX_ATTEMPTS = 10;
const bounded = (value: unknown, max: number) =>
  typeof value === "number" && Number.isFinite(value)
    ? Math.max(0, Math.min(max, Math.floor(value)))
    : 0;
const MAX_LAYERS = 8;
const ELEMENT_NAME = /^[A-Za-z0-9_.-]{1,80}$/;
const elementName = (value: unknown) =>
  typeof value === "string" && ELEMENT_NAME.test(value) ? value : null;
const region = (value: unknown): Region =>
  value === "title" || value === "body" || value === "none" ? value : "other";

/** 브라우저 응답과 예외의 추가 값을 정해진 분류와 수로 제한한다. */
export function filterFocusDiagnostics(
  value: unknown,
): FocusDiagnostics | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value))
    return undefined;
  const raw = value as Record<string, unknown>;
  if (!Array.isArray(raw.samples)) return undefined;
  return {
    attempts: bounded(raw.attempts, MAX_ATTEMPTS),
    samples: raw.samples.slice(0, MAX_ATTEMPTS).map((item) => {
      const s =
        item && typeof item === "object"
          ? (item as Record<string, unknown>)
          : {};
      return {
        elements: bounded(s.elements, 100),
        sized: s.sized === true,
        editable: s.editable === true,
        page_focused: s.page_focused === true,
        page_visible: s.page_visible === true,
        hit: region(s.hit),
        anchor: region(s.anchor),
        end: region(s.end),
        active: region(s.active),
        collapsed: s.collapsed === true,
        input_frame: s.input_frame === true,
        covered_by: elementName(s.covered_by),
        layers: Array.isArray(s.layers)
          ? s.layers
              .slice(0, MAX_LAYERS)
              .map(elementName)
              .filter((name): name is string => name !== null)
          : [],
      };
    }),
  };
}

/** 원문을 읽지 않고 현재 문단, 클릭 대상, 커서를 같은 평가에서 확인한다. */
async function probe(page: EditorPage, selector: string, scope: string) {
  const raw = await page.js<any>(`(() => {
  const focusProbe = true;
  ${fieldCandidates(selector, scope === ".se-documentTitle")}
  if (el) el.scrollIntoView({behavior: "instant", block: "center"});
  const r = el?.getBoundingClientRect();
  const sized = !!r && r.width > 0 && r.height > 0;
  const editable = !!el && (el.isContentEditable || bufferReady) &&
    el.checkVisibility({opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true});
  // 가운데가 다른 요소에 덮이면 같은 문단의 다른 지점을 본다. 덮은 요소를 치우거나 강제로 누르지는 않는다.
  const spots = r ? [0.5, 0.1, 0.9, 0.25, 0.75].map(f => ({x: r.left + Math.max(4, r.width * f), y: r.top + r.height / 2})) : [];
  const hits = sized ? spots.map(p => document.elementFromPoint(p.x, p.y)) : [];
  const found = hits.findIndex(h => !!el && el.contains(h));
  const pick = found >= 0 ? found : 0;
  const x = spots[pick]?.x ?? 0, y = spots[pick]?.y ?? 0;
  const top = hits[pick] ?? null;
  ${LAYER_DIAGNOSIS_JS}
  const classify = n => {
    if (!n) return "none";
    const e = n.nodeType === 1 ? n : n.parentElement;
    if (e?.closest(".se-documentTitle")) return "title";
    if (e?.closest(".se-component.se-text")) return "body";
    return "other";
  };
  const s = getSelection(), active = document.activeElement;
  const innerActive = bufferDocument?.activeElement, innerSelection = bufferDocument?.getSelection();
  const inputFrame = bufferReady && active === bufferFrame && bufferDocument.hasFocus() &&
    innerActive === bufferDocument.body && innerActive.isContentEditable && !!innerSelection?.isCollapsed &&
    innerActive.contains(innerSelection.anchorNode) && innerActive.contains(innerSelection.focusNode);
  const pageFocused = document.hasFocus(), pageVisible = document.visibilityState === "visible";
  const clickable = !!el && sized && editable && pageFocused && pageVisible &&
    el.contains(top) && !!el.closest(${JSON.stringify(scope)});
  const focused = clickable && !!s?.isCollapsed &&
    el.contains(s.anchorNode) && el.contains(s.focusNode) && (inputFrame ||
      (el.isContentEditable && !!active?.isContentEditable && (active.contains(el) || el.contains(active))));
  return { focused, spot: clickable ? {x, y} : null, sample: {
    elements: Math.min(nodes.length, 100), sized, editable,
    page_focused: pageFocused, page_visible: pageVisible,
    hit: classify(top), anchor: classify(s?.anchorNode), end: classify(s?.focusNode),
    active: classify(active), collapsed: !!s?.isCollapsed, input_frame: !!inputFrame,
    covered_by: sized && !el.contains(top) ? name(top) : null,
    layers: sized && !el.contains(top) ? visibleLayers(${MAX_LAYERS}) : []
  }};
})()`);
  const sample = filterFocusDiagnostics({
    attempts: 1,
    samples: [raw?.sample],
  })!.samples[0]!;
  const spot = raw?.spot;
  return {
    sample,
    focused: raw?.focused === true,
    spot:
      spot && Number.isFinite(spot.x) && Number.isFinite(spot.y)
        ? { x: spot.x as number, y: spot.y as number }
        : null,
  };
}

/** 기존 대기 안에서 문단을 다시 찾고, 덮은 요소를 누르거나 치우지 않고 같은 문단에서 덮이지 않은 지점만 누르며 실제 클릭 뒤 커서를 확인한다. */
export async function focusField(
  page: EditorPage,
  selector: string,
  scope: string,
  tries = 10,
) {
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
export async function focus(
  page: EditorPage,
  selector: string,
  scope: string,
  tries = 10,
) {
  return (await focusField(page, selector, scope, tries)).focused;
}
