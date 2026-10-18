// Minimal Chrome DevTools Protocol client for the app's WebView (debug builds enable
// WebView.setWebContentsDebuggingEnabled). The devtools socket must be forwarded to
// 127.0.0.1:$CDP_PORT first (test-viewer.mjs does this). Needs Node 22+ (global WebSocket).
//
//   node cdp.mjs eval '<expression>'         evaluate in the top-level viewer shell
//   node cdp.mjs frame-eval '<expression>'   evaluate in the artifact iframe's main world
//
// Prints the JSON value of the expression. Promises are awaited.
import { pathToFileURL } from "node:url";

const port = () => Number(process.env.CDP_PORT || 9222);

/** Evaluate [expression] in the viewer shell (frame = false) or in the artifact iframe (frame = true). */
export async function evaluate(expression, { frame = false, timeoutMs = 20000 } = {}) {
  const targets = await (await fetch(`http://127.0.0.1:${port()}/json`)).json();
  const page = targets.find((t) => t.type === "page" && t.webSocketDebuggerUrl);
  if (!page) throw new Error(`no page target: ${JSON.stringify(targets).slice(0, 300)}`);
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let nextId = 0;
  const pending = new Map();
  const contexts = [];
  ws.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.executionContextCreated") contexts.push(message.params.context);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(JSON.stringify(message.error)));
      else resolve(message.result);
    }
  });
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", () => reject(new Error("cdp: websocket error")), { once: true });
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
  let timer;
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("cdp: timeout")), timeoutMs);
  });
  try {
    return await Promise.race([deadline, (async () => {
      let contextId;
      if (frame) {
        // The artifact frame's own (main) world, so values stored on window persist between calls.
        const { frameTree } = await send("Page.getFrameTree");
        const child = (frameTree.childFrames || [])[0];
        if (!child) throw new Error("no artifact iframe");
        await send("Runtime.enable");
        const context = contexts.find((c) => c.auxData && c.auxData.frameId === child.frame.id && c.auxData.isDefault);
        if (!context) throw new Error("no execution context for the artifact iframe");
        contextId = context.id;
      }
      const { result, exceptionDetails } = await send("Runtime.evaluate", {
        expression, returnByValue: true, awaitPromise: true, ...(contextId ? { contextId } : {}),
      });
      if (exceptionDetails) throw new Error(`cdp: exception ${JSON.stringify(exceptionDetails).slice(0, 400)}`);
      return result.value === undefined ? null : result.value;
    })()]);
  } finally {
    clearTimeout(timer);
    ws.close();
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [mode, expression] = process.argv.slice(2);
  if (!["eval", "frame-eval"].includes(mode) || !expression) {
    console.error("usage: node cdp.mjs eval|frame-eval '<expression>'");
    process.exit(2);
  }
  try {
    console.log(JSON.stringify(await evaluate(expression, { frame: mode === "frame-eval" })));
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
