// Car design fixtures for the Android Automotive emulator test (test-car.sh): two more
// organizations with categories, one with a gallery color (#356B9F) and one without (the app then
// picks a palette color), and eight short Listen stories.
//
//   PROXY=http://127.0.0.1:8480 MCP_URL=http://127.0.0.1:3480/mcp HOMELAB_KEY=... BOOKCLUB_KEY=... node car-seed.mjs
//
// Org registry calls go through the identity proxy as the test admin. Prints `<artifact_key> <id>`.
const proxy = process.env.PROXY || "http://127.0.0.1:8480";
const publicOrigin = process.env.PUBLIC_ORIGIN || "http://localhost:8480";
const url = process.env.MCP_URL || "http://127.0.0.1:3480/mcp";
const keys = { homelab: process.env.HOMELAB_KEY, bookclub: process.env.BOOKCLUB_KEY };
if (!keys.homelab || !keys.bookclub) throw new Error("HOMELAB_KEY and BOOKCLUB_KEY are required");

async function admin(path, body) {
  const response = await fetch(proxy + path, {
    method: "POST",
    headers: { "content-type": "application/json", origin: publicOrigin, "x-artifact-mutation": "1" },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  console.error(`admin ${path}: HTTP ${response.status} ${text.slice(0, 120)}`);
}

await admin("/settings/orgs", { name: "homelab", label: "Homelab" });
await admin("/settings/orgs", { name: "bookclub", label: "Book club" });
await admin("/settings/orgs/homelab/color", { color: "#356B9F" });

const stories = [
  ["homelab", "car/baby-log", "Baby log: the first month", "Family", ["Night feeds", "The first bath", "Sleep, finally"]],
  ["homelab", "car/road-trip", "Road trip to Luxor", "Family", ["Leaving Cairo", "The desert road", "Arrival"]],
  ["homelab", "car/rack-tour", "Rack dashboard tour", "Infrastructure", ["The switch", "The storage shelf", "The quiet fans"]],
  ["homelab", "car/backups", "How the backup chain works", "Infrastructure", ["Snapshots", "Offsite copies", "The restore test"]],
  ["homelab", "car/network-notes", "Notes from the network rebuild, spring 2026: VLANs, DNS and the long weekend", "", ["Friday night", "Saturday", "Monday morning"]],
  ["bookclub", "car/frankenstein", "Frankenstein, read aloud", "Books", ["Letter one", "The laboratory", "The ice"]],
  ["bookclub", "car/orwell", "1984: Part one", "Books", ["The clocks strike thirteen", "The ministry", "The diary"]],
  ["bookclub", "car/winter-picks", "Book club picks for winter", "Lists", ["Something long", "Something short", "Something funny"]],
];

function html(title, sections) {
  const body = sections.map((name, i) =>
    `<h2>${name}</h2>\n<p>This is part ${i + 1} of ${title}. It is a short passage for the car test, read by the speech worker.</p>\n<p>The road is quiet and the story goes on.</p>`).join("\n");
  return `<!doctype html>\n<html lang="en">\n<head><meta charset="utf-8"><title>${title}</title></head>\n<body>\n<h1>${title}</h1>\n${body}\n</body>\n</html>\n`;
}

let rpcId = 0;
for (const [org, artifactKey, title, category, sections] of stories) {
  const response = await fetch(url, {
    method: "POST",
    headers: { authorization: `Bearer ${keys[org]}`, "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify({
      jsonrpc: "2.0", id: ++rpcId, method: "tools/call",
      params: { name: "publish_artifact", arguments: { html: html(title, sections), title, description: "Car test story", artifact_key: artifactKey, category } },
    }),
  });
  const body = await response.json();
  if (!response.ok || body.error || body.result?.isError) throw new Error(`publish ${artifactKey}: ${JSON.stringify(body).slice(0, 300)}`);
  const id = body.result.structuredContent?.id;
  if (!id) throw new Error(`no id for ${artifactKey}`);
  console.log(`${artifactKey} ${id}`);
  // Distinct updated_at values, so newest-first order is deterministic.
  await new Promise((r) => setTimeout(r, 1100));
}
