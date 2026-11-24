import { defineConfig } from "vitest/config";

// Test files run one after another: run in parallel, about one run in four saw canvasLibrary undefined in whichever
// file imported it (the shared module runner and the OpenUI packages' chunks), never with files in sequence.
export default defineConfig({ test: { environment: "jsdom", css: false, fileParallelism: false } });
