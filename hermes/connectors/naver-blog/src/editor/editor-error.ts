/** 편집기 단계가 실패를 알리는 코드. 작업 상태의 `error.code` 가 된다. */
export type EditorErrorCode =
  | "login_required"
  | "category_not_found"
  | "place_not_unique"
  | "photo_upload_failed"
  | "editor_failed"
  | "save_unconfirmed"
  | "draft_not_found"
  | "draft_changed"
  | "changes_mismatch"
  | "component_not_found"
  | "backup_failed";

/** 편집기 단계의 실패. `message` 에는 CDP 주소와 파일 경로, 초안 본문을 싣지 않는다. */
export class EditorError extends Error {
  constructor(
    readonly code: EditorErrorCode,
    readonly stage: string,
    message: string,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = "EditorError";
  }
}
