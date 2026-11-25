// The board on the page: the solver's grid rectangles as React Flow nodes, a Frame's content rendered by OpenUI inside
// its node, and the geometry report kept for the agent (window.myAirBoards) and shown in a corner.
import { createContext, memo, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  Background,
  BackgroundVariant,
  BaseEdge,
  ControlButton,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  NodeResizer,
  Panel,
  Position,
  ReactFlow,
  type Edge,
  type EdgeChange,
  type EdgeProps,
  type InternalNode,
  type Node,
  type NodeChange,
  type NodeProps,
  useInternalNode,
  useReactFlow,
} from "@xyflow/react";
import { useTriggerAction, type ElementNode } from "@openuidev/react-lang";
import { DEFAULT_UNIT, facingSides, midpoint, movedBox, report, resizedBox, routeEdge, solve, type BoardReport, type ElementSpec, type Route } from "./layout";
import { boardSpec } from "./spec";
import type { BoardEditOp } from "../protocol";
import { selectionContext, selectionMessage } from "./selection";

/** Whether the page shows the dark theme; main.tsx provides it from the plugin's frame. */
export const DarkContext = createContext(false);

/** Where the user dropped a board element, in grid units of its container's content area, with its box's own w and h. */
export type BoardMove = { element: string; x: number; y: number; w: number | null; h: number | null };

/** Receives every drop of a dragged element; main.tsx posts it to the plugin, which writes it into the document. */
export const BoardMoveContext = createContext<(move: BoardMove) => void>(() => {});

/** Receives what the user adds, connects, removes or retitles on board [board]; main.tsx posts it as `edit`. */
export const BoardEditContext = createContext<(board: string, edit: BoardEditOp) => void>(() => {});

declare global {
  interface Window {
    /** The latest geometry report of every board on the page, by the Board's statement id. */
    myAirBoards?: Record<string, BoardReport>;
  }
}

/**
 * Receives every board's geometry report whenever it changes (heights measured included). By default it is kept in
 * window.myAirBoards; the render check (main.tsx `measureBoards`) collects them from its hidden render instead.
 */
export const BoardReportContext = createContext<(report: BoardReport) => void>((r) => {
  window.myAirBoards = { ...window.myAirBoards, [r.board]: r };
});

/** The least height of a board, in px, when it gives none; it otherwise reaches down to the bottom of the view. */
export const MIN_HEIGHT = 320;
/** The canvas's bottom padding (canvas.css `.canvas`), kept below a board that fills the view. */
const BOTTOM_GAP = 12;

/**
 * The height of the board in [ref]: from where it starts on the page down to the bottom of the view, so a board fills
 * the canvas tab, and never less than [least] (the Board's height, else [MIN_HEIGHT]). Follows the window's size.
 */
function useFillHeight(ref: React.RefObject<HTMLDivElement | null>, least: number) {
  const [height, setHeight] = useState(least);
  useLayoutEffect(() => {
    const fit = () => {
      const el = ref.current;
      if (!el) return;
      const top = el.getBoundingClientRect().top + window.scrollY;
      setHeight(Math.max(least, Math.floor(window.innerHeight - top - BOTTOM_GAP)));
    };
    fit();
    window.addEventListener("resize", fit);
    return () => window.removeEventListener("resize", fit);
  }, [ref, least]);
  return height;
}

type Measure = (id: string, px: number) => void;

interface ElementData extends Record<string, unknown> {
  spec: ElementSpec;
  node: ElementNode;
  renderNode: (value: unknown) => ReactNode;
  measure: Measure;
  /** Whether the height comes from the content (no h in the box): then the content's height is measured. */
  autoHeight: boolean;
  /** Whether the user may move and resize it: not inside a row, column or grid, which places and sizes it. */
  free: boolean;
  unit: number;
  /** A resize ended at these pixels (relative to the parent): the board posts it as a move with w and h. */
  onResized: (id: string, px: { x: number; y: number; width: number; height: number }) => void;
  /** The user edited the element's text (a note's text, a shape's label, a frame's title). */
  onText: (id: string, text: string) => void;
}

const SIDES = [Position.Top, Position.Right, Position.Bottom, Position.Left];

