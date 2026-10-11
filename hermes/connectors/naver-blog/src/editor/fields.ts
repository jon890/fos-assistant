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
