/** 이름을 정하지 않았을 때 쓰는 중립 이름이다. */
export const DEFAULT_APP_NAME = "fos-assistant";

/**
 * 사이드바 머리와 로그인 화면과 브라우저 제목에 보일 앱 이름이다.
 *
 * 서버에서 요청을 받을 때 `APP_NAME` 을 읽는다. `NEXT_PUBLIC_` 으로 시작하는 이름을 쓰지 않는다.
 * 그 이름은 빌드할 때 값이 코드에 박혀, 같은 이미지를 받아 쓰는 곳마다 이름을 바꿀 수 없다.
 * 화면 부품은 이 함수를 부르지 않고 서버가 넘긴 값을 받는다.
 */
export function appName(): string {
  return process.env.APP_NAME?.trim() || DEFAULT_APP_NAME;
}
