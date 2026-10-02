export type ClientMe = { role: "ADMIN" | "MEMBER"; displayName: string };

/**
 * 브라우저에서 내 역할과 이름을 다시 읽는다.
 *
 * <p>레이아웃이 서버에서 읽지 못했을 때 화면 틀이 부른다. 읽지 못하면 `null` 을 준다.
 */
export async function fetchMe(): Promise<ClientMe | null> {
  try {
    const response = await fetch("/api/me", { cache: "no-store" });
    if (!response.ok) return null;
    const body = (await response.json()) as Partial<ClientMe>;
    if (body.role !== "ADMIN" && body.role !== "MEMBER") return null;
    return {
      role: body.role,
      displayName: typeof body.displayName === "string" ? body.displayName : "",
    };
  } catch {
    return null;
  }
}
