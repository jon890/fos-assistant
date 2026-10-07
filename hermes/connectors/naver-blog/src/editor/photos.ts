import { type Block, type DraftInput, readPhoto } from "../draft.ts";
import {
  ABORTED,
  BODY_SELECTOR,
  blockingPopup,
  click,
  EditorError,
  type EditorPage,
  focusPlaceholder,
  paragraphs,
  requireClearScreen,
  setStage,
} from "./page.ts";
import { imagePlaceholder } from "./text.ts";

export const PHOTO_BUTTON = "button.se-image-toolbar-button";
// `사진 첨부 방식` 창의 `개별사진`. 사진이 둘 이상이면 이 창이 뜬다.
const LAYOUT_EACH = "#image-type-list";
const FIT_TOOLBAR = "button.se-object-arrangement-fit-toolbar-button";
const IMAGES = "[...document.querySelectorAll('.se-component.se-image')]";
export const FITTED_COUNT =
  "document.querySelectorAll('.se-component.se-image .se-component-content-fit').length";

/**
 * 가로챈 파일 선택 창의 input 에 사진 바이트를 넣는 함수. `this` 가 그 input 이다.
 * 경로를 넘기지 않으므로 브라우저가 도는 기계에 파일이 없어도 된다.
 */
const SET_FILE = `function (data, name, mime) {
  const raw = atob(data);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  const dt = new DataTransfer();
  dt.items.add(new File([bytes], name, { type: mime }));
  this.files = dt.files;
  this.dispatchEvent(new Event("input", { bubbles: true }));
  this.dispatchEvent(new Event("change", { bubbles: true }));
  return this.files.length;
}`;

/** 본문에 들어간 사진 블록 수. */
export async function imageCount(page: EditorPage) {
  return (
    (await page.js<number>('document.querySelectorAll(".se-component.se-image").length')) || 0
  );
}

/** 자리만 생긴 상태가 아니라 네이버 사진 주소로 전송됐는지 읽는다. */
async function imageUploaded(page: EditorPage, index: number) {
  return Boolean(
    await page.js(
      `(() => { const img = ${IMAGES}[${index}]` +
        "?.querySelector('img'); return !!(img && img.src.includes('blogfiles.pstatic.net')" +
        " && img.complete && img.naturalWidth > 0); })()",
    ),
  );
}

/** 검사할 사진을 편집 화면에 보이게 한 뒤 네이버 주소를 확인한다. */
async function imageUploadedVisible(page: EditorPage, index: number) {
  const shown = await page.js(
    `(() => { const image = ${IMAGES}[${index}];` +
      " if (!image) return false;" +
      " image.scrollIntoView({behavior: 'instant', block: 'center'}); return true; })()",
  );
  return Boolean(shown) && (await imageUploaded(page, index));
}

/** 화면 밖 미리보기를 다시 보이게 한 뒤 전송이 끝나지 않은 사진의 순번(0부터)을 돌려준다. */
export async function incompleteImages(page: EditorPage, count: number) {
  const missing = new Set<number>();
  for (let index = 0; index < count; index++) {
    if (!(await page.waitUntil(() => imageUploadedVisible(page, index), page.photoWait(15))))
      missing.add(index);
  }
  // 뒤쪽 사진으로 스크롤하는 동안 앞쪽 사진이 다시 바뀌었는지도 확인한다.
  for (let index = 0; index < count; index++)
    if (!(await imageUploaded(page, index))) missing.add(index);
  return [...missing].sort((a, b) => a - b);
}

/** index 번째 사진을 골라 `문서 너비` 를 적용하고 결과 클래스를 확인한다. */
async function fitImage(page: EditorPage, index: number) {
  const image = `${IMAGES}[${index}].querySelector('.se-module-image')`;
  if (!(await page.waitUntil(() => page.mouseClick(image), page.step(10)))) return false;
  const fitted = `${IMAGES}[${index}]?.querySelector('.se-component-content-fit') !== null`;
  if (await page.js(fitted)) return true;
  const toolbarShown = async () =>
    Boolean(
      await page.js(
        `(() => { const e = document.querySelector(${JSON.stringify(FIT_TOOLBAR)});` +
          " if (!e) return false; const r = e.getBoundingClientRect();" +
          " return r.width > 0 && r.height > 0; })()",
      ),
    );
  if (!(await page.waitUntil(toolbarShown, page.step(10)))) return false;
  if (!(await click(page, FIT_TOOLBAR))) return false;
  return page.waitUntil(async () => Boolean(await page.js(fitted)), page.step(10));
}

/** 「사진 첨부 방식」 창이 보이면 개별사진을 고른다. */
async function chooseEachLayout(page: EditorPage) {
  const shown = await page.js(
    `(() => { const e = document.querySelector(${JSON.stringify(LAYOUT_EACH)});` +
      " if (!e) return false; const r = e.getBoundingClientRect();" +
      " return r.width > 0 && r.height > 0; })()",
  );
  if (shown) await click(page, LAYOUT_EACH);
}

/**
 * 사진 단추를 눌러 열리는 파일 선택 창을 가로채 사진 바이트를 넣는다. 실패하면 그 까닭을 돌려준다.
 * 네이버는 단추를 누를 때 `input[type=file]` 을 만들고 창이 닫히면 지운다.
 * 선택 창 이벤트의 `backendNodeId` 로 같은 연결에서 바로 그 input 을 찾아 넣는다.
 */
