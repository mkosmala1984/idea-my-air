// The board's OpenUI components. Board reads its items as data (the solver places them) and renders them itself;
// Shape, Note, Frame and Edge render nothing of their own, only a hint when written outside a Board.
import { createElement } from "react";
import { z } from "zod/v4";
import { defineComponent } from "@openuidev/react-lang";
import { BoardView } from "./BoardView";

const BoxSchema = z.object({
  x: z.number().int().optional(),
  y: z.number().int().optional(),
  w: z.number().int().positive().optional(),
  h: z.number().int().positive().optional(),
  rightOf: z.string().optional(),
  leftOf: z.string().optional(),
  below: z.string().optional(),
  above: z.string().optional(),
  gap: z.number().int().min(0).optional(),
  align: z.enum(["start", "center", "end"]).optional(),
  pinned: z.boolean().optional(),
});

const outsideBoard = (name: string) => () =>
  createElement("span", { className: "myair-board-outside" }, `${name} belongs in a Board's items.`);

export const Shape = defineComponent({
  name: "Shape",
  props: z.object({
    kind: z.enum(["rect", "rounded", "ellipse", "diamond", "cylinder", "group"]),
    label: z.string(),
    box: BoxSchema,
    children: z.array(z.any()).optional(),
    layout: z.enum(["free", "row", "column", "grid"]).optional(),
    gap: z.number().int().min(0).optional(),
    columns: z.number().int().positive().optional(),
  }),
  description:
    "A board shape with a label. With children (Shape, Note, Frame, Edge) it is a container: layout 'free' (default) " +
    "places them by their own boxes relative to its content area, 'row', 'column' and 'grid' place them in order with gap; " +
    "without w/h it grows to fit them. kind 'group' draws a dashed region.",
  component: outsideBoard("Shape"),
});

export const Note = defineComponent({
  name: "Note",
  props: z.object({
    text: z.string(),
    box: BoxSchema,
    color: z.enum(["yellow", "blue", "green", "pink", "gray"]).optional(),
  }),
  description: "A sticky note on a board. Without h its height fits the text.",
  component: outsideBoard("Note"),
});

export const Frame = defineComponent({
  name: "Frame",
  props: z.object({
    title: z.string(),
    box: BoxSchema,
    content: z.array(z.any()),
  }),
  description:
    "A titled frame on a board holding any canvas components (tables, charts, forms, buttons...). Without h its height fits " +
    "the content. Drag it by its title.",
  component: outsideBoard("Frame"),
});

export const Edge = defineComponent({
  name: "Edge",
  props: z.object({
    from: z.string(),
    to: z.string(),
    label: z.string().optional(),
    route: z.enum(["orthogonal", "straight", "curved"]).optional(),
    arrow: z.enum(["end", "both", "none"]).optional(),
  }),
  description: "A connector between two board elements, named by their statement names. route default 'orthogonal', arrow default 'end'.",
  component: outsideBoard("Edge"),
});

export const Board = defineComponent({
  name: "Board",
  props: z.object({
    items: z.array(z.union([Shape.ref, Note.ref, Frame.ref, Edge.ref])),
    unit: z.number().int().positive().optional(),
    height: z.number().int().positive().optional(),
  }),
  description:
    "A whiteboard: zoomable, pannable, elements draggable. Positions and sizes are whole grid units (unit px each, default 24). " +
    "Every element's box is {x, y} (absolute, in its container's content area) or one anchor {rightOf | leftOf | below | above: " +
    "'sibling name', gap?, align?: start|center|end}, plus optional w and h. Anchors name sibling statements only. " +
    "Give every element its own statement: its name is its id for anchors and edges. The board reaches down to the bottom of the canvas view; " +
    "height is its least height in px (default 320). " +
    "When the user drags an element, its box becomes {x, y, w?, h?, pinned: true}; leave a pinned element where it is unless the user asks. " +
    "The user can select elements and edges and send you a question about them: its context is {selection: {board, elements: " +
    "[{id, kind, parent, x, y, w, h, text}], edges: [{id, from, to, label?}]}}; patch those statements when asked to change them.",
  component: ({ props, renderNode, statementId }) => createElement(BoardView, { props, renderNode, statementId }),
});
