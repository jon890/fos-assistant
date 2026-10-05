import { describe, expect, test } from "bun:test";
import { confirmComposedMessage, createGmailServer } from "../src/server.ts";
import {
  FakeGoogle,
  accessToken,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";

function setup(options: Record<string, unknown> = {}) {
  const fake = new FakeGoogle();
  fake.on("POST", "/token", { access_token: accessToken });
  return {
    fake,
    server: createGmailServer({
      tokenUrl: `${fake.url}/token`,
      apiBase: `${fake.url}/gmail`,
      env: credentials,
      ...options,
    }),
  };
}

function mail(
  id: string,
  text: string,
  extra: Array<{ name: string; value: string }> = [],
) {
  return {
    id,
    threadId: "t1",
    payload: {
      mimeType: "text/plain",
      headers: [
        { name: "Content-Type", value: "text/plain; charset=utf-8" },
        ...extra,
      ],
      body: { data: Buffer.from(text).toString("base64url") },
    },
  };
}

function raw(body: string) {
  return Buffer.from(body).toString("base64url");
}

function decodedSubject(rawMessage: string) {
  const header = rawMessage.split("\r\n\r\n", 1)[0] ?? "";
  const subject = header
    .split("\r\n")
    .filter((line) => line.startsWith("Subject:") || /^[ \t]/.test(line))
    .join(" ")
    .replace(/^Subject:\s*/, "")
    .replace(/\?=\s+=\?/g, "?==?");
  return subject.replace(/=\?UTF-8\?B\?([^?]+)\?=/gi, (_word, value) =>
    Buffer.from(value, "base64").toString("utf8"),
  );
}

describe("Python Gmail 회귀의 HTTP 경계 이관", () => {
  test.each([
    "GMAIL_OAUTH_CLIENT_ID",
    "GMAIL_OAUTH_CLIENT_SECRET",
    "GMAIL_OAUTH_REFRESH_TOKEN",
  ])("자격 증명 %s가 없으면 HTTP 전에 unauthorized이다", async (key) => {
    const env = { ...credentials, [key]: " " };
    const { fake, server } = setup({ env });
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_UNAUTHORIZED", "get_profile"),
      );
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("닫힌 endpoint와 API redirect는 unavailable이며 redirect를 따르지 않는다", async () => {
    const closed = createGmailServer({
      tokenUrl: "http://127.0.0.1:1/token",
      apiBase: "http://127.0.0.1:1/gmail",
      env: credentials,
      timeoutMs: 50,
    });
    await withMcp(closed, (client) =>
      expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile"),
    );
    const { fake, server } = setup();
    fake.routes.set(
      "GET /gmail/profile",
      new Response(null, {
        status: 302,
        headers: { location: "http://localhost:9/elsewhere" },
      }),
    );
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile"),
      );
    } finally {
      fake.stop();
    }
  });

  test("token redirect는 Gmail API를 부르지 않고 unavailable이다", async () => {
    const { fake, server } = setup();
    fake.routes.set(
      "POST /token",
      new Response(null, {
        status: 302,
        headers: { location: `${fake.url}/gmail/profile` },
      }),
    );
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile"),
      );
      expect(fake.seen("GET", "/gmail/profile")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("환경 proxy 값이 있어도 localhost FakeGoogle에 직접 요청한다", async () => {
    const proxyKeys = [
      "HTTP_PROXY",
      "HTTPS_PROXY",
      "ALL_PROXY",
      "http_proxy",
      "https_proxy",
      "all_proxy",
      "NO_PROXY",
      "no_proxy",
    ];
    const saved = Object.fromEntries(
      proxyKeys.map((key) => [key, process.env[key]]),
    );
    const { fake, server } = setup();
    let proxyRequests = 0;
    const proxy = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch: () => {
        proxyRequests += 1;
        return new Response(null, { status: 502 });
      },
    });
    fake.on("GET", "/gmail/profile", { emailAddress: "me" });
    try {
      for (const key of proxyKeys.slice(0, 6))
        process.env[key] = proxy.url.toString();
      delete process.env.NO_PROXY;
      delete process.env.no_proxy;
      await withMcp(server, async (client) => {
        expect((await tool(client, "get_profile")).result.isError).not.toBe(
          true,
        );
      });
      expect(fake.seen("GET", "/gmail/profile")).toHaveLength(1);
      expect(proxyRequests).toBe(0);
    } finally {
      for (const [key, value] of Object.entries(saved)) {
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      }
      proxy.stop(true);
      fake.stop();
    }
  });

  test("FakeGoogle allowlist는 모든 허용 요청을 받고 잘못된 method와 금지 경로를 잡는다", async () => {
    const allowed = new FakeGoogle();
    const requests = [
      ["POST", "/token"],
      ["GET", "/gmail/profile"],
      ["GET", "/gmail/labels"],
      ["POST", "/gmail/labels"],
      ["GET", "/gmail/messages"],
      ["POST", "/gmail/messages/send"],
      ["POST", "/gmail/messages/batchModify"],
      ["GET", "/gmail/settings/filters"],
      ["POST", "/gmail/settings/filters"],
      ["POST", "/gmail/drafts"],
      ["GET", "/gmail/messages/m1"],
      ["GET", "/gmail/threads/t1"],
      ["POST", "/gmail/messages/m1/modify"],
      ["PATCH", "/gmail/labels/Label_1"],
      ["DELETE", "/gmail/settings/filters/f1"],
    ] as const;
    for (const [method, path] of requests)
      await fetch(`${allowed.url}${path}`, { method });
    allowed.stop();

    for (const [method, path] of [
      ["GET", "/token"],
      ["POST", "/gmail/threads/t1"],
      ["POST", "/gmail/messages/m1/trash"],
      ["DELETE", "/gmail/messages/m1"],
    ]) {
      const denied = new FakeGoogle();
      await fetch(`${denied.url}${path}`, { method });
      expect(() => denied.stop()).toThrow();
    }
  });

  test.each([
    [10 * 1024 * 1024, false],
    [10 * 1024 * 1024 + 1, true],
  ])("응답 %i byte는 한도 정책을 적용한다", async (bytes, fails) => {
    const { fake, server } = setup();
    fake.routes.set(
      "GET /gmail/profile",
      new Response(" ".repeat(bytes - 2) + "{}", {
        headers: { "content-type": "application/json" },
      }),
    );
    try {
      await withMcp(server, async (client) => {
        if (fails)
          await expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile");
        else
          expect((await tool(client, "get_profile")).result.isError).not.toBe(
            true,
          );
      });
    } finally {
      fake.stop();
    }
  });

  test("64자 ID는 허용하고 빈 검색은 q 없이 최근 메일을 요청한다", async () => {
    const { fake, server } = setup();
    const id = "a".repeat(64);
    fake.on("GET", `/gmail/messages/${id}`, mail(id, "body"));
    fake.on("GET", "/gmail/messages", { messages: [] });
    try {
      await withMcp(server, async (client) => {
        expect(
          (await tool(client, "get_message", { message_id: id })).result
            .isError,
        ).not.toBe(true);
        await tool(client, "search_messages", { query: "", max_results: 1 });
      });
      expect(fake.seen("GET", "/gmail/messages")[0]!.query.has("q")).toBe(
        false,
      );
    } finally {
      fake.stop();
    }
  });

  test("검색 metadata 하나가 실패하면 부분 결과 없이 전체가 실패한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages", {
      messages: [{ id: "m1" }, { id: "m2" }],
    });
    fake.on("GET", "/gmail/messages/m1", mail("m1", "a"));
    fake.on("GET", "/gmail/messages/m2", { error: {} }, 500);
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_UNAVAILABLE", "search_messages", {
          query: "x",
          max_results: 2,
        }),
      );
    } finally {
      fake.stop();
    }
  });

  test("검색은 다섯 metadata 요청만 병렬로 시작하고 결과 순서를 보존한다", async () => {
    const { fake, server } = setup();
    const ids = Array.from({ length: 6 }, (_, index) => `m${index}`);
    let active = 0;
    let peak = 0;
    fake.on("GET", "/gmail/messages", { messages: ids.map((id) => ({ id })) });
    for (const id of ids)
      fake.routes.set(`GET /gmail/messages/${id}`, async () => {
        active += 1;
        peak = Math.max(peak, active);
        await Bun.sleep(15);
        active -= 1;
        return json(mail(id, id));
      });
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "search_messages", {
          query: "x",
          max_results: 6,
        });
        expect(
          (body.messages as Array<{ id: string }>).map((item) => item.id),
        ).toEqual(ids);
      });
      expect(peak).toBe(5);
    } finally {
      fake.stop();
    }
  });
});

