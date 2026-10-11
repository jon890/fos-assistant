/** 준비 판정·커서·제목 판독이 같은 후보를 고르도록 페이지 평가에 넣는 JS 조각이다. */
export function fieldCandidates(selector: string, unique: boolean) {
  return `const nodes = document.querySelectorAll(${JSON.stringify(selector)});
  const candidates = [...nodes].slice(0, 100).map(node => {
    const rect = node.getBoundingClientRect();
    return {node, sized: rect.width > 0 && rect.height > 0,
      editable: node.isContentEditable,
      visible: node.checkVisibility({opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true})};
  });
  const prepared = nodes.length > 100 ? [] : candidates.filter(c => c.sized && c.editable && c.visible);
  const el = ${unique ? "prepared.length === 1 ? prepared[0].node : null" : "nodes[0] ?? null"};`;
}
