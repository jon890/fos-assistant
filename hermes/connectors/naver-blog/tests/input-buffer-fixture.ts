import type { EditorPage } from "../src/editor/page.ts";

/** 관측한 비편집 문단·별도 입력 프레임 관계를 합성한다. 실제 SmartEditor 구현을 대신하지 않는다. */
export async function installInputBuffer(page: EditorPage) {
  await page.js(`(() => {
    const title = document.getElementById('title'), body = document.getElementById('body');
    title.contentEditable = body.contentEditable = 'false';
    window.SmartEditor = {_editors: {synthetic: {getDocumentData() {return {};}}}};
    const frame = document.createElement('iframe');frame.id = 'input_buffer_100';
    frame.style.cssText = 'position:absolute;left:-9999px;top:0;width:17px;height:478px';
    document.body.append(frame);
    const doc = frame.contentDocument, input = doc.body;input.contentEditable = 'true';
    let current = title;
    const caret = () => getSelection().collapse(current, current.childNodes.length);
    const activate = target => {
      current = target;input.focus();doc.getSelection().collapse(input, 0);caret();
    };
    for (const field of [title, body]) field.addEventListener('mouseup', () => activate(field));
    input.addEventListener('beforeinput', event => {
      event.preventDefault();
      if (event.inputType.startsWith('delete')) current.textContent = '';
      else if (event.inputType === 'insertText') current.textContent += event.data ?? '';
      caret();
    });
    input.addEventListener('keydown', event => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      const next = document.createElement('p');next.className = 'se-text-paragraph';
      current.after(next);current = next;caret();
    });
  })()`);
}
