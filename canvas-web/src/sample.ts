// The sample document, one of each kind of component the canvas is meant for (from the M1 spike); the page shows it until the plugin shows a frame, and in a plain browser.
import sample from "../../src/main/resources/canvas/spike-sample.oui?raw";

export const SAMPLE: string = sample;

/** The board spike's sample (src/board/board-sample.oui), shown in a plain browser when the URL ends with #board. */
export { default as BOARD_SAMPLE } from "./board/board-sample.oui?raw";
