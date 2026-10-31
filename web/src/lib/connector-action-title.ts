/** 도구가 제목을 선언하지 않았을 때 Control Plane 이 주는 제목이다. 값은 `ConnectorActionView.UNNAMED_TITLE` 과 같다. */
export const UNNAMED_ACTION_TITLE = "이름 없는 동작";

/**
 * 카드와 허락 목록에 보일 도구 이름이다. 선언한 제목이 없고 원래 도구 이름이 확인된 줄은 그 이름을 보인다.
 * `assistant.json` 이 없는 표준 플러그인의 도구는 제목이 없어 원래 이름이 사람이 알아볼 유일한 단서다.
 */
export function actionDisplayTitle(
  title: string | null,
  toolName: string | null,
): string {
  const declared = title?.trim();
  if (declared && declared !== UNNAMED_ACTION_TITLE) return declared;
  return toolName?.trim() || UNNAMED_ACTION_TITLE;
}
