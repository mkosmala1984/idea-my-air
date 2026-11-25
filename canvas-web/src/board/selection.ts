// What the board sends the canvas's manager about the elements the user selected: their rectangles and their text.
import type { ElementNode } from "@openuidev/react-lang";
import type { BoardReport } from "./layout";

export interface SelectionContext {
  board: string;
  /** The selected elements in grid units, with a note's text, a shape's label or a frame's title. */
  elements: { id: string; kind: string; parent: string | null; x: number; y: number; w: number; h: number; text: string }[];
  edges: { id: string; from: string; to: string; label?: string }[];
}

const textOf = (node: ElementNode | undefined) => String(node?.props.text ?? node?.props.label ?? node?.props.title ?? "");

/** The `@ToAssistant` context of a selection: [elementIds] and [edgeIds] in report order, unknown ids left out. */
export function selectionContext(
  report: BoardReport, nodes: ReadonlyMap<string, ElementNode>, edgeLabel: (id: string) => string | undefined,
  elementIds: ReadonlySet<string>, edgeIds: ReadonlySet<string>,
): SelectionContext {
  return {
    board: report.board,
    elements: report.elements
      .filter((e) => elementIds.has(e.id))
      .map((e) => ({ id: e.id, kind: e.kind, parent: e.parent, x: e.x, y: e.y, w: e.w, h: e.h, text: textOf(nodes.get(e.id)) })),
    edges: report.edges.filter((e) => edgeIds.has(e.id)).map((e) => {
      const label = edgeLabel(e.id);
      return label ? { ...e, label } : { ...e };
    }),
  };
}

/** The message a selection is sent with: what the user typed, else a line naming the selected ids. */
export function selectionMessage(context: SelectionContext, typed: string): string {
  if (typed.trim()) return typed.trim();
  const ids = [...context.elements.map((e) => e.id), ...context.edges.map((e) => e.id)];
  return `Selected on board ${context.board}: ${ids.join(", ")}`;
}
