/** 넓은 화면의 사이드바를 접었는지다. 그 브라우저에 남기고, 저장할 수 없어도 현재 화면은 따른다. */
const SIDEBAR_COLLAPSED_KEY = "sidebar-collapsed";
/** 이번 화면에서 접거나 편 값이다. 저장소에 적지 못해도 현재 화면은 이 값을 따른다. 아직 바꾸지 않았으면 null 이다 */
let collapsedOverride: boolean | null = null;
const collapsedListeners = new Set<() => void>();

export function subscribeCollapsed(onChange: () => void): () => void {
  collapsedListeners.add(onChange);
  return () => collapsedListeners.delete(onChange);
}

export function readCollapsed(): boolean {
  if (collapsedOverride !== null) return collapsedOverride;
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "1";
  } catch {
    /* 저장소를 막은 브라우저에서도 화면을 연다. */
    return false;
  }
}

export function updateCollapsed(value: boolean): void {
  collapsedOverride = value;
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, value ? "1" : "0");
  } catch {
    /* 저장할 수 없어도 현재 화면은 바꾼다. */
  }
  collapsedListeners.forEach((onChange) => onChange());
}
