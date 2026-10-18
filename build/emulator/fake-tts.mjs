// Stand-in speech worker for the emulator Listen test: answers the Kokoro worker API
// (POST /speech with a bearer token) with a quiet tone WAV whose length follows the text.
// Binds to 127.0.0.1 only.
//
//   LISTEN_PORT=3490 TOKEN_FILE=/run/fake-tts.token node fake-tts.mjs
import http from "node:http";
import { readFileSync } from "node:fs";

const PORT = Number(process.env.LISTEN_PORT || 3490);
const TOKEN = readFileSync(process.env.TOKEN_FILE, "utf8").trim();
const RATE = 16000;

function wav(seconds) {
  const samples = Math.round(RATE * seconds);
  const out = Buffer.alloc(44 + samples * 2);
  out.write("RIFF", 0); out.writeUInt32LE(36 + samples * 2, 4); out.write("WAVE", 8);
  out.write("fmt ", 12); out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22);
  out.writeUInt32LE(RATE, 24); out.writeUInt32LE(RATE * 2, 28); out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34);
  out.write("data", 36); out.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) out.writeInt16LE(Math.round(2000 * Math.sin((2 * Math.PI * 440 * i) / RATE)), 44 + i * 2);
  return out;
}

http.createServer((req, res) => {
  let body = "";
  req.on("data", (chunk) => { body += chunk; if (body.length > 100000) req.destroy(); });
  req.on("end", () => {
    if (req.method !== "POST" || req.url !== "/speech" || req.headers.authorization !== `Bearer ${TOKEN}`) {
      res.writeHead(404).end();
      return;
    }
    let text = "";
    try { text = String(JSON.parse(body).text || ""); } catch {}
    const audio = wav(Math.min(12, Math.max(1.5, text.length * 0.06)));
    console.log(`speech ${text.length} chars -> ${audio.length} bytes`);
    res.writeHead(200, { "content-type": "audio/wav", "content-length": audio.length }).end(audio);
  });
}).listen(PORT, "127.0.0.1", () => console.log(`fake tts 127.0.0.1:${PORT}`));
