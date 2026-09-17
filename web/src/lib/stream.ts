export async function readEventStream<T>(
  response: Response,
  onEvent: (event: T) => void | Promise<void>,
): Promise<void> {
  if (!response.body) throw new Error("응답 스트림이 없습니다.");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let data: string[] = [];

  const flush = async () => {
    if (data.length === 0) return;
    const payload = data.join("\n");
    data = [];
    await onEvent(JSON.parse(payload) as T);
  };

  const consumeLine = async (line: string) => {
    if (line === "") {
      await flush();
    } else if (!line.startsWith(":") && line.startsWith("data:")) {
      data.push(line.slice(5).trimStart());
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