/** Invisible handles on every side, so an edge leaves and enters on the sides facing each other. */
const Handles = () => (
  <>
    {SIDES.map((side) => (
      <Handle key={`s-${side}`} id={side} type="source" position={side} className="myair-board-handle" isConnectable={false} />
    ))}
    {SIDES.map((side) => (
      <Handle key={`t-${side}`} id={side} type="target" position={side} className="myair-board-handle" isConnectable={false} />
    ))}
  </>
);

/** Handles on a selected element's corners and sides; the end of a resize goes to the plugin as the box's new x, y, w and h. */
const Resizer = ({ data, selected }: { data: ElementData; selected: boolean }) =>
  data.free ? (
    <NodeResizer isVisible={selected} minWidth={data.unit} minHeight={data.unit} color="var(--openui-interactive-accent-default, #3574f0)"
      onResizeEnd={(_, p) => data.onResized(data.spec.id, p)} />
  ) : null;

/**
 * An element's text that a double click turns into a text box: Enter (Ctrl+Enter in a multi-line one) or leaving it
 * saves through [onSave] when it changed, Escape gives up.
 */
function EditableText({ text, multiline, className, onSave }: { text: string; multiline?: boolean; className?: string; onSave: (text: string) => void }) {
  const [editing, setEditing] = useState<string | null>(null);
  if (editing == null)
    return <div className={className} onDoubleClick={() => setEditing(text)} title="Double-click to edit">{text}</div>;
  const done = (save: boolean) => {
    if (save && editing !== text) onSave(editing);
    setEditing(null);
  };
  const common = {
    autoFocus: true,
    value: editing,
    className: `myair-board-edit nodrag nowheel nopan ${className ?? ""}`,
    onChange: (e: React.ChangeEvent<HTMLTextAreaElement | HTMLInputElement>) => setEditing(e.target.value),
    onBlur: () => done(true),
    onKeyDown: (e: React.KeyboardEvent) => {
      e.stopPropagation();
      if (e.key === "Escape") done(false);
      if (e.key === "Enter" && (!multiline || e.ctrlKey || e.metaKey)) done(true);
    },
  };
  return multiline ? <textarea {...common} rows={4} /> : <input {...common} />;
}

/** Reports the natural height of [ref]'s element whenever it changes. */
function useMeasured(ref: React.RefObject<HTMLDivElement | null>, data: ElementData) {
  const { measure, spec, autoHeight } = data;
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || !autoHeight) return;
    const send = () => measure(spec.id, el.offsetHeight);
    send();
    const observer = new ResizeObserver(send);
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref, measure, spec.id, autoHeight]);
}

const NoteNode = memo(({ data, selected }: NodeProps<Node<ElementData>>) => {
  const ref = useRef<HTMLDivElement>(null);
  useMeasured(ref, data);
  const color = (data.node.props.color as string | undefined) ?? "yellow";
  return (
    <>
      <Resizer data={data} selected={selected} />
      <div className={`myair-board-note myair-board-note-${color}`}>
        <Handles />
        <div ref={ref}>
          <EditableText text={String(data.node.props.text ?? "")} multiline className="myair-board-note-text" onSave={(t) => data.onText(data.spec.id, t)} />
        </div>
      </div>
    </>
  );
});

const FrameNode = memo(({ data, selected }: NodeProps<Node<ElementData>>) => {
  const ref = useRef<HTMLDivElement>(null);
  useMeasured(ref, data);
  return (
    <>
      <Resizer data={data} selected={selected} />
      <div className="myair-board-frame">
        <Handles />
        <div ref={ref}>
          <EditableText text={String(data.node.props.title ?? "")} className="myair-board-frame-title myair-board-drag" onSave={(t) => data.onText(data.spec.id, t)} />
          {/* nodrag/nowheel/nopan: the content keeps the mouse, so forms, tabs and scrolling work inside. */}
          <div className="myair-board-frame-body nodrag nowheel nopan">{data.renderNode(data.node.props.content)}</div>
        </div>
      </div>
    </>
  );
});

