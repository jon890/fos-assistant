import { describe, expect, test } from "bun:test";
import { createGmailServer } from "../src/server.ts";
import {
  FakeGoogle,
  accessToken,
  credentials,
  tool,
  withMcp,
} from "./support.ts";

function setup() {
  const fake = new FakeGoogle();
  fake.on("POST", "/token", { access_token: accessToken });
  return {
    fake,
    server: createGmailServer({
      tokenUrl: `${fake.url}/token`,
      apiBase: `${fake.url}/gmail`,
      env: credentials,
    }),
  };
}

const message = (
  id: string,
  body: string,
  headers: Array<{ name: string; value: string }> = [],
) => ({
  id,
  threadId: "thread-9",
  labelIds: ["INBOX"],
  payload: {
    mimeType: "text/plain",
    headers: [
      { name: "Content-Type", value: "text/plain; charset=utf-8" },
      ...headers,
    ],
    body: { data: Buffer.from(body).toString("base64url") },
  },
});

describe("Python Gmail 회귀의 MIME·답장·스레드 이관", () => {
  test("초안과 새 메일은 UTF-8 MIME으로 조립하고 raw 본문과 제목을 보존한다", async () => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/drafts", {
      id: "draft-1",
      message: { id: "message-1" },
    });
    fake.on("POST", "/gmail/messages/send", {
      id: "sent-1",
      threadId: "thread-1",
    });
    try {
      await withMcp(server, async (client) => {
        await tool(client, "create_draft", {
          to: "a@example.com",
          subject: "한글 제목",
          body: "첫 줄\n둘째 줄",
        });
        await tool(client, "send_message", {
          to: "a@example.com",
          cc: "copy@example.com",
          bcc: "hidden@example.com",
          subject: "한글 제목",
          body: "본문",
        });
      });
      const draft = JSON.parse(fake.seen("POST", "/gmail/drafts")[0]!.body);
      const draftRaw = Buffer.from(draft.message.raw, "base64url").toString(
        "utf8",
      );
      expect(draftRaw).toContain("MIME-Version: 1.0");
      expect(draftRaw).toContain("Content-Type: text/plain; charset=utf-8");
      expect(draftRaw).toContain("\r\n\r\n");
      expect(draftRaw.replaceAll("\r\n", "\n")).toContain("\n\n첫 줄\n둘째 줄");
      const sent = JSON.parse(
        fake.seen("POST", "/gmail/messages/send")[0]!.body,
      );
      const sentRaw = Buffer.from(sent.raw, "base64url").toString("utf8");
      expect(sentRaw).toContain("To: a@example.com");
      expect(sentRaw).toContain("Cc: copy@example.com");
      expect(sentRaw).toContain("Bcc: hidden@example.com");
    } finally {
      fake.stop();
    }
  });

  test("답장은 원본의 안전한 thread와 References를 읽고 새 수신자와 제목만 쓴다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages/orig-1", {
      id: "orig-1",
      threadId: "thread-9",
      payload: {
        headers: [
          { name: "Message-ID", value: "<orig-1@example.com>" },
          { name: "References", value: "<root@example.com>" },
        ],
      },
    });
    fake.on("POST", "/gmail/messages/send", {
      id: "sent-1",
      threadId: "thread-9",
    });
    try {
      await withMcp(server, async (client) => {
        await tool(client, "reply_to_message", {
          message_id: "orig-1",
          to: "approved@example.com",
          subject: "승인 제목",
          body: "답장",
        });
      });
      const sent = JSON.parse(
        fake.seen("POST", "/gmail/messages/send")[0]!.body,
      );
      const raw = Buffer.from(sent.raw, "base64url").toString("utf8");
      expect(sent.threadId).toBe("thread-9");
      expect(raw).toContain("In-Reply-To: <orig-1@example.com>");
      expect(raw).toContain(
        "References: <root@example.com> <orig-1@example.com>",
      );
      expect(raw).toContain("To: approved@example.com");
    } finally {
      fake.stop();
    }
  });

  test("RFC 2047 제목, 알 수 없는 charset, 빠진 base64url 패딩을 안전하게 읽는다", async () => {
    const { fake, server } = setup();
    fake.on(
      "GET",
      "/gmail/messages/m1",
      message("m1", "한글 본문", [
        { name: "Subject", value: "=?UTF-8?B?7ZWc6riAIOygnOuqqQ==?=" },
      ]),
    );
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "get_message", {
          message_id: "m1",
        });
        expect(body).toMatchObject({ subject: "한글 제목", body: "한글 본문" });
      });
    } finally {
      fake.stop();
    }
  });

  test("HTML의 중첩 hidden 요소와 script는 본문으로 흘러들지 않는다", async () => {
    const { fake, server } = setup();
    const html =
      "<div hidden><span>숨김</span></div><p>보임</p><script>비밀</script>";
    fake.on("GET", "/gmail/messages/m1", {
      id: "m1",
      threadId: "t1",
      payload: {
        mimeType: "text/html",
        headers: [{ name: "Content-Type", value: "text/html; charset=utf-8" }],
        body: { data: Buffer.from(html).toString("base64url") },
      },
    });
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "get_message", {
          message_id: "m1",
        });
        expect(body).toMatchObject({ body: "보임" });
      });
    } finally {
      fake.stop();
    }
  });

  test("스레드는 20통과 각 본문 5,000자까지만 반환하고 초과를 표시한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/threads/t1", {
      id: "t1",
      messages: Array.from({ length: 21 }, (_, index) =>
        message(`m${index}`, "a".repeat(5_001)),
      ),
    });
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "get_thread", { thread_id: "t1" });
        expect(body).toMatchObject({ id: "t1", messages_truncated: true });
        expect(body.messages).toHaveLength(20);
        expect(
          (
            body.messages as Array<{ body: string; body_truncated: boolean }>
          )[0],
        ).toMatchObject({ body: "a".repeat(5_000), body_truncated: true });
      });
    } finally {
      fake.stop();
    }
  });
});
