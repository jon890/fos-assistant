/**
 * 도구 묶음의 이름과 설명을 화면 문구로 옮긴다.
 *
 * <p>Hermes 가 주는 이름은 영어이고 설명은 평서체라 화면에 그대로 쓰지 않는다. 키는 묶음의 `name` 이다.
 * 이 파일은 다른 모듈을 부르지 않는다. 단위 테스트가 `node --test` 로 직접 불러서다.
 */
type ToolsetText = { label: string; description: string };

const TEXTS: Record<string, ToolsetText> = {
  web: { label: "웹 검색", description: "웹에서 찾아봐요" },
  vision: { label: "사진 보기", description: "올린 사진을 읽어요" },
  todo: { label: "할 일 정리", description: "긴 일을 할 일로 나눠 챙겨요" },
  clarify: { label: "되묻기", description: "모호하면 먼저 물어봐요" },
  session_search: {
    label: "지난 대화 찾기",
    description: "예전 대화에서 찾아봐요",
  },
  skills: { label: "스킬", description: "올려 둔 스킬을 써요" },
  tts: { label: "소리 내어 읽기", description: "글을 음성으로 읽어요" },
  delegation: {
    label: "도우미에게 맡기기",
    description: "일을 나눠 도우미에게 맡겨요",
  },
  terminal: { label: "명령 실행", description: "서버에서 명령을 실행해요" },
  file: { label: "파일", description: "파일을 읽고 써요" },
  code_execution: { label: "코드 실행", description: "코드를 돌려 계산해요" },
  browser: { label: "브라우저", description: "웹 페이지를 열어 조작해요" },
  computer_use: {
    label: "컴퓨터 조작",
    description: "화면을 보고 컴퓨터를 조작해요",
  },
  cronjob: { label: "예약 실행", description: "정한 시각에 일을 해요" },
  image_gen: { label: "그림 만들기", description: "그림을 만들어요" },
  video_gen: { label: "동영상 만들기", description: "동영상을 만들어요" },
  homeassistant: {
    label: "집 기기",
    description: "집의 기기를 살피고 조작해요",
  },
  spotify: { label: "Spotify", description: "음악을 틀고 멈춰요" },
  discord: { label: "Discord", description: "Discord 에 글을 보내고 읽어요" },
};

/** 표에 없는 묶음은 받은 이름과 설명을 그대로 돌려준다. */
export function toolsetText(name: string, fallback: ToolsetText): ToolsetText {
  return Object.hasOwn(TEXTS, name) ? TEXTS[name]! : fallback;
}
