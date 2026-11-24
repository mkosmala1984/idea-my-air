import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Renderer } from "@openuidev/react-lang";
import { ThemeProvider } from "@openuidev/react-ui";
import "@openuidev/react-ui/components.css";
import "@xyflow/react/dist/style.css";
import "./canvas.css";
import { installBridge, parseSummary, wireAction, wireErrors } from "./bridge";
import { canvasLibrary } from "./library";
import type { ShowCall } from "./protocol";
import { BOARD_SAMPLE, SAMPLE } from "./sample";
import { DarkContext } from "./board/BoardView";

const started = performance.now();
const elapsed = () => Math.round(performance.now() - started);

const bridge = installBridge(window);

function Canvas() {
  // Until the plugin shows a frame (and in a plain browser), the spike's sample.
  const [frame, setFrame] = useState<ShowCall>({ frame: "sample", openui: location.hash === "#board" ? BOARD_SAMPLE : SAMPLE, dark: matchMedia("(prefers-color-scheme: dark)").matches });
  useEffect(() => {
    bridge.onShow(setFrame);
    bridge.post({ type: "ready", ms: elapsed() });
  }, []);
  return (
    <DarkContext.Provider value={frame.dark}>
    <ThemeProvider mode={frame.dark ? "dark" : "light"}>
      <div className="canvas">
        <Renderer
          key={frame.frame}
          response={frame.openui}
          library={canvasLibrary}
          isStreaming={false}
          initialState={frame.state ?? undefined}
          onAction={(event) => bridge.post({ type: "action", frame: frame.frame, event: wireAction(event) })}
          onStateUpdate={(state) => bridge.states.update(frame.frame, state)}
          onParseResult={(result) => bridge.post({ type: "parsed", frame: frame.frame, ms: elapsed(), ...parseSummary(result) })}
          onError={(errors) => bridge.post({ type: "errors", frame: frame.frame, errors: wireErrors(errors) })}
        />
      </div>
    </ThemeProvider>
    </DarkContext.Provider>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><Canvas /></StrictMode>);
