import { cva } from "class-variance-authority";

/** 비서의 답 앞에 두는 둥근 머리글자다. 답과 답을 기다리는 줄이 같은 모양을 쓴다 */
export const assistantAvatar = cva(
  "flex size-8 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground",
);

/** 마우스를 올리거나 초점이 들어올 때만 보이는 보낸 시각이다 */
export const revealedTime = cva(
  "invisible text-xs text-muted-foreground group-hover:visible group-focus-within:visible",
);

/** 사진을 그릴 수 없을 때 사진 자리에 두는 칸이다. 대화에 붙은 사진은 크게, 입력창의 미리보기는 작게 그린다 */
export const attachmentPlaceholder = cva(
  "flex items-center justify-center rounded-md border border-border bg-muted text-center text-muted-foreground",
  {
    variants: {
      size: {
        message: "size-24 p-2 text-xs",
        preview: "size-16 flex-col gap-1 p-1 text-[0.625rem] leading-tight",
      },
    },
  },
);
