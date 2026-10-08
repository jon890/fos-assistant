export async function readEventStream<T>(
  response: Response,
  /** `name` 은 `event:` 줄의 이름이다. 없으면 `message` 다. */
  onEvent: (event: T, name: string) => void | Promise<void>,
): Promise<void> {
  if (!response.body) throw new Error("응답 연결이 없어요.");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let data: string[] = [];
  let name = "message";

  const flush = async () => {
    const event = name;
    name = "message";
    if (data.length === 0) return;
    const payload = data.join("\n");
    data = [];
    await onEvent(JSON.parse(payload) as T, event);
  };

  const consumeLine = async (line: string) => {
    if (line === "") {
      await flush();
    } else if (!line.startsWith(":") && line.startsWith("data:")) {
      data.push(line.slice(5).trimStart());
    } else if (line.startsWith("event:")) {
      name = line.slice(6).trim();
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";
    for (const line of lines) await consumeLine(line);
    if (done) break;
  }
  if (buffer.length > 0) await consumeLine(buffer);
  await flush();
}