describe("Python Gmail 회귀의 MIME 한계 이관", () => {
  test("중첩 multipart는 HTML보다 plain 본문을 선택한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages/m1", {
      id: "m1",
      payload: {
        mimeType: "multipart/mixed",
        headers: [],
        parts: [
          {
            mimeType: "multipart/alternative",
            parts: [
              {
                mimeType: "text/html",
                headers: [],
                body: { data: raw("<b>html</b>") },
              },
              {
                mimeType: "text/plain",
                headers: [],
                body: { data: raw("plain") },
              },
            ],
          },
        ],
      },
    });
    try {
      await withMcp(server, async (client) =>
        expect(
          (await tool(client, "get_message", { message_id: "m1" })).body,
        ).toMatchObject({ body: "plain" }),
      );
    } finally {
      fake.stop();
    }
  });

  test("닫힌 hidden 뒤 텍스트는 남고 닫히지 않은 head는 body를 숨기지 않는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages/m1", {
      id: "m1",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw("<span hidden>drop</span>shown") },
      },
    });
    fake.on("GET", "/gmail/messages/m2", {
      id: "m2",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw("<head><title>x</title><body>shown") },
      },
    });
    try {
      await withMcp(server, async (client) => {
        expect(
          (await tool(client, "get_message", { message_id: "m1" })).body,
        ).toMatchObject({ body: "shown" });
        expect(
          (await tool(client, "get_message", { message_id: "m2" })).body,
        ).toMatchObject({ body: "shown" });
      });
    } finally {
      fake.stop();
    }
  });

  test("따옴표 안의 >를 가진 hidden 속성도 본문을 숨긴다", async () => {
    const { fake, server } = setup();
    const html = '<span hidden data-note="a > b">숨김</span><p>보임</p>';
    fake.on("GET", "/gmail/messages/m3", {
      id: "m3",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw(html) },
      },
    });
    try {
      await withMcp(server, async (client) => {
        const result = await tool(client, "get_message", { message_id: "m3" });
        expect(result.body).toMatchObject({ body: "보임" });
        expect(JSON.stringify(result.body)).not.toContain("숨김");
      });
    } finally {
      fake.stop();
    }
  });

  test("void 또는 self-closing hidden 요소는 뒤의 보이는 HTML을 숨기지 않는다", async () => {
    const { fake, server } = setup();
    const examples = [
      '<img hidden src="x">보임',
      "<br hidden>보임",
      '<img aria-hidden="true" src="x">보임',
      "<span hidden />보임",
      '<div title="foo hidden bar">보임</div>',
      '<DIV title="foo hidden bar">보임</DIV>',
    ];
    for (const [index, html] of examples.entries()) {
      fake.on("GET", `/gmail/messages/h${index}`, {
        id: `h${index}`,
        payload: {
          mimeType: "text/html",
          headers: [],
          body: { data: raw(html) },
        },
      });
    }
    fake.on("GET", "/gmail/messages/hidden", {
      id: "hidden",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw("<span hidden>숨김</span>보임") },
      },
    });
    fake.on("GET", "/gmail/messages/hidden-value", {
      id: "hidden-value",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw('<div hidden="true">숨김</div>보임') },
      },
    });
    fake.on("GET", "/gmail/messages/nested-hidden", {
      id: "nested-hidden",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: {
          data: raw("<div hidden><div>SECRET</div>STILL_SECRET</div>VISIBLE"),
        },
      },
    });
    fake.on("GET", "/gmail/messages/script-tag", {
      id: "script-tag",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw('<script>"<div>"</script>VISIBLE') },
      },
    });
    fake.on("GET", "/gmail/messages/malformed-hidden", {
      id: "malformed-hidden",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: {
          data: raw("<div hidden><span>SECRET</div>STILL_SECRET</span>VISIBLE"),
        },
      },
    });
    fake.on("GET", "/gmail/messages/hidden-script", {
      id: "hidden-script",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: {
          data: raw('<div hidden><script>"<div>"</script></div>VISIBLE'),
        },
      },
    });
    try {
      await withMcp(server, async (client) => {
        for (const index of examples.keys()) {
          const result = await tool(client, "get_message", {
            message_id: `h${index}`,
          });
          expect(result.body).toMatchObject({ body: "보임" });
        }
        const hidden = await tool(client, "get_message", {
          message_id: "hidden",
        });
        expect(hidden.body).toMatchObject({ body: "보임" });
        expect(JSON.stringify(hidden.body)).not.toContain("숨김");
        const hiddenValue = await tool(client, "get_message", {
          message_id: "hidden-value",
        });
        expect(hiddenValue.body).toMatchObject({ body: "보임" });
        expect(JSON.stringify(hiddenValue.body)).not.toContain("숨김");
        const nested = await tool(client, "get_message", {
          message_id: "nested-hidden",
        });
        expect(nested.body).toMatchObject({ body: "VISIBLE" });
        expect(JSON.stringify(nested.body)).not.toContain("SECRET");
        const script = await tool(client, "get_message", {
          message_id: "script-tag",
        });
        expect(script.body).toMatchObject({ body: "VISIBLE" });
        const malformed = await tool(client, "get_message", {
          message_id: "malformed-hidden",
        });
        expect(malformed.body).toMatchObject({ body: "VISIBLE" });
        expect(JSON.stringify(malformed.body)).not.toContain("SECRET");
        const hiddenScript = await tool(client, "get_message", {
          message_id: "hidden-script",
        });
        expect(hiddenScript.body).toMatchObject({ body: "VISIBLE" });
      });
    } finally {
      fake.stop();
    }
  });

  test("HTML5 named·numeric entity는 한 번만 풀고 hidden 본문에는 섞지 않는다", async () => {
    const { fake, server } = setup();
    const html = [
      "&lt;&gt;&quot;&apos;&copy;&eacute;&NotEqualTilde;",
      "&#54620;&#x1F600;",
      "&amp;lt;",
      "<span hidden>&copy;비밀</span>보임",
    ].join("|");
    fake.on("GET", "/gmail/messages/entities", {
      id: "entities",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw(html) },
      },
    });
    try {
      await withMcp(server, async (client) => {
        const result = await tool(client, "get_message", {
          message_id: "entities",
        });
        expect(result.body.body).toBe("<>\"'©é≂̸|한😀|&lt;|보임");
      });
    } finally {
      fake.stop();
    }
  });

  test("HTML entity edge case는 Python html.unescape와 같은 결과를 낸다", async () => {
    const { fake, server } = setup();
    const html =
      "&amp|&notit;|&#0;|&#x110000;|&#xD800;|&#128;|&#1;|&#x0b;|&constructor;|&toString;";
    fake.on("GET", "/gmail/messages/entity-edge", {
      id: "entity-edge",
      payload: {
        mimeType: "text/html",
        headers: [],
        body: { data: raw(html) },
      },
    });
    try {
      await withMcp(server, async (client) => {
        const result = await tool(client, "get_message", {
          message_id: "entity-edge",
        });
        expect(result.body.body).toBe(
          "&|¬it;|�|�|�|€|||&constructor;|&toString;",
        );
      });
    } finally {
      fake.stop();
    }
  });

  test("1000자 header, EUC-KR encoded subject, 20000자 본문 경계를 보존한다", async () => {
    const { fake, server } = setup();
    const euckr = Buffer.from([0xc7, 0xd1, 0xb1, 0xdb]).toString("base64");
    fake.on(
      "GET",
      "/gmail/messages/m1",
      mail("m1", "a".repeat(20_000), [
        { name: "Subject", value: `=?EUC-KR?B?${euckr}?=` },
        { name: "From", value: "x".repeat(1_000) },
      ]),
    );
    fake.on("GET", "/gmail/messages/m2", mail("m2", "b".repeat(20_001)));
    try {
      await withMcp(server, async (client) => {
        const one = (await tool(client, "get_message", { message_id: "m1" }))
          .body;
        expect(one.from as string).toHaveLength(1_000);
        expect(one.subject).toBe("한글");
        expect(one.body as string).toHaveLength(20_000);
        expect(
          (await tool(client, "get_message", { message_id: "m2" })).body,
        ).toMatchObject({ body_truncated: true });
      });
    } finally {
      fake.stop();
    }
  });

  test("RFC 2047 Q 제목은 charset, 밑줄 공백, UTF-8 바이트를 올바르게 푼다", async () => {
    const { fake, server } = setup();
    const subjects = [
      ["m1", "=?ISO-8859-1?Q?caf=E9?=", "café"],
      ["m2", "=?UTF-8?Q?=ED=95=9C=EA=B5=AD=EC=96=B4?=", "한국어"],
      ["m3", "=?ISO-8859-1?Q?caf=E9_au_lait?=", "café au lait"],
      ["m4", "=?UTF-8?Q?Wire?=   =?UTF-8?B?IG1vbmV5?=", "Wire money"],
    ] as const;
    for (const [id, subject] of subjects) {
      fake.on(
        "GET",
        `/gmail/messages/${id}`,
        mail(id, "body", [{ name: "Subject", value: subject }]),
      );
    }
    try {
      await withMcp(server, async (client) => {
        for (const [id, _encoded, expected] of subjects) {
          const result = await tool(client, "get_message", { message_id: id });
          expect(result.body.subject).toBe(expected);
        }
      });
    } finally {
      fake.stop();
    }
  });

  test("정확히 20개 스레드는 자르지 않고 각 5000자 본문을 보존한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/threads/t1", {
      id: "t1",
      messages: Array.from({ length: 20 }, (_, index) =>
        mail(`m${index}`, "x".repeat(5_000)),
      ),
    });
    try {
      await withMcp(server, async (client) => {
        const body = (await tool(client, "get_thread", { thread_id: "t1" }))
          .body;
        expect(body.messages_truncated).toBe(false);
        expect(
          (body.messages as Array<{ body: string }>)[0]!.body,
        ).toHaveLength(5_000);
      });
    } finally {
      fake.stop();
    }
  });

  test("emoji는 header, body, thread body, filename 한계를 code point로 센다", async () => {
    const { fake, server } = setup();
    const emoji = "😀";
    const file255 = emoji.repeat(255);
    const file256 = emoji.repeat(256);
    fake.on("GET", "/gmail/messages/m1", {
      id: "m1",
      payload: {
        mimeType: "multipart/mixed",
        headers: [{ name: "Subject", value: emoji.repeat(1001) }],
        parts: [
          {
            mimeType: "text/plain",
            headers: [],
            body: { data: raw(emoji.repeat(20001)) },
          },
          {
            mimeType: "application/pdf",
            filename: file255,
            body: { attachmentId: "a1" },
          },
          {
            mimeType: "application/pdf",
            filename: file256,
            body: { attachmentId: "a2" },
          },
        ],
      },
    });
    fake.on("GET", "/gmail/threads/t1", {
      id: "t1",
      messages: [mail("t1", emoji.repeat(5001))],
    });
    try {
      await withMcp(server, async (client) => {
        const message = (
          await tool(client, "get_message", { message_id: "m1" })
        ).body;
        expect(Array.from(message.subject as string)).toHaveLength(1000);
        expect(Array.from(message.body as string)).toHaveLength(20000);
        expect(
          (message.attachments as Array<{ filename: string }>).map(
            (item) => Array.from(item.filename).length,
          ),
        ).toEqual([255, 255]);
        const thread = (await tool(client, "get_thread", { thread_id: "t1" }))
          .body;
        expect(
          Array.from((thread.messages as Array<{ body: string }>)[0]!.body),
        ).toHaveLength(5000);
      });
    } finally {
      fake.stop();
    }
  });
});

