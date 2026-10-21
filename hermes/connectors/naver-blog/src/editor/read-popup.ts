import type { EditorPage } from "./page.ts";
import {
  dismissKnownPopup,
  type PopupDiagnostics,
  probePopup,
} from "./popup.ts";
import { publishDiagnosis } from "./settings.ts";

/** 도움말 알림이 이어서 뜰 수 있어 닫고 다시 보는 횟수의 상한. */
const SETTLE_TRIES = 3;

/**
 * 읽기 도구가 화면을 읽기 전에 열린 뒤 늦게 뜬 알림을 처리한다. 판별과 누르기는 저장 흐름의 `dismissKnownPopup` 을 그대로 쓴다.
 * 도움말 알림은 닫고 이어 가며, 「작성 중인 글」 알림은 어떤 단추도 누르지 않고 `draft_notice` 로 알린다.
 * 모르는 알림이거나 위험한 단추가 든 알림은 누르지 않고 `editor_failed` 다. 알림의 종류는 `popup_diagnostics` 로 싣는다.
 * `screenNeeded` 가 거짓이면 화면이 필요 없는 읽기라서 그런 알림이 남아도 던지지 않고 `blocked` 로 알린다.
 * 저장 흐름과 달리 「작성 중인 글」 알림의 취소를 누르지 않는 것은 읽기가 사용자의 자동저장 글을 버리게 하지 않으려는 것이다.
 */
export async function settleReadPopup(
  page: EditorPage,
  { screenNeeded = true } = {},
): Promise<"clear" | "draft_notice" | "blocked"> {
  // 닫기는 SETTLE_TRIES 번까지만 누르고, 마지막으로 닫은 뒤의 화면은 누르지 않고 한 번 더 본다.
  for (let i = 0; i < SETTLE_TRIES; i++) {
    // 지난 판별이 남기지 않게 비운다. 알림이 없으면 `dismissKnownPopup` 이 아무것도 쓰지 않는다.
    page.popup = null;
    if (await dismissKnownPopup(page, { keepDraftNotice: true })) continue;
    return classify(page, screenNeeded);
  }
  page.popup = null;
  const found = await probePopup(page);
  if (found) page.popup = found.diagnostics;
  return classify(page, screenNeeded);
}

/** 읽어 둔 `page.popup` 으로 결과를 정한다. */
async function classify(page: EditorPage, screenNeeded: boolean) {
  // `dismissKnownPopup` 과 `probePopup` 이 값을 쓰므로 위의 비움으로 좁혀진 타입을 되돌린다.
  const seen = page.popup as PopupDiagnostics | null;
  if (!seen) return "clear";
  if (seen.kind === "draft_notice") return "draft_notice";
  if (!screenNeeded) return "blocked";
  throw page.fail("editor_failed", "화면을 덮은 알림을 닫지 못한다", {
    diagnosis: await publishDiagnosis(page),
  });
}
