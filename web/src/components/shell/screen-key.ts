/** 화면 전환의 단위다. 대화 화면은 주소가 바뀌어도 같은 화면으로 본다. */
export function screenKey(pathname: string): string {
  return pathname === "/" || pathname.startsWith("/chat/") ? "chat" : pathname;
}

/** 빌드 때 굳는 값이다. `off` 가 아니면 켠다. */
export function viewTransitionEnabled(value: string | undefined): boolean {
  return value !== "off";
}
