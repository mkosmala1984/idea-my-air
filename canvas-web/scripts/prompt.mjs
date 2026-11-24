// Writes the OpenUI system prompt of the canvas library (src/library.ts, systemPrompt()) to the plugin's resources.
// library.ts is TypeScript, so it is loaded through Vite's SSR module loader; no build of its own is needed.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

const out = process.argv[2];
const server = await createServer({
  root: resolve(dirname(fileURLToPath(import.meta.url)), ".."),
  configFile: false,
  appType: "custom",
  logLevel: "error",
  server: { middlewareMode: true, hmr: false, ws: false },
  optimizeDeps: { noDiscovery: true, include: [] },
});
try {
  const { systemPrompt } = await server.ssrLoadModule("/src/library.ts");
  const prompt = systemPrompt();
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, prompt);
  console.log(`${out}: ${prompt.length} chars`);
} finally {
  await server.close();
}