function shapePath(kind: string, w: number, h: number): ReactNode {
  const s = { className: "myair-board-shape-outline" };
  switch (kind) {
    case "ellipse":
      return <ellipse {...s} cx={w / 2} cy={h / 2} rx={w / 2 - 1} ry={h / 2 - 1} />;
    case "diamond":
      return <polygon {...s} points={`${w / 2},1 ${w - 1},${h / 2} ${w / 2},${h - 1} 1,${h / 2}`} />;
    case "cylinder": {
      const ry = Math.min(12, h / 6);
      return (
        <>
          <path {...s} d={`M1,${ry} L1,${h - ry} A${w / 2 - 1},${ry} 0 0 0 ${w - 1},${h - ry} L${w - 1},${ry}`} />
          <ellipse {...s} cx={w / 2} cy={ry} rx={w / 2 - 1} ry={ry - 1} />
        </>
      );
    }
    default:
      return <rect {...s} x={1} y={1} width={w - 2} height={h - 2} rx={kind === "rect" ? 2 : 10} strokeDasharray={kind === "group" ? "6 4" : undefined} />;
  }
}

const ShapeNode = memo(({ data, width, height, selected }: NodeProps<Node<ElementData>>) => {
  const kind = String(data.node.props.kind ?? "rect");
  const container = data.spec.children.length > 0;
  const w = width ?? 0;
  const h = height ?? 0;
  return (
    <>
      <Resizer data={data} selected={selected} />
      <div className={`myair-board-shape myair-board-shape-${kind}${container ? " myair-board-container" : ""}`}>
        <Handles />
        <svg className="myair-board-shape-svg" width={w} height={h}>{shapePath(kind, w, h)}</svg>
        <EditableText text={data.spec.label ?? ""} className="myair-board-shape-label" onSave={(t) => data.onText(data.spec.id, t)} />
      </div>
    </>
  );
});

const nodeTypes = { note: NoteNode, frame: FrameNode, shape: ShapeNode };

/** Held while clicking, adds to the selection (Shift + drag draws a selection box). */
const MULTI_SELECT_KEYS = ["Control", "Meta", "Shift"];

interface EdgeData extends Record<string, unknown> {
  route: Route;
  unit: number;
}

/** A node's rectangle in grid units, where it is now (while dragged too). */
const rectOf = (n: InternalNode, unit: number) => ({
  x: n.internals.positionAbsolute.x / unit,
  y: n.internals.positionAbsolute.y / unit,
  w: (n.width ?? n.measured.width ?? 0) / unit,
  h: (n.height ?? n.measured.height ?? 0) / unit,
});

/** Draws the path [routeEdge] gives, the one the edge-crosses check follows, from the nodes' current rectangles. */
const BoardEdge = memo(({ id, source, target, data, label, markerEnd, markerStart }: EdgeProps<Edge<EdgeData>>) => {
  const from = useInternalNode(source);
  const to = useInternalNode(target);
  if (!from || !to || !data) return null;
  const { unit } = data;
  const path = routeEdge(rectOf(from, unit), rectOf(to, unit), data.route);
  const mid = midpoint(path);
  const d = path.map((p, i) => `${i === 0 ? "M" : "L"}${p.x * unit},${p.y * unit}`).join(" ");
  return <BaseEdge id={id} path={d} label={label} labelX={mid.x * unit} labelY={mid.y * unit} markerEnd={markerEnd} markerStart={markerStart} />;
});

const edgeTypes = { board: BoardEdge };

export interface BoardProps {
  items: unknown[];
  unit?: number;
  height?: number;
}

/**
 * Fits the view again when the board's height changes: fitView runs once, when the board first lays out, which is
 * before useFillHeight has grown it to the bottom of the view (or after the window resized).
 */
function FitOnResize({ height }: { height: number }) {
  const { fitView } = useReactFlow();
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    // After React Flow has taken the new size.
    const frame = requestAnimationFrame(() => void fitView());
    return () => cancelAnimationFrame(frame);
  }, [height, fitView]);
  return null;
}

