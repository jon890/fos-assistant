export const ACCEPTED_TYPES = [
  "image/jpeg",
  "image/png",
  "image/gif",
  "image/webp",
];
export const MAX_ATTACHMENTS = 30;
export const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
/** 미리보기의 긴 변 길이다. 열 장에 33MB 였던 실측이 있어 원본을 그대로 그리지 않는다 */
export const THUMBNAIL_MAX_SIDE = 192;

export type AttachmentItem = {
  key: string;
  file: File;
  previewUrl: string;
  status: "uploading" | "done" | "error";
  attachmentId: number | null;
  errorMessage: string | null;
  /** 이 첨부가 올라간 대화의 공개 식별자다. 지울 때 이 식별자로 서버 DELETE 를 부른다 */
  conversationId: string | null;
};

/** 서버에 보내기 전, 대화 안에서 업로드와 실패를 보여 주는 내 메시지다. */
export type OutgoingMessage = {
  text: string;
  items: AttachmentItem[];
  errorMessage: string | null;
  retry(key: string): void;
  omit(key: string): void;
  retrySend(): void;
};

export async function buildThumbnail(file: File): Promise<string> {
  const bitmap = await createImageBitmap(file);
  const longSide = Math.max(bitmap.width, bitmap.height);
  const scale = Math.min(1, THUMBNAIL_MAX_SIDE / longSide);
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) {
    bitmap.close();
    throw new Error("캔버스를 만들지 못했어요.");
  }
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();

  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (result) =>
        result
          ? resolve(result)
          : reject(new Error("미리보기를 만들지 못했어요.")),
      "image/png",
    );
  });
  return URL.createObjectURL(blob);
}
