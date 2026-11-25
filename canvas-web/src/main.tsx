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
import { BoardMoveContext, BoardReportContext, DarkContext } from "./board/BoardView";
import type { BoardReport } from "./board/layout";

const started = performance.now();
const elapsed = () => Math.round(performance.now() - started);

const bridge = installBridge(window);

/** How long the boards' reports must rest before the measured ones are taken. */
const SETTLE_MS = 150;
/** How long a check waits for the measuring render at most; a page in a hidden tab may never lay out. */
const MEASURE_MS = 2000;

/**
 * Renders [openui] out of sight, at the board's own widths, so every Board measures the heights of its notes and frames;
 * resolves with their reports once they rest, or null when none came in time. The visible frame is left alone.
 */
function measureBoards(openui: string): Promise<BoardReport[] | null> {
  return new Promise((resolve) => {
    const host = document.createElement("div");
    host.className = "canvas-measure";
    host.setAttribute("aria-hidden", "true");
    document.body.appendChild(host);
    const root = createRoot(host);
    const reports = new Map<string, BoardReport>();
    let settle: ReturnType<typeof setTimeout> | undefined;
    const finish = () => {
      clearTimeout(settle);
      clearTimeout(deadline);
      root.unmount();
      host.remove();
      resolve(reports.size > 0 ? [...reports.values()] : null);
    };
    const deadline = setTimeout(finish, MEASURE_MS);
    const collect = (report: BoardReport) => {
      reports.set(report.board, report);
      clearTimeout(settle);
      settle = setTimeout(finish, SETTLE_MS);
    };
    root.render(
      <BoardReportContext.Provider value={collect}>
        <ThemeProvider mode="light">
          <div className="canvas">
            <Renderer response={openui} library={canvasLibrary} isStreaming={false} />
          </div>
        </ThemeProvider>
      </BoardReportContext.Provider>,
    );
  });
}

bridge.setMeasurer(measureBoards);

/**
 * Has Chromium repaint the whole page: an opacity change on the body damages every layer. The IDE's off-screen JCEF
 * browser can keep parts of the previous frame on screen after a switch (a board's composited layers in particular)
 * when only the changed regions are sent to it.
 */
function repaintAll() {
  requestAnimationFrame(() => {
    document.body.style.opacity = "0.999";
    requestAnimationFrame(() => (document.body.style.opacity = ""));
  });
}

function Canvas() {
  // Until the plugin shows a frame (and in a plain browser), the spike's sample.
  const [frame, setFrame] = useState<ShowCall>({ frame: "sample", openui: location.hash === "#board" ? BOARD_SAMPLE : SAMPLE, dark: matchMedia("(prefers-color-scheme: dark)").matches });
  // After every frame switch, and again once a board has fitted its view and measured its notes.
  useEffect(() => {
    repaintAll();
    const later = setTimeout(repaintAll, 400);
    return () => clearTimeout(later);
  }, [frame.frame, frame.openui]);
  useEffect(() => {
    bridge.onShow(setFrame);
    bridge.post({ type: "ready", ms: elapsed() });
  }, []);
  return (
    <DarkContext.Provider value={frame.dark}>
    <BoardMoveContext.Provider value={(move) => bridge.post({ type: "move", frame: frame.frame, ...move })}>
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
    </BoardMoveContext.Provider>
    </DarkContext.Provider>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><Canvas /></StrictMode>);
