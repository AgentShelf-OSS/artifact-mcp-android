// Local test identity for the emulator. Stands in for Cloudflare Access in front of an Artifact MCP
// dev server that runs with TRUST_ACCESS_HEADERS=1 on loopback.
//
// Every request gets `Cf-Access-Authenticated-User-Email: $TEST_EMAIL`; any identity header the
// client sent is dropped first. Binds to 127.0.0.1 only. The emulator reaches it via `adb reverse`.
//
//   LISTEN_PORT=8480 UPSTREAM_PORT=3480 TEST_EMAIL=tester@emulator.test node identity-proxy.mjs
import http from "node:http";

const LISTEN_PORT = Number(process.env.LISTEN_PORT || 8480);
const UPSTREAM_PORT = Number(process.env.UPSTREAM_PORT || 3480);
const EMAIL = process.env.TEST_EMAIL || "tester@emulator.test";

const server = http.createServer((req, res) => {
  const headers = {};
  for (const [name, value] of Object.entries(req.headers)) {
    if (!name.toLowerCase().startsWith("cf-access-")) headers[name] = value;
  }
  headers["cf-access-authenticated-user-email"] = EMAIL;
  const upstream = http.request(
    { host: "127.0.0.1", port: UPSTREAM_PORT, method: req.method, path: req.url, headers },
    (up) => {
      res.writeHead(up.statusCode || 502, up.headers);
      up.pipe(res);
    },
  );
  upstream.on("error", (error) => {
    if (!res.headersSent) res.writeHead(502, { "content-type": "text/plain" });
    res.end(`identity proxy: upstream error ${error.code || error.message}\n`);
  });
  req.pipe(upstream);
});

server.listen(LISTEN_PORT, "127.0.0.1", () => {
  console.log(`identity proxy 127.0.0.1:${LISTEN_PORT} -> 127.0.0.1:${UPSTREAM_PORT} as ${EMAIL}`);
});
