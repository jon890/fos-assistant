import type { EditorPage } from "./page.ts";

/** 알림에서 단추 이름으로 싣는 글은 이 목록뿐이다. 그 밖의 글은 `other` 로 적는다. */
const KNOWN_LABELS = [
  "취소",
  "확인",
  "닫기",
  "계속",
  "나중에",
  "다음에",
] as const;
const KINDS = ["draft_notice", "help", "unknown"] as const;
export type PopupKind = (typeof KINDS)[number];
export type PopupDiagnostics = {
  kind: PopupKind;
  /** 알림 상자 요소를 태그와 앞 클래스 둘로만 적는다. */
  box: string | null;
  /** 단추 이름. 알려진 이름만 그대로 적는다. */
  buttons: string[];
  /** 안전한 쪽으로 닫아 보았는지. */
  dismissed: boolean;
};

const MAX_BUTTONS = 8;
/** 발행이나 저장, 삭제로 이어질 수 있는 단추가 하나라도 있으면 어떤 단추도 누르지 않는다. */
const RISKY = /발행|저장|등록|게시|공개|삭제|전송|업로드|완료/;
/** 종류 판정과 단추 검사, 누르기가 같은 상자 하나를 보게 하는 선택자다. */
const BOX = ".se-popup-container, [role=dialog]";

const q = (value: unknown) => JSON.stringify(value);

/**
 * 화면을 덮은 알림 상자의 종류를 글 없이 읽는다. 상자 안의 본문 글은 읽어도 돌려주지 않고 종류 판정에만 쓴다.
 * 단추 이름은 네이버 화면의 고정 문구라 읽지만, 알려진 이름이 아니면 호출자가 `other` 로 바꾼다.
 */
const PROBE = `(() => {
  const dim = [...document.querySelectorAll("[class*=popup-dim]")].find(e => {
    const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
    return r.width > 100 && s.display !== "none" && s.visibility !== "hidden";
  });
  if (!dim) return null;
  const box = document.querySelector(${q(BOX)});
  if (!box) return JSON.stringify({box: null, buttons: [], draft: false, help: false, risky: false});
  const name = e => (e.tagName.toLowerCase() + [...e.classList].slice(0, 2).map(c => "." + c).join(""))
    .replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 80);
  const text = box.innerText.replace(/\\s+/g, " ");
  return JSON.stringify({
    box: name(box),
    buttons: [...box.querySelectorAll("button")].slice(0, ${MAX_BUTTONS}).map(b => b.innerText.trim().slice(0, 20)),
    // 위험 여부는 단추 전체로 본다. 진단에 싣는 목록만 여덟 개로 자른다.
    risky: [...box.querySelectorAll("button")].some(b => new RegExp(${q(RISKY.source)}).test(b.innerText)),
    draft: text.includes("작성 중인 글이 있습니다") && text.includes("이어서 작성하시겠습니까"),
    help: /help/i.test(box.className) || /help/i.test(box.id || ""),
  });
})()`;

type Probe = {
  box: unknown;
  buttons: unknown;
  draft: boolean;
  help: boolean;
  risky: boolean;
};

/** 원문 없이 알림의 종류와 단추를 읽는다. 알림이 없으면 `null` 이다. */
export async function probePopup(page: EditorPage): Promise<{
  diagnostics: PopupDiagnostics;
  labels: string[];
  risky: boolean;
} | null> {
  const raw = await page.js<string | null>(PROBE);
  if (!raw) return null;
  let probe: Probe;
  try {
    probe = JSON.parse(raw) as Probe;
  } catch {
    return null;
  }
  const labels = Array.isArray(probe.buttons)
    ? probe.buttons.filter((l): l is string => typeof l === "string")
    : [];
  return {
    labels,
    risky: probe.risky !== false,
    diagnostics: {
      kind: probe.draft ? "draft_notice" : probe.help ? "help" : "unknown",
      box:
        typeof probe.box === "string" &&
        /^[A-Za-z0-9_.-]{1,80}$/.test(probe.box)
          ? probe.box
          : null,
      buttons: labels
        .slice(0, MAX_BUTTONS)
        .map((label) =>
          (KNOWN_LABELS as readonly string[]).includes(label) ? label : "other",
        ),
      dismissed: false,
    },
  };
}

/** 브라우저 응답의 추가 값을 정해진 분류와 수로 제한한다. */
export function filterPopupDiagnostics(
  value: unknown,
): PopupDiagnostics | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value))
    return undefined;
  const raw = value as Record<string, unknown>;
  const known: readonly string[] = [...KNOWN_LABELS, "other"];
  return {
    kind: KINDS.includes(raw.kind as PopupKind)
      ? (raw.kind as PopupKind)
      : "unknown",
    box:
      typeof raw.box === "string" && /^[A-Za-z0-9_.-]{1,80}$/.test(raw.box)
        ? raw.box
        : null,
    buttons: Array.isArray(raw.buttons)
      ? raw.buttons
          .slice(0, MAX_BUTTONS)
          .map((label) =>
            known.includes(label as string) ? (label as string) : "other",
          )
      : [],
    dismissed: raw.dismissed === true,
  };
}

/**
 * 알려진 알림을 안전한 쪽으로 한 번 닫는다. 닫았으면 `true` 이고, 모르는 알림이거나 위험한 단추가 있으면 누르지 않고 `false` 다.
 * 「작성 중인 글」 알림은 이어 쓰지 않는 단추(취소)를, 도움말 알림은 닫기 단추를 누른다.
 */
export async function dismissKnownPopup(page: EditorPage) {
  const found = await probePopup(page);
  if (!found) return false;
  page.popup = found.diagnostics;
  const { kind } = found.diagnostics;
  if (kind === "unknown" || found.risky) return false;
  const label =
    kind === "draft_notice"
      ? "취소"
      : (found.labels.find((l) => l === "닫기") ??
        found.labels.find((l) => l === "확인"));
  if (!label || !found.labels.includes(label)) return false;
  const clicked = await page.mouseClick(
    `[...(document.querySelector(${q(BOX)})?.querySelectorAll("button") ?? [])].find(b => b.innerText.trim() === ${q(label)})`,
  );
  if (!clicked) return false;
  found.diagnostics.dismissed = true;
  page.popup = found.diagnostics;
  await page.sleep(page.step(0.5));
  return true;
}

/**
 * 글을 넣는 도중에 뜬 알림을 누르기 전에 한 번 닫아 본다. 알림이 없으면 아무것도 하지 않는다.
 * 닫는 규칙은 `dismissKnownPopup` 과 같아서 모르는 알림과 위험한 단추가 든 알림은 누르지 않는다.
 * 닫지 못한 알림은 종류를 `page.popup` 에 남겨 뒤의 실패가 `popup_diagnostics` 로 싣게 한다.
 */
export async function settleKnownPopup(page: EditorPage) {
  // 닫지 못한 알림의 진단은 `dismissKnownPopup` 이 읽은 값으로 이미 남긴다. 여기서 다시 읽어 덮지 않는다.
  if (await dismissKnownPopup(page)) {
    // 닫혀 화면이 깨끗해졌으니 알림과 상관없는 뒤의 실패에 지난 진단을 붙이지 않는다.
    page.popup = null;
  }
}