export function BoardView({ props, renderNode, statementId }: { props: BoardProps; renderNode: (v: unknown) => ReactNode; statementId?: string }) {
  const dark = useContext(DarkContext);
  const onMove = useContext(BoardMoveContext);
  const onReport = useContext(BoardReportContext);
  const onEditBoard = useContext(BoardEditContext);
  const boardName = statementId ?? "board";
  const edit = useCallback((op: BoardEditOp) => onEditBoard(boardName, op), [onEditBoard, boardName]);
  const onText = useCallback((id: string, text: string) => edit({ op: "text", element: id, text }), [edit]);
  const unit = props.unit ?? DEFAULT_UNIT;
  const spec = useMemo(() => boardSpec(props.items ?? []), [props.items]);

  // Natural heights in pixels, as the nodes measured them; the solver gets whole grid units.
  const [measuredPx, setMeasuredPx] = useState<Record<string, number>>({});
  const measure = useCallback<Measure>((id, px) => setMeasuredPx((m) => (m[id] === px ? m : { ...m, [id]: px })), []);
  const measured = useMemo(() => new Map(Object.entries(measuredPx).map(([id, px]) => [id, Math.ceil(px / unit)])), [measuredPx, unit]);

  const solved = useMemo(() => solve(spec.elements, spec.edges, measured), [spec, measured]);
  const geometry = useMemo(() => report(statementId ?? "board", spec.elements, spec.edges, solved, unit, spec.problems), [statementId, spec, solved, unit]);

  // Positions the user dragged to, in pixels relative to the parent; the spike keeps them on the page only.
  const [dragged, setDragged] = useState<Record<string, { x: number; y: number }>>({});
  // What the user selected (click, Ctrl/Shift+click, Shift+drag a box): sent to the manager from the selection bar.
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [selectedEdges, setSelectedEdges] = useState<ReadonlySet<string>>(new Set());
  // Sizes the user resized to, in pixels, until the plugin's document arrives with them.
  const [resized, setResized] = useState<Record<string, { width: number; height: number }>>({});
  const onNodesChange = useCallback((changes: NodeChange<Node<ElementData>>[]) => {
    const moves = changes.flatMap((c) => (c.type === "position" && c.position ? [[c.id, c.position] as const] : []));
    if (moves.length > 0) setDragged((d) => ({ ...d, ...Object.fromEntries(moves) }));
    // Only a resize sets the attributes; React Flow's own measuring reports dimensions too.
    const sizes = changes.flatMap((c) => (c.type === "dimensions" && c.setAttributes && c.dimensions ? [[c.id, c.dimensions] as const] : []));
    if (sizes.length > 0) setResized((r) => ({ ...r, ...Object.fromEntries(sizes) }));
    setSelected((s) => applySelect(s, changes));
  }, []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => setSelectedEdges((s) => applySelect(s, changes)), []);
  // A changed layout places everything itself, the moves the user made included once the plugin wrote them. Keyed on
  // the specs' text, not their identity: the renderer may hand over new item objects for an unchanged document.
  const layoutKey = useMemo(() => JSON.stringify(spec.elements), [spec]);
  useEffect(() => {
    setDragged({});
    setResized({});
  }, [layoutKey]);

  const parents = useMemo(() => {
    const map = new Map<string, { el: ElementSpec; parent: ElementSpec | null }>();
    const walk = (els: ElementSpec[], parent: ElementSpec | null) => els.forEach((el) => (map.set(el.id, { el, parent }), walk(el.children, el)));
    walk(spec.elements, null);
    return map;
  }, [spec]);
  const onNodeDragStop = useCallback((_: unknown, _node: Node, dropped: Node[]) => {
    for (const n of dropped) {
      const found = parents.get(n.id);
      if (found) onMove({ element: n.id, ...movedBox(n.position, unit, found.el, found.parent) });
    }
  }, [parents, unit, onMove]);
  const onResized = useCallback((id: string, px: { x: number; y: number; width: number; height: number }) => {
    const found = parents.get(id);
    if (found) onMove({ element: id, ...resizedBox(px, unit, found.parent) });
  }, [parents, unit, onMove]);

  const nodes = useMemo(() => {
    const list: Node<ElementData>[] = [];
    const walk = (els: ElementSpec[], parent?: ElementSpec) => {
      for (const el of els) {
        const r = solved.local.get(el.id)!;
        const autoHeight = el.children.length === 0 && el.box.h == null && el.kind !== "shape";
        // A row, column or grid places and sizes its children itself; only free placement can be dragged and resized.
        const free = !parent || (parent.layout ?? "free") === "free";
        list.push({
          id: el.id,
          type: el.kind,
          position: dragged[el.id] ?? { x: r.x * unit, y: r.y * unit },
          width: resized[el.id]?.width ?? r.w * unit,
          height: resized[el.id]?.height ?? r.h * unit,
          parentId: parent?.id,
          extent: parent ? "parent" : undefined,
          draggable: free,
          dragHandle: el.kind === "frame" ? ".myair-board-drag" : undefined,
          selected: selected.has(el.id),
          data: { spec: el, node: spec.nodes.get(el.id)!, renderNode, measure, autoHeight, free, unit, onResized, onText },
        });
        walk(el.children, el);
      }
    };
    walk(spec.elements);
    return list;
  }, [spec, solved, unit, dragged, resized, renderNode, measure, selected, onResized, onText]);

  const edges = useMemo<Edge[]>(
    () =>
      spec.edges
        .filter((e) => solved.absolute.has(e.from) && solved.absolute.has(e.to))
        .map((e) => {
          const node = edgeNode(props.items ?? [], e.id);
          // React Flow wants handles to draw an edge at all; BoardEdge draws its own path.
          const [sourceHandle, targetHandle] = facingSides(solved.absolute.get(e.from)!, solved.absolute.get(e.to)!);
          const arrow = (node?.props.arrow as string | undefined) ?? "end";
          return {
            id: e.id,
            source: e.from,
            target: e.to,
            sourceHandle,
            targetHandle,
            type: "board",
            data: { route: e.route ?? "orthogonal", unit },
            label: (node?.props.label as string | undefined) || undefined,
            markerEnd: arrow === "none" ? undefined : { type: MarkerType.ArrowClosed },
            markerStart: arrow === "both" ? { type: MarkerType.ArrowClosed } : undefined,
            zIndex: 1000,
            selected: selectedEdges.has(e.id),
          };
        }),
    [spec, solved, props.items, unit, selectedEdges],
  );

  useEffect(() => onReport(geometry), [geometry, onReport]);

  const boardRef = useRef<HTMLDivElement>(null);
  const height = useFillHeight(boardRef, props.height ?? MIN_HEIGHT);
  const [showReport, setShowReport] = useState(false);
  // Off by default: in its corner it covers the board's content, a frame's table or buttons included.
  const [showMap, setShowMap] = useState(false);
  const errors = geometry.problems.filter((p) => p.severity === "error").length;
  const warnings = geometry.problems.length - errors;

  return (
    <div ref={boardRef} className="myair-board" style={{ height }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        multiSelectionKeyCode={MULTI_SELECT_KEYS}
        onNodeDragStop={onNodeDragStop}
        colorMode={dark ? "dark" : "light"}
        snapToGrid
        snapGrid={[unit, unit]}
        fitView
        minZoom={0.2}
        nodesConnectable={false}
        proOptions={{ hideAttribution: true }}
        deleteKeyCode={["Delete", "Backspace"]}
        onDelete={({ nodes: n, edges: e }) => {
          const ids = [...n.map((x) => x.id), ...e.map((x) => x.id)];
          if (ids.length > 0) edit({ op: "remove", ids });
        }}
      >
        <FitOnResize height={height} />
        <Palette unit={unit} onAdd={(kind, x, y) => edit({ op: "add", kind, x, y })} />
        <Background variant={BackgroundVariant.Dots} gap={unit} />
        {showMap ? <MiniMap pannable zoomable /> : null}
        <Controls showInteractive={false}>
          <ControlButton onClick={() => setShowMap((s) => !s)} title={showMap ? "Hide minimap" : "Show minimap"} aria-label="Toggle minimap">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M1 3l4.5-1.5 5 1.5L15 1.5v11.5l-4.5 1.5-5-1.5L1 14.5z M5.5 1.5v11.5 M10.5 3v11.5" fill="none" stroke="currentColor" strokeWidth="1.3" /></svg>
          </ControlButton>
        </Controls>
      </ReactFlow>
      {selected.size + selectedEdges.size > 0 ? (
        <SelectionBar
          count={selected.size + selectedEdges.size}
          onConnect={selected.size === 2 && selectedEdges.size === 0 ? () => {
            const [from, to] = [...selected];
            edit({ op: "connect", from, to });
          } : undefined}
          onDelete={() => edit({ op: "remove", ids: [...selected, ...selectedEdges] })}
          context={() => selectionContext(geometry, spec.nodes, (id) => (edgeNode(props.items ?? [], id)?.props.label as string | undefined) || undefined, selected, selectedEdges)}
          onClear={() => (setSelected(new Set()), setSelectedEdges(new Set()))}
        />
      ) : null}
      <div className="myair-board-report nodrag">
        <button type="button" onClick={() => setShowReport((s) => !s)}>
          {geometry.problems.length === 0 ? "Geometry: OK" : `Geometry: ${errors} errors, ${warnings} warnings`}
        </button>
        {showReport ? <pre>{JSON.stringify(geometry, null, 1)}</pre> : null}
      </div>
    </div>
  );
}

/** What the palette adds: a note or a Shape kind, with its default size in grid units (as the plugin writes it). */
const PALETTE: { kind: string; label: string; w: number; h: number }[] = [
  { kind: "note", label: "Note", w: 8, h: 4 },
  { kind: "rect", label: "Rect", w: 6, h: 3 },
  { kind: "rounded", label: "Rounded", w: 6, h: 3 },
  { kind: "ellipse", label: "Ellipse", w: 6, h: 3 },
  { kind: "diamond", label: "Diamond", w: 6, h: 3 },
  { kind: "cylinder", label: "Cylinder", w: 6, h: 3 },
];

/** Buttons that add a note or a shape in the middle of what the board shows; the plugin writes it into the document. */
function Palette({ unit, onAdd }: { unit: number; onAdd: (kind: string, x: number, y: number) => void }) {
  const { screenToFlowPosition } = useReactFlow();
  const add = (item: (typeof PALETTE)[number], button: HTMLElement) => {
    const board = button.closest(".react-flow")?.getBoundingClientRect();
    if (!board) return;
    const center = screenToFlowPosition({ x: board.left + board.width / 2, y: board.top + board.height / 2 });
    onAdd(item.kind, Math.round(center.x / unit - item.w / 2), Math.round(center.y / unit - item.h / 2));
  };
  return (
    <Panel position="center-left" className="myair-board-palette">
      {PALETTE.map((item) => (
        <button key={item.kind} type="button" title={`Add a ${item.label.toLowerCase()}`} onClick={(e) => add(item, e.currentTarget)}>{item.label}</button>
      ))}
    </Panel>
  );
}

/** [current] with React Flow's `select` changes applied. */
function applySelect(current: ReadonlySet<string>, changes: ReadonlyArray<{ type: string; id?: string; selected?: boolean }>): ReadonlySet<string> {
  const selects = changes.filter((c) => c.type === "select" && c.id != null);
  if (selects.length === 0) return current;
  const next = new Set(current);
  for (const c of selects) {
    if (c.selected) next.add(c.id!);
    else next.delete(c.id!);
  }
  return next;
}

/** The bar over a board while something is selected: a question for the manager about the selection, sent with it. */
function SelectionBar({ count, context, onClear, onConnect, onDelete }: {
  count: number; context: () => ReturnType<typeof selectionContext>; onClear: () => void; onConnect?: () => void; onDelete: () => void;
}) {
  const trigger = useTriggerAction();
  const [text, setText] = useState("");
  const send = () => {
    const ctx = context();
    const message = selectionMessage(ctx, text);
    trigger(message, undefined, { steps: [{ type: "continue_conversation", message, context: { selection: ctx } }] });
    setText("");
  };
  return (
    <div className="myair-board-selection nodrag nowheel nopan">
      <span>{count} selected</span>
      <input value={text} placeholder="Ask about the selection (Enter sends)" aria-label="Question about the selection"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") send();
          if (e.key === "Escape") onClear();
        }} />
      <button type="button" className="myair-board-selection-send" onClick={send}>Send to agent</button>
      {onConnect ? <button type="button" onClick={onConnect} title="An edge from the first selected to the second">Connect</button> : null}
      <button type="button" onClick={onDelete} title="Remove the selection from the board (Delete)">Delete</button>
      <button type="button" onClick={onClear} title="Clear the selection" aria-label="Clear the selection">×</button>
    </div>
  );
}

/** The Edge element node named [id], searched through the board's items and the shapes' children. */
function edgeNode(items: unknown[], id: string): ElementNode | undefined {
  for (const item of items) {
    const n = item as ElementNode | undefined;
    if (n?.type !== "element") continue;
    if (n.typeName === "Edge" && n.statementId === id) return n;
    const found = edgeNode((n.props.children as unknown[] | undefined) ?? [], id);
    if (found) return found;
  }
  return undefined;
}
