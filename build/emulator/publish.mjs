// Publish the emulator test artifacts into the local dev server over MCP (legacy JSON-RPC).
// Prints `<artifact_key> <id>` per artifact. Stable keys make reruns update in place.
//
//   MCP_URL=http://127.0.0.1:3480/mcp MCP_KEY=... node publish.mjs probe.html:emu/geo-probe:Geo\ probe ...
import { readFile } from "node:fs/promises";

const url = process.env.MCP_URL || "http://127.0.0.1:3480/mcp";
const key = process.env.MCP_KEY;
if (!key) throw new Error("MCP_KEY is required");

let rpcId = 0;
async function call(name, args) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      authorization: `Bearer ${key}`,
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: ++rpcId, method: "tools/call", params: { name, arguments: args } }),
  });
  const body = await response.json();
  if (!response.ok || body.error || body.result?.isError) {
    throw new Error(`${name} failed: HTTP ${response.status} ${JSON.stringify(body).slice(0, 400)}`);
  }
  return body.result;
}

for (const spec of process.argv.slice(2)) {
  const [file, artifactKey, ...titleParts] = spec.split(":");
  const html = await readFile(file, "utf8");
  const result = await call("publish_artifact", {
    html,
    title: titleParts.join(":") || artifactKey,
    description: "Emulator test artifact",
    artifact_key: artifactKey,
  });
  const structured = result.structuredContent || {};
  const text = (result.content || []).map((c) => c.text || "").join(" ");
  const id = structured.id || (text.match(/"id"\s*:\s*"([A-Za-z0-9_-]+)"/) || text.match(/\/([A-Za-z0-9_-]{6,64})\b/) || [])[1];
  if (!id) throw new Error(`no id in publish result: ${text.slice(0, 300)}`);
  console.log(`${artifactKey} ${id}`);
}