async function attachPhoto(
  page: EditorPage,
  photo: { bytes: Uint8Array; mime: string; name: string },
) {
  await page.call("DOM.enable");
  await page.call("Runtime.enable");
  await page.call("Page.setInterceptFileChooserDialog", { enabled: true });
  try {
    const chooser = page.expectEvent<{ backendNodeId?: number }>(
      "Page.fileChooserOpened",
      page.photoWait(30),
    );
    // JS 의 `.click()` 은 사용자 활성화로 인정되지 않아 파일 선택 창이 열리지 않는다.
    if (!(await click(page, PHOTO_BUTTON))) return "사진 단추를 찾지 못했다";
    const opened = await chooser;
    if (!opened) return "파일 선택 창을 가로채지 못했다";
    if (!opened.backendNodeId) return "파일 선택 창에 backendNodeId 가 없다";
    const resolved = await page.call<{ object?: { objectId?: string } }>("DOM.resolveNode", {
      backendNodeId: opened.backendNodeId,
    });
    const objectId = resolved?.object?.objectId;
    if (!objectId) return "파일 input 을 찾지 못했다";
    const result = await page.call("Runtime.callFunctionOn", {
      objectId,
      functionDeclaration: SET_FILE,
      arguments: [
        { value: Buffer.from(photo.bytes).toString("base64") },
        { value: photo.name },
        { value: photo.mime },
      ],
      returnByValue: true,
    });
    if (result?.exceptionDetails) return "파일 input 에 사진을 넣지 못했다";
    return "";
  } finally {
    await page.call("Page.setInterceptFileChooserDialog", { enabled: false });
  }
}

/**
 * 사진 자리마다 자리표시 글을 지우고 사진 바이트를 넣는다.
 * 전송이 끝나면 `문서 너비` 를 적용한다. 실패하면 `photo_upload_failed` 와 몇 번째 사진 자리인지다.
 */
export async function photos(
  page: EditorPage,
  input: DraftInput,
  blocks: Block[],
  draftHash: string,
) {
  await setStage(page, draftHash, "photos", false);
  const images = blocks.filter(
    (block): block is Extract<Block, { type: "image" }> => block.type === "image",
  );
  if (images.length === 0) {
    await setStage(page, draftHash, "photos", true);
    return;
  }
  const failed = (photo: number, message: string) =>
    page.fail("photo_upload_failed", message, { photo });

  const note = await requireClearScreen(page);
  if (note) throw page.fail("editor_failed", `화면을 덮은 알림이 있어 사진을 넣지 못한다: ${note}`);

  let inserted = await imageCount(page);
  if (inserted > images.length)
    throw page.fail("editor_failed", `사진이 초안보다 많다: 화면 ${inserted}개, 초안 ${images.length}개`);
  const fitted = (await page.js<number>(FITTED_COUNT)) || 0;
  if (fitted !== inserted)
    throw page.fail("editor_failed", "기존 사진 중 문서 너비가 아닌 것이 있다");
  const pending = await incompleteImages(page, inserted);
  if (pending.length) throw failed(pending[0]! + 1, "기존 사진 중 전송이 끝나지 않은 것이 있다");
  const visible = await paragraphs(page, BODY_SELECTOR);
  for (const [index, block] of images.slice(0, inserted).entries()) {
    const marker = imagePlaceholder(block);
    if (
      visible.some(
        (line) => marker === line || (marker.startsWith(line) && line.length >= marker.length - 10),
      )
    )
      throw failed(index + 1, "사진과 자리표시가 함께 남아 있다");
  }

  for (const [offset, block] of images.slice(inserted).entries()) {
    const number = inserted + offset + 1;
    let photo;
    try {
      photo = await readPhoto(input, block.file);
    } catch {
      throw failed(number, "사진 파일을 읽지 못했다");
    }
    if (!(await focusPlaceholder(page, imagePlaceholder(block))))
      throw failed(number, "사진 자리를 찾지 못했다");
    const before = await imageCount(page);
    let problem: string;
    try {
      problem = await attachPhoto(page, { ...photo, name: block.file });
    } catch (error) {
      if (error instanceof EditorError && error.message === ABORTED) throw error;
      throw failed(number, "파일 선택 창에 사진을 넣지 못했다");
    }
    if (problem) throw failed(number, problem);
    const landed = await page.waitUntil(async () => {
      await chooseEachLayout(page);
      return (await imageCount(page)) > before;
    }, page.photoWait(30));
    if (!landed) throw failed(number, "사진이 본문에 들어가지 않았다");
    const settled = await page.waitUntil(
      async () =>
        (await imageUploadedVisible(page, before)) || Boolean(await blockingPopup(page)),
      page.photoWait(90),
    );
    if (!settled) throw failed(number, "사진 전송이 끝나지 않았다");
    if (!(await imageUploaded(page, before)))
      throw failed(number, `사진 전송 중 알림이 떴다: ${await blockingPopup(page)}`);
    if (!(await fitImage(page, before)))
      throw failed(number, "사진에 `문서 너비` 를 적용하지 못했다");
    inserted += 1;
    const incomplete = await incompleteImages(page, inserted);
    if (incomplete.length)
      throw failed(incomplete[0]! + 1, "사진을 배치한 뒤 네이버 주소로 읽지 못한 사진이 있다");
  }

  if ((await imageCount(page)) !== images.length)
    throw failed(images.length, "사진 개수가 초안과 다르다");
  const incomplete = await incompleteImages(page, images.length);
  if (incomplete.length) throw failed(incomplete[0]! + 1, "사진 전송이 끝나지 않은 것이 있다");
  await setStage(page, draftHash, "photos", true);
}
