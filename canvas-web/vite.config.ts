import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

// One self-contained HTML file: the plugin loads it with JBCefBrowser.loadHTML, so nothing may be fetched.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    outDir: "../src/main/resources/canvas",
    emptyOutDir: false,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
  },
});
