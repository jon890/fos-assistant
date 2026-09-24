/** 사진을 올려 메시지에 붙이고, 에이전트에게 그 자리가 알려지고, 지운 뒤에도 자리가 남는 것을 검사한다. */
import { crc32, deflateSync } from "node:zlib";
import { call, expect, expectStatus, step, upload, type Context, type Scenario } from "../harness.ts";

/** run.ts 가 Control Plane 에 넘긴 에이전트 쪽 경로다. */
const AGENT_ROOT = "/agent-side/attachments";

type Started = { conversationId: number };
type Attachment = { id: number; originalName: string; visible: boolean };
type Message = { id: number; role: "USER" | "ASSISTANT"; content: string; attachments: Attachment[] };

export const chatAttachmentScenario: Scenario = {
  name: "사진 첨부",

  async run(context) {
    const token = context.tokens.dad;

    step("빈 대화를 먼저 만들고 사진 한 장을 올린다");
    const { conversationId } = expectStatus(
      await call(context, "/chat/conversations", {
        method: "POST",
        token,
        body: { agentCode: "dad" },
      }),
      200,
      "빈 대화 만들기",
    ).json<Started>();
    const photo = expectStatus(
      await upload(context, `/chat/conversations/${conversationId}/attachments`, {
        token,
        field: "file",
        fileName: "바다.png",
        contentType: "image/png",
        bytes: onePixelPng(),
      }),
      200,
      "사진 올리기",
    ).json<Attachment>();

    step("사진을 붙여 보내면 Hermes 가 받은 입력에 그 자리와 파일 이름이 있다");
    const text = "이 사진 설명해 줘";
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token,
        body: { conversationId, text, attachmentIds: [photo.id] },
      }),
      200,
      "사진을 붙여 보내기",
    );
    const input = context.hermes.lastSubmittedInput() ?? "";
    expect(
      input.includes(`${AGENT_ROOT}/${conversationId}`) && input.includes(`${photo.id}.png`),
      `Hermes 입력에 사진 자리나 파일 이름이 없다: ${input}`,
    );
    expect(input.endsWith(text), `Hermes 입력이 사용자가 쓴 글로 끝나지 않는다: ${input}`);

    step("대화를 다시 읽으면 그 메시지에 첨부가 달려 있고 본문은 사용자가 쓴 그대로다");
    const sent = userMessage(await history(context, token, conversationId), text);
    expect(sent.content === text, `저장된 본문이 달라졌다: ${sent.content}`);
    expect(
      sent.attachments.length === 1 && sent.attachments[0]?.id === photo.id && sent.attachments[0].visible,
      `메시지에 붙은 첨부가 다르다: ${JSON.stringify(sent.attachments)}`,
    );

    step("첨부를 지운 뒤 다시 읽으면 자리는 남고 볼 수 없다고 표시된다");
    expectStatus(
      await call(context, `/chat/conversations/${conversationId}/attachments/${photo.id}`, {
        method: "DELETE",
        token,
      }),
      200,
      "첨부 지우기",
    );
    const after = userMessage(await history(context, token, conversationId), text);
    expect(
      after.attachments.length === 1 && after.attachments[0]?.id === photo.id && !after.attachments[0].visible,
      `지운 첨부의 자리가 다르다: ${JSON.stringify(after.attachments)}`,
    );

    step("지운 첨부의 본문을 읽으면 410 ATTACHMENT_GONE 이다");
    const gone = expectStatus(
      await call(context, `/chat/conversations/${conversationId}/attachments/${photo.id}`, { token }),
      410,
      "지운 첨부 읽기",
    );
    expect(
      gone.json<{ code: string }>().code === "ATTACHMENT_GONE",
      `기대한 오류 코드가 아니다: ${gone.body}`,
    );
  },
};

async function history(
  context: Context,
  token: string,
  conversationId: number,
): Promise<Message[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/messages`, { token }),
    200,
    "메시지 이력 조회",
  ).json<Message[]>();
}

function userMessage(messages: Message[], content: string): Message {
  const found = messages.find((message) => message.role === "USER" && message.content === content);
  expect(found !== undefined, `사용자 메시지를 찾지 못했다: ${JSON.stringify(messages)}`);
  return found as Message;
}

/** 1x1 PNG 를 만든다. 저장소에 이미지 파일을 두지 않으려고 검사 안에서 바이트를 짓는다. */
function onePixelPng(): Uint8Array {
  const chunk = (type: string, data: Buffer): Buffer => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(1, 0);
  header.writeUInt32BE(1, 4);
  header[8] = 8; // 비트 깊이
  header[9] = 2; // RGB
  const pixels = deflateSync(Buffer.from([0, 255, 255, 255]));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", pixels),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
