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
} from "@xyflow/react";
import { useTriggerAction, type ElementNode } from "@openuidev/react-lang";
import { DEFAULT_UNIT, facingSides, midpoint, movedBox, report, routeEdge, solve, type BoardReport, type ElementSpec, type Route } from "./layout";
import { boardSpec } from "./spec";
import { selectionContext, selectionMessage } from "./selection";

/** Whether the page shows the dark theme; main.tsx provides it from the plugin's frame. */
export const DarkContext = createContext(false);

/** Where the user dropped a board element, in grid units of its container's content area, with its box's own w and h. */
export type BoardMove = { element: string; x: number; y: number; w: number | null; h: number | null };

/** Receives every drop of a dragged element; main.tsx posts it to the plugin, which writes it into the document. */
export const BoardMoveContext = createContext<(move: BoardMove) => void>(() => {});

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

const NoteNode = memo(({ data }: NodeProps<Node<ElementData>>) => {
  const ref = useRef<HTMLDivElement>(null);
  useMeasured(ref, data);
  const color = (data.node.props.color as string | undefined) ?? "yellow";
  return (
    <div className={`myair-board-note myair-board-note-${color}`}>
      <Handles />
      <div ref={ref} className="myair-board-note-text">{String(data.node.props.text ?? "")}</div>
    </div>
  );
});

const FrameNode = memo(({ data }: NodeProps<Node<ElementData>>) => {
  const ref = useRef<HTMLDivElement>(null);
  useMeasured(ref, data);
  return (
    <div className="myair-board-frame">
      <Handles />
      <div ref={ref}>
        <div className="myair-board-frame-title myair-board-drag">{String(data.node.props.title ?? "")}</div>
        {/* nodrag/nowheel/nopan: the content keeps the mouse, so forms, tabs and scrolling work inside. */}
        <div className="myair-board-frame-body nodrag nowheel nopan">{data.renderNode(data.node.props.content)}</div>
      </div>
    </div>
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

const ShapeNode = memo(({ data, width, height }: NodeProps<Node<ElementData>>) => {
  const kind = String(data.node.props.kind ?? "rect");
  const container = data.spec.children.length > 0;
  const w = width ?? 0;
  const h = height ?? 0;
  return (
    <div className={`myair-board-shape myair-board-shape-${kind}${container ? " myair-board-container" : ""}`}>
      <Handles />
      <svg className="myair-board-shape-svg" width={w} height={h}>{shapePath(kind, w, h)}</svg>
      {data.spec.label ? <div className="myair-board-shape-label">{data.spec.label}</div> : null}
    </div>
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

export function BoardView({ props, renderNode, statementId }: { props: BoardProps; renderNode: (v: unknown) => ReactNode; statementId?: string }) {
  const dark = useContext(DarkContext);
  const onMove = useContext(BoardMoveContext);
  const onReport = useContext(BoardReportContext);
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
  const onNodesChange = useCallback((changes: NodeChange<Node<ElementData>>[]) => {
    const moves = changes.flatMap((c) => (c.type === "position" && c.position ? [[c.id, c.position] as const] : []));
    if (moves.length > 0) setDragged((d) => ({ ...d, ...Object.fromEntries(moves) }));
    setSelected((s) => applySelect(s, changes));
  }, []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => setSelectedEdges((s) => applySelect(s, changes)), []);
  // A changed layout places everything itself, the moves the user made included once the plugin wrote them. Keyed on
  // the specs' text, not their identity: the renderer may hand over new item objects for an unchanged document.
  const layoutKey = useMemo(() => JSON.stringify(spec.elements), [spec]);
  useEffect(() => setDragged({}), [layoutKey]);

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

  const nodes = useMemo(() => {
    const list: Node<ElementData>[] = [];
    const walk = (els: ElementSpec[], parent?: ElementSpec) => {
      for (const el of els) {
        const r = solved.local.get(el.id)!;
        const autoHeight = el.children.length === 0 && el.box.h == null && el.kind !== "shape";
        list.push({
          id: el.id,
          type: el.kind,
          position: dragged[el.id] ?? { x: r.x * unit, y: r.y * unit },
          width: r.w * unit,
          height: r.h * unit,
          parentId: parent?.id,
          extent: parent ? "parent" : undefined,
          // A row, column or grid places its children itself; only free placement can be dragged.
          draggable: !parent || (parent.layout ?? "free") === "free",
          dragHandle: el.kind === "frame" ? ".myair-board-drag" : undefined,
          selected: selected.has(el.id),
          data: { spec: el, node: spec.nodes.get(el.id)!, renderNode, measure, autoHeight },
        });
        walk(el.children, el);
      }
    };
    walk(spec.elements);
    return list;
  }, [spec, solved, unit, dragged, renderNode, measure, selected]);

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
      >
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
function SelectionBar({ count, context, onClear }: { count: number; context: () => ReturnType<typeof selectionContext>; onClear: () => void }) {
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