describe("조립한 메일 확인과 전송 경계", () => {
  test("send, draft, reply의 Unicode 제목은 ASCII RFC 2047 header로 접고 원문으로 읽힌다", async () => {
    const { fake, server } = setup();
    const subject = `${"한국어 😀 ".repeat(20)}끝`;
    fake.on("POST", "/gmail/drafts", { id: "d1", message: { id: "m1" } });
    fake.on("POST", "/gmail/messages/send", { id: "sent" });
    fake.on("GET", "/gmail/messages/orig", {
      id: "orig",
      threadId: "t1",
      payload: { headers: [] },
    });
    try {
      await withMcp(server, async (client) => {
        await tool(client, "create_draft", {
          to: "a@example.com",
          subject,
          body: "b",
        });
        await tool(client, "send_message", {
          to: "a@example.com",
          subject,
          body: "b",
        });
        await tool(client, "reply_to_message", {
          message_id: "orig",
          to: "a@example.com",
          subject,
          body: "b",
        });
      });
      const requests = [
        fake.seen("POST", "/gmail/drafts")[0]!,
        ...fake.seen("POST", "/gmail/messages/send"),
      ];
      for (const request of requests) {
        const payload = JSON.parse(request.body);
        const source = Buffer.from(
          payload.message?.raw ?? payload.raw,
          "base64url",
        ).toString("utf8");
        const header = source.split("\r\n\r\n", 1)[0]!;
        expect(header).toMatch(/^Subject: [\x00-\x7f\r\n\t ]+$/m);
        expect(decodedSubject(source)).toBe(subject);
      }
    } finally {
      fake.stop();
    }
  });

  test.each([
    ["create_draft", "reply_to_message_id"],
    ["reply_to_message", "message_id"],
  ])(
    "답장 원본 ID가 안전하지 않으면 %s는 HTTP 전에 거절한다",
    async (name, field) => {
      const { fake, server } = setup();
      try {
        await withMcp(server, async (client) => {
          for (const value of ["/trash", "a".repeat(65)]) {
            await expectFailure(client, "GMAIL_INVALID_INPUT", name, {
              to: "a@example.com",
              subject: "s",
              body: "b",
              [field]: value,
            });
          }
        });
        expect(fake.requests).toHaveLength(0);
      } finally {
        fake.stop();
      }
    },
  );

  test("빈 create_draft 답장 원본 ID는 새 초안으로 허용한다", async () => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/drafts", { id: "d1", message: { id: "m1" } });
    try {
      await withMcp(server, async (client) => {
        const result = await tool(client, "create_draft", {
          to: "a@example.com",
          subject: "s",
          body: "b",
          reply_to_message_id: "",
        });
        expect(result.result.isError).not.toBe(true);
      });
    } finally {
      fake.stop();
    }
  });

  test.each([new Response(null, { status: 204 }), json({}, 200)])(
    "send의 빈 성공 응답은 재시도 없이 결과 불명이다",
    async (response) => {
      const { fake, server } = setup();
      fake.routes.set("POST /gmail/messages/send", response);
      try {
        await withMcp(server, (client) =>
          expectFailure(client, "GMAIL_SEND_UNKNOWN", "send_message", {
            to: "a@example.com",
            subject: "s",
            body: "b",
          }),
        );
        expect(fake.seen("POST", "/gmail/messages/send")).toHaveLength(1);
      } finally {
        fake.stop();
      }
    },
  );

  test.each([
    { to: "Kim <a@example.com>" },
    { subject: "=?utf-8?q?Wire_money?=" },
    { body: "hello\u200bworld" },
    { to: "not-an-address" },
  ])(
    "잘못된 메일 인자는 create_draft·send·reply 모두 HTTP 전에 거절한다",
    async (change) => {
      const { fake, server } = setup();
      const base = { to: "a@example.com", subject: "s", body: "b", ...change };
      try {
        await withMcp(server, async (client) => {
          await expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "create_draft",
            base,
          );
          await expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "send_message",
            base,
          );
          await expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "reply_to_message",
            { ...base, message_id: "orig" },
          );
        });
        expect(fake.requests).toHaveLength(0);
      } finally {
        fake.stop();
      }
    },
  );

  test("원본 답장 header가 ASCII와 message-id 규칙을 어기면 모두 버린다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages/orig", {
      id: "orig",
      threadId: "t1",
      payload: {
        headers: [
          { name: "Message-ID", value: "<원본@example.com>" },
          { name: "References", value: "bad value" },
        ],
      },
    });
    fake.on("POST", "/gmail/messages/send", { id: "sent" });
    try {
      await withMcp(server, async (client) => {
        await tool(client, "reply_to_message", {
          message_id: "orig",
          to: "a@example.com",
          subject: "s",
          body: "b",
        });
      });
      const source = Buffer.from(
        JSON.parse(fake.seen("POST", "/gmail/messages/send")[0]!.body).raw,
        "base64url",
      ).toString();
      expect(source).not.toContain("In-Reply-To:");
      expect(source).not.toContain("References:");
    } finally {
      fake.stop();
    }
  });

  test("수신자는 To, Cc, Bcc별 순서로 raw header에 한 번씩 유지한다", async () => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/messages/send", { id: "sent" });
    try {
      await withMcp(server, async (client) => {
        await tool(client, "send_message", {
          to: "a@example.com, b@example.com",
          cc: "c@example.com",
          bcc: "d@example.com, e@example.com",
          subject: "s",
          body: "b",
        });
      });
      const source = Buffer.from(
        JSON.parse(fake.seen("POST", "/gmail/messages/send")[0]!.body).raw,
        "base64url",
      ).toString();
      expect(source).toContain("To: a@example.com, b@example.com");
      expect(source).toContain("Cc: c@example.com");
      expect(source).toContain("Bcc: d@example.com, e@example.com");
    } finally {
      fake.stop();
    }
  });

  test.each(["a\u034fb", "a\ufe00b", "a\u{e0100}b"])(
    "보이지 않는 결합 문자가 든 제목은 거절한다: %s",
    async (subject) => {
      const { fake, server } = setup();
      try {
        await withMcp(server, (client) =>
          expectFailure(client, "GMAIL_INVALID_INPUT", "send_message", {
            to: "a@example.com",
            subject,
            body: "b",
          }),
        );
        expect(fake.requests).toHaveLength(0);
      } finally {
        fake.stop();
      }
    },
  );

  test("허용된 특수문자 주소와 일반 emoji variation selector 제목은 전송한다", async () => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/messages/send", { id: "sent" });
    try {
      await withMcp(server, async (client) =>
        expect(
          (
            await tool(client, "send_message", {
              to: "!#$%&'*+/=?^_{|}~-@example.com",
              subject: "❤️ 확인",
              body: "b",
            })
          ).result.isError,
        ).not.toBe(true),
      );
    } finally {
      fake.stop();
    }
  });

  test("보이는 결합 문자와 평범하지 않은 제목도 raw 메일에 그대로 보존한다", async () => {
    const { fake, server } = setup();
    const subjects = [
      "Cafe\u0301 안내",
      "1+1=?",
      "a =? b ?= c",
      "  앞뒤 공백  ",
    ];
    fake.on("POST", "/gmail/messages/send", { id: "sent" });
    try {
      await withMcp(server, async (client) => {
        for (const subject of subjects) {
          fake.requests.length = 0;
          const result = await tool(client, "send_message", {
            to: "a@example.com",
            subject,
            body: "b",
          });
          expect(result.result.isError).not.toBe(true);
          const request = fake.seen("POST", "/gmail/messages/send")[0]!;
          const source = Buffer.from(
            JSON.parse(request.body).raw,
            "base64url",
          ).toString();
          expect(decodedSubject(source)).toBe(subject);
        }
      });
    } finally {
      fake.stop();
    }
  });

  test.each([
    [
      "허가하지 않은 수신자",
      "To: a@example.com, b@example.com, x@example.com\r\nCc: c@example.com\r\nBcc: d@example.com\r\nSubject: s\r\n\r\nb",
    ],
    [
      "To 수신자 순서",
      "To: b@example.com, a@example.com\r\nCc: c@example.com\r\nBcc: d@example.com\r\nSubject: s\r\n\r\nb",
    ],
    [
      "Cc로 수신자 이동",
      "To: a@example.com, b@example.com\r\nCc: c@example.com, d@example.com\r\nSubject: s\r\n\r\nb",
    ],
    [
      "중복 Subject",
      "To: a@example.com, b@example.com\r\nCc: c@example.com\r\nBcc: d@example.com\r\nSubject: s\r\nSubject: s\r\n\r\nb",
    ],
    [
      "plain Subject 불일치",
      "To: a@example.com, b@example.com\r\nCc: c@example.com\r\nBcc: d@example.com\r\nSubject: other\r\n\r\nb",
    ],
    [
      "encoded Subject 불일치",
      "To: a@example.com, b@example.com\r\nCc: c@example.com\r\nBcc: d@example.com\r\nSubject: =?utf-8?q?Wire_money?=\r\n\r\nb",
    ],
  ])("confirmComposedMessage는 %s만 바뀐 raw를 거절한다", (_name, source) => {
    const recipients = {
      To: ["a@example.com", "b@example.com"],
      Cc: ["c@example.com"],
      Bcc: ["d@example.com"],
    };
    expect(() =>
      confirmComposedMessage(new TextEncoder().encode(source), "s", recipients),
    ).toThrow("GMAIL_INVALID_INPUT");
  });

  test("응답 없는 send만 unknown이고 draft와 modify는 unavailable이다", async () => {
    const { fake, server } = setup({ timeoutMs: 20 });
    fake.routes.set("POST /gmail/messages/send", async () => {
      await Bun.sleep(100);
      return json({});
    });
    fake.routes.set("POST /gmail/drafts", async () => {
      await Bun.sleep(100);
      return json({});
    });
    fake.on("GET", "/gmail/labels", {
      labels: [{ id: "INBOX", name: "INBOX", type: "system" }],
    });
    fake.routes.set("POST /gmail/messages/m1/modify", async () => {
      await Bun.sleep(100);
      return json({});
    });
    try {
      await withMcp(server, async (client) => {
        await expectFailure(client, "GMAIL_SEND_UNKNOWN", "send_message", {
          to: "a@example.com",
          subject: "s",
          body: "b",
        });
        await expectFailure(client, "GMAIL_UNAVAILABLE", "create_draft", {
          to: "a@example.com",
          subject: "s",
          body: "b",
        });
        await expectFailure(client, "GMAIL_UNAVAILABLE", "modify_labels", {
          message_id: "m1",
          remove_labels: "INBOX",
        });
      });
      expect(fake.seen("POST", "/gmail/messages/send")).toHaveLength(1);
    } finally {
      fake.stop();
    }
  });

  test("각 기존 도구는 token 한 번과 필요한 Gmail endpoint만 호출한다", async () => {
    const { fake, server } = setup();
    const labels = [{ id: "INBOX", name: "INBOX", type: "system" }];
    fake.on("GET", "/gmail/profile", { emailAddress: "me" });
    fake.on("GET", "/gmail/labels", { labels });
    fake.on("GET", "/gmail/messages", { messages: [{ id: "m1" }] });
    fake.on("GET", "/gmail/messages/m1", mail("m1", "body"));
    fake.on("GET", "/gmail/messages/orig", {
      id: "orig",
      threadId: "t1",
      payload: { headers: [] },
    });
    fake.on("GET", "/gmail/threads/t1", {
      id: "t1",
      messages: [mail("m1", "body")],
    });
    fake.on("POST", "/gmail/drafts", { id: "d1", message: { id: "m1" } });
    fake.on("POST", "/gmail/messages/send", { id: "sent" });
    fake.on("POST", "/gmail/messages/m1/modify", { id: "m1" });
    const cases: Array<[string, Record<string, unknown>, string[]]> = [
      ["get_profile", {}, ["POST /token", "GET /gmail/profile"]],
      ["list_labels", {}, ["POST /token", "GET /gmail/labels"]],
      [
        "search_messages",
        { query: "x", max_results: 1 },
        ["POST /token", "GET /gmail/messages", "GET /gmail/messages/m1"],
      ],
      [
        "get_message",
        { message_id: "m1" },
        ["POST /token", "GET /gmail/messages/m1"],
      ],
      [
        "get_thread",
        { thread_id: "t1" },
        ["POST /token", "GET /gmail/threads/t1"],
      ],
      [
        "create_draft",
        { to: "a@example.com", subject: "s", body: "b" },
        ["POST /token", "POST /gmail/drafts"],
      ],
      [
        "send_message",
        { to: "a@example.com", subject: "s", body: "b" },
        ["POST /token", "POST /gmail/messages/send"],
      ],
      [
        "reply_to_message",
        { message_id: "orig", to: "a@example.com", subject: "s", body: "b" },
        [
          "POST /token",
          "GET /gmail/messages/orig",
          "POST /gmail/messages/send",
        ],
      ],
      [
        "modify_labels",
        { message_id: "m1", remove_labels: "INBOX" },
        ["POST /token", "GET /gmail/labels", "POST /gmail/messages/m1/modify"],
      ],
    ];
    try {
      await withMcp(server, async (client) => {
        for (const [name, args, expected] of cases) {
          fake.requests.length = 0;
          expect((await tool(client, name, args)).result.isError).not.toBe(
            true,
          );
          expect(
            fake.requests
              .map((request) => `${request.method} ${request.path}`)
              .sort(),
          ).toEqual(expected.sort());
        }
      });
    } finally {
      fake.stop();
    }
  });

  test("알 수 없는 라벨은 modify 요청을 쓰기 전에 거절한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/labels", {
      labels: [{ id: "INBOX", name: "INBOX", type: "system" }],
    });
    try {
      await withMcp(server, async (client) => {
        await expectFailure(client, "GMAIL_INVALID_INPUT", "modify_labels", {
          message_id: "m1",
          add_labels: "없는 라벨",
        });
      });
      expect(fake.seen("POST", "/gmail/messages/m1/modify")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
});
