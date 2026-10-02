/** 화면 틀이 아는 역할의 상태다. `reading` 과 `failed` 는 역할을 아직 모르는 것이다. */
export type ShellRoleState = "admin" | "member" | "reading" | "failed";

/**
 * 읽은 역할과 다시 읽었는지로 상태를 정한다.
 *
 * <p>역할을 읽지 못한 것(`null`)을 `member` 로 치지 않는다. 그렇게 치면 관리자의 입구가 까닭 없이 사라진다.
 * 다시 읽기 전이면 `reading`, 다시 읽고도 모르면 `failed` 다.
 */
export function shellRoleState(
  role: "ADMIN" | "MEMBER" | null,
  retried: boolean,
): ShellRoleState {
  if (role === "ADMIN") return "admin";
  if (role === "MEMBER") return "member";
  return retried ? "failed" : "reading";
}
