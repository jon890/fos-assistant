/** 준비 판정·커서·제목 판독이 같은 후보를 고르도록 페이지 평가에 넣는 JS 조각이다. */
export function fieldCandidates(selector: string, unique: boolean) {
  return `const editorRegistry = window.SmartEditor?._editors;
  const editors = editorRegistry && typeof editorRegistry === 'object' ? Object.values(editorRegistry) : [];
  const inputFrames = document.querySelectorAll('iframe[id^="input_buffer"]');
  const bufferFrame = inputFrames.length === 1 && /^input_buffer_?[0-9]+$/.test(inputFrames[0].id) ? inputFrames[0] : null;
  let bufferDocument = null;
  try { bufferDocument = bufferFrame?.contentDocument; } catch { /* 다른 출처는 거절한다. */ }
  const bufferReady = editors.length === 1 && typeof editors[0]?.getDocumentData === 'function' &&
    !!bufferDocument?.body?.isContentEditable;
  const nodes = document.querySelectorAll(${JSON.stringify(selector)});
  const candidates = [...nodes].slice(0, 100).map(node => {
    const rect = node.getBoundingClientRect();
    return {node, sized: rect.width > 0 && rect.height > 0,
      editable: node.isContentEditable,
      visible: node.checkVisibility({opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true})};
  });
  const prepared = nodes.length > 100 ? [] : candidates.filter(c => c.sized && (c.editable || bufferReady) && c.visible);
  const el = ${unique ? "prepared.length === 1 ? prepared[0].node : null" : "nodes[0] ?? null"};`;
}

/**
 * 요소 이름과 보이는 레이어를 모으는 페이지 평가용 JS 조각이다. 커서 진단과 발행 설정 진단이 같은 기준을 쓴다.
 * 이름은 태그와 앞 클래스 둘로만 적고 80자까지다. 편집기 도구 막대 안의 layer 클래스는 늘 보이므로 뺀다.
 */
export const LAYER_DIAGNOSIS_JS = `const name = e => e ? (e.tagName.toLowerCase() + [...e.classList].slice(0, 2).map(c => "." + c).join(""))
    .replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 80) : null;
  const shown = e => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && s.display !== "none" && s.visibility !== "hidden"; };
  const visibleLayers = max => [...new Set([...document.querySelectorAll(
    "[class*=layer], [class*=popup], [class*=dim], [class*=tooltip], [class*=balloon], [role=dialog]")]
    .filter(e => shown(e) && !e.closest("[class*=toolbar]")).map(name))].slice(0, max);`;
