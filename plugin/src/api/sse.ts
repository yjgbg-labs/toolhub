/** Parse SSE incrementally, including split UTF-8/CRLF, comments and multiline data. */
export async function* sse(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  // Allow JSON escaping overhead for the server's bounded UTF-8 payloads.
  const maxChars = 1024 * 1024;
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let line = "", data: string[] = [], size = 0, cr = false;
  function consume(): string | undefined {
    const value = line;
    line = "";
    if (!value) {
      const event = data.length ? data.join("\n") : undefined;
      data = []; size = 0;
      return event;
    }
    if (value === "data" || value.startsWith("data:")) {
      const text = value === "data" ? "" : value.slice(5).replace(/^ /, "");
      data.push(text);
      size += text.length;
      if (size > maxChars) throw new Error("SSE event exceeds character limit");
    }
  }
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) return; // An unterminated event is not dispatched.
      for (const character of decoder.decode(chunk.value, { stream: true })) {
        if (cr && character === "\n") { cr = false; continue; }
        cr = character === "\r";
        if (character === "\r" || character === "\n") {
          const event = consume();
          if (event !== undefined) yield event;
        } else {
          line += character;
          if (line.length > maxChars) throw new Error("SSE line exceeds character limit");
        }
      }
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
