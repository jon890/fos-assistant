/**
 * 사이드바와 설정에서 여는 화면의 제목과 한 줄 설명이다(docs/features/app-shell/design.md 의 「같은 머리 틀」).
 *
 * <p>page 와 `loading.tsx` 가 같은 값을 읽어, 읽는 중에도 머리가 같은 글자로 보인다.
 */
export const PAGE_HEADS = {
  agents: {
    title: "에이전트",
    description:
      "대화를 시작할 때 고르는 에이전트예요. 눌러서 성격과 도구를 고쳐요.",
  },
  connections: {
    title: "외부 서비스 연결",
    description:
      "쓰는 서비스의 계정을 연결하면 에이전트가 그 서비스로 일을 도와요.",
  },
  browser: {
    title: "내 브라우저",
    description: "연결에 필요한 사이트에 로그인해 두는 나만의 브라우저예요.",
  },
  files: {
    title: "파일 공간",
    description: "대화에서 에이전트가 만든 파일이 모이는 곳이에요.",
  },
  devDocs: { title: "개발 문서" },
  tasks: {
    title: "예약 작업",
    description: "정한 시각에 에이전트가 맡아서 하는 일이에요.",
  },
  memory: {
    title: "기억",
    description:
      "에이전트가 대화하며 남긴 기억이에요. 받아들인 기억만 다음 대화에 사용해요. 눌러서 내용을 보고 고치거나 지울 수 있어요.",
  },
  now: {
    title: "지금 볼 것",
    description: "내가 확인하거나 답할 일과 새 알림이 모여요.",
  },
} as const;
