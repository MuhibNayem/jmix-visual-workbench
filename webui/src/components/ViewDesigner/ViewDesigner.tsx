import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  AlignLeft, ArrowDown, ArrowUp, Calendar, CalendarClock, CheckSquare, ChevronDown,
  ChevronRight, CircleDollarSign, Columns, Copy, Database, Filter, Grid, Hash,
  Heading1, Heading2, Heading3, Image as ImageIcon, Layers, LayoutTemplate, List,
  ListTree, Loader2, MousePointerClick, MoreHorizontal, PanelLeft, Play, Plus, Rows,
  Search, Sigma, Star, Table as TableIcon, Tag, Text as TextIcon, Trash2, Type, X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type {
  ComponentModel, ComponentType, DataContainerModel, ViewModel, ViewType,
} from '../../types'

// ─── Constants ───────────────────────────────────────────────────────────────

const DND_MIME = 'application/x-jmix-component'

const CONTAINERS = new Set<ComponentType>([
  'vbox', 'hbox', 'formLayout', 'gridLayout', 'split', 'tabSheet',
])

const VIEW_TYPES: ViewType[] = ['listView', 'detailView', 'blankView', 'fragment']

interface PaletteItem {
  type: ComponentType
  label: string
  icon: LucideIcon
}

const PALETTE: { category: string; items: PaletteItem[] }[] = [
  {
    category: 'Layouts',
    items: [
      { type: 'vbox', label: 'VBox', icon: Rows },
      { type: 'hbox', label: 'HBox', icon: Columns },
      { type: 'formLayout', label: 'Form Layout', icon: LayoutTemplate },
      { type: 'gridLayout', label: 'Grid Layout', icon: Grid },
      { type: 'split', label: 'Split Pane', icon: PanelLeft },
      { type: 'tabSheet', label: 'Tab Sheet', icon: Layers },
    ],
  },
  {
    category: 'Fields',
    items: [
      { type: 'textField', label: 'Text Field', icon: Type },
      { type: 'textArea', label: 'Text Area', icon: AlignLeft },
      { type: 'integerField', label: 'Integer Field', icon: Hash },
      { type: 'numberField', label: 'Number Field', icon: Sigma },
      { type: 'bigDecimalField', label: 'BigDecimal', icon: CircleDollarSign },
      { type: 'checkbox', label: 'Checkbox', icon: CheckSquare },
      { type: 'datePicker', label: 'Date Picker', icon: Calendar },
      { type: 'dateTimePicker', label: 'DateTime', icon: CalendarClock },
      { type: 'comboBox', label: 'Combo Box', icon: ChevronDown },
      { type: 'entityComboBox', label: 'Entity Combo', icon: List },
      { type: 'entityPicker', label: 'Entity Picker', icon: Search },
    ],
  },
  {
    category: 'Data Display',
    items: [
      { type: 'dataGrid', label: 'Data Grid', icon: TableIcon },
      { type: 'treeDataGrid', label: 'Tree Grid', icon: ListTree },
    ],
  },
  {
    category: 'Filter',
    items: [
      { type: 'genericFilter', label: 'Generic Filter', icon: Filter },
      { type: 'simplePagination', label: 'Pagination', icon: MoreHorizontal },
    ],
  },
  {
    category: 'Navigation',
    items: [
      { type: 'button', label: 'Button', icon: MousePointerClick },
    ],
  },
  {
    category: 'Display',
    items: [
      { type: 'label', label: 'Label', icon: Tag },
      { type: 'span', label: 'Span', icon: TextIcon },
      { type: 'h1', label: 'Heading 1', icon: Heading1 },
      { type: 'h2', label: 'Heading 2', icon: Heading2 },
      { type: 'h3', label: 'Heading 3', icon: Heading3 },
      { type: 'image', label: 'Image', icon: ImageIcon },
      { type: 'icon', label: 'Icon', icon: Star },
    ],
  },
]

// ─── Styles ──────────────────────────────────────────────────────────────────

const btnPrimary =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-50'
const btnGhost =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300'
const btnIcon =
  'rounded p-1 text-gray-500 transition-colors hover:bg-surface-lighter hover:text-gray-200'
const inputSm = 'w-full py-1 text-xs'

// ─── Tree helpers (pure, immutable) ──────────────────────────────────────────

function findNode(node: ComponentModel, id: string): ComponentModel | null {
  if (node.id === id) return node
  for (const child of node.children) {
    const found = findNode(child, id)
    if (found) return found
  }
  return null
}

function findPath(node: ComponentModel, id: string, trail: ComponentModel[] = []): ComponentModel[] | null {
  const next = [...trail, node]
  if (node.id === id) return next
  for (const child of node.children) {
    const found = findPath(child, id, next)
    if (found) return found
  }
  return null
}

function updateNode(
  node: ComponentModel,
  id: string,
  fn: (n: ComponentModel) => ComponentModel,
): ComponentModel {
  if (node.id === id) return fn(node)
  return { ...node, children: node.children.map((c) => updateNode(c, id, fn)) }
}

function removeNode(node: ComponentModel, id: string): ComponentModel {
  return {
    ...node,
    children: node.children.filter((c) => c.id !== id).map((c) => removeNode(c, id)),
  }
}

function insertChild(node: ComponentModel, parentId: string, child: ComponentModel): ComponentModel {
  if (node.id === parentId) return { ...node, children: [...node.children, child] }
  return { ...node, children: node.children.map((c) => insertChild(c, parentId, child)) }
}

function moveNode(node: ComponentModel, id: string, dir: -1 | 1): ComponentModel {
  const idx = node.children.findIndex((c) => c.id === id)
  if (idx !== -1) {
    const target = idx + dir
    if (target < 0 || target >= node.children.length) return node
    const children = [...node.children]
    ;[children[idx], children[target]] = [children[target], children[idx]]
    return { ...node, children }
  }
  return { ...node, children: node.children.map((c) => moveNode(c, id, dir)) }
}

function cloneWithIds(node: ComponentModel, nextId: (type: string) => string): ComponentModel {
  return { ...node, id: nextId(node.type), children: node.children.map((c) => cloneWithIds(c, nextId)) }
}

function duplicateNode(node: ComponentModel, id: string, nextId: (type: string) => string): ComponentModel {
  const idx = node.children.findIndex((c) => c.id === id)
  if (idx !== -1) {
    const children = [...node.children]
    children.splice(idx + 1, 0, cloneWithIds(node.children[idx], nextId))
    return { ...node, children }
  }
  return { ...node, children: node.children.map((c) => duplicateNode(c, id, nextId)) }
}

function countNodes(node: ComponentModel): number {
  return 1 + node.children.reduce((sum, c) => sum + countNodes(c), 0)
}

// ─── Component factory ───────────────────────────────────────────────────────

function defaultPropsFor(type: ComponentType): Record<string, any> {
  switch (type) {
    case 'button': return { caption: 'Button' }
    case 'label': return { caption: 'Label' }
    case 'span': return { caption: 'Text span' }
    case 'h1': return { caption: 'Heading 1' }
    case 'h2': return { caption: 'Heading 2' }
    case 'h3': return { caption: 'Heading 3' }
    case 'image': return { src: '' }
    case 'icon': return { icon: 'star' }
    default: return {}
  }
}

function createComponent(type: ComponentType, nextId: (type: string) => string): ComponentModel {
  return {
    id: nextId(type),
    type,
    properties: defaultPropsFor(type),
    children: [],
    actions: [],
    columns: [],
    cssClasses: [],
    visible: true,
    enabled: true,
    ...(CONTAINERS.has(type) ? { width: '100%' } : {}),
  }
}

function makeRoot(): ComponentModel {
  return {
    id: 'root', type: 'vbox', properties: {}, children: [], actions: [],
    columns: [], cssClasses: [], visible: true, enabled: true, width: '100%',
  }
}

// ─── Leaf preview (WYSIWYG mock rendering) ───────────────────────────────────

function LeafPreview({ node }: { node: ComponentModel }) {
  const caption = String(node.properties.caption ?? node.id)

  switch (node.type) {
    case 'textField':
    case 'integerField':
    case 'numberField':
    case 'bigDecimalField':
      return (
        <div className="flex w-full max-w-[220px] items-center justify-between rounded border border-surface-border bg-surface px-2 py-1">
          <span className="truncate text-[10px] text-gray-500">{node.propertyBinding || caption}</span>
          <span className="ml-2 shrink-0 text-[8px] uppercase tracking-wider text-gray-600">{node.type.replace('Field', '')}</span>
        </div>
      )
    case 'textArea':
      return (
        <div className="w-full max-w-[220px] space-y-1 rounded border border-surface-border bg-surface px-2 py-1.5">
          <div className="h-1 w-4/5 rounded bg-surface-lighter" />
          <div className="h-1 w-full rounded bg-surface-lighter" />
          <div className="h-1 w-2/3 rounded bg-surface-lighter" />
        </div>
      )
    case 'checkbox':
      return (
        <div className="flex items-center gap-1.5">
          <CheckSquare size={13} className="text-jmix-400" />
          <span className="text-[10px] text-gray-400">{node.propertyBinding || caption}</span>
        </div>
      )
    case 'datePicker':
    case 'dateTimePicker':
      return (
        <div className="flex w-full max-w-[180px] items-center justify-between rounded border border-surface-border bg-surface px-2 py-1">
          <span className="text-[10px] text-gray-500">dd/mm/yyyy</span>
          {node.type === 'datePicker'
            ? <Calendar size={11} className="text-gray-500" />
            : <CalendarClock size={11} className="text-gray-500" />}
        </div>
      )
    case 'comboBox':
    case 'entityComboBox':
      return (
        <div className="flex w-full max-w-[200px] items-center justify-between rounded border border-surface-border bg-surface px-2 py-1">
          <span className="truncate text-[10px] text-gray-500">{node.propertyBinding || 'Select…'}</span>
          <ChevronDown size={11} className="ml-2 shrink-0 text-gray-500" />
        </div>
      )
    case 'entityPicker':
      return (
        <div className="flex w-full max-w-[200px] items-center justify-between rounded border border-surface-border bg-surface px-2 py-1">
          <span className="truncate text-[10px] text-gray-500">{node.propertyBinding || 'Lookup…'}</span>
          <Search size={11} className="ml-2 shrink-0 text-jmix-400" />
        </div>
      )
    case 'dataGrid':
    case 'treeDataGrid':
      return (
        <div className="w-full overflow-hidden rounded border border-surface-border bg-surface">
          <div className="flex gap-2 border-b border-surface-border bg-surface-light px-2 py-1">
            {['id', 'name', 'status'].map((h) => (
              <span key={h} className="flex-1 text-[9px] font-semibold uppercase tracking-wider text-gray-500">{h}</span>
            ))}
          </div>
          {[0, 1, 2].map((row) => (
            <div key={row} className="flex gap-2 border-b border-surface-border/40 px-2 py-1.5 last:border-0">
              {[70, 90, 50].map((w, i) => (
                <div key={i} className="flex-1">
                  <div className="h-1 rounded bg-surface-lighter" style={{ width: `${w - row * 8}%` }} />
                </div>
              ))}
            </div>
          ))}
        </div>
      )
    case 'genericFilter':
      return (
        <div className="flex w-full items-center gap-1.5 rounded border border-surface-border bg-surface px-2 py-1">
          <Filter size={11} className="text-jmix-400" />
          <span className="text-[10px] text-gray-500">Add condition…</span>
          <span className="ml-auto rounded bg-jmix-500/20 px-1.5 py-px text-[9px] text-jmix-300">Search</span>
        </div>
      )
    case 'simplePagination':
      return (
        <div className="flex items-center gap-1">
          {['«', '1', '2', '3', '»'].map((p, i) => (
            <span
              key={p}
              className={`rounded border px-1.5 py-0.5 text-[9px] ${
                i === 1
                  ? 'border-jmix-500 bg-jmix-500/20 text-jmix-300'
                  : 'border-surface-border bg-surface text-gray-500'
              }`}
            >
              {p}
            </span>
          ))}
        </div>
      )
    case 'button':
      return (
        <span className="inline-flex items-center rounded bg-jmix-500/90 px-2.5 py-1 text-[10px] font-medium text-white shadow-sm">
          {caption}
        </span>
      )
    case 'label':
      return <span className="text-[11px] text-gray-300">{caption}</span>
    case 'span':
      return <span className="text-[10px] text-gray-400">{caption}</span>
    case 'h1':
      return <span className="text-lg font-bold text-gray-100">{caption}</span>
    case 'h2':
      return <span className="text-sm font-semibold text-gray-200">{caption}</span>
    case 'h3':
      return <span className="text-xs font-semibold text-gray-300">{caption}</span>
    case 'image':
      return (
        <div className="flex h-14 w-24 items-center justify-center rounded border border-dashed border-surface-border bg-surface">
          <ImageIcon size={16} className="text-gray-600" />
        </div>
      )
    case 'icon':
      return (
        <div className="flex h-7 w-7 items-center justify-center rounded border border-surface-border bg-surface">
          <Star size={13} className="text-jmix-400" />
        </div>
      )
    default:
      return (
        <div className="rounded border border-dashed border-surface-border px-2 py-1 text-[10px] text-gray-500">
          {node.type}
        </div>
      )
  }
}

// ─── Canvas node renderer ────────────────────────────────────────────────────

interface NodeViewProps {
  node: ComponentModel
  depth: number
  selectedId: string | null
  dropTargetId: string | null
  onSelect: (id: string) => void
  onDropNew: (type: ComponentType, parentId: string) => void
  onHoverDrop: (id: string | null) => void
}

function NodeView({ node, depth, selectedId, dropTargetId, onSelect, onDropNew, onHoverDrop }: NodeViewProps) {
  const isContainer = CONTAINERS.has(node.type)
  const isSelected = selectedId === node.id
  const isDropTarget = dropTargetId === node.id

  const containerLayout: Partial<Record<ComponentType, string>> = {
    vbox: 'flex flex-col gap-2',
    hbox: 'flex flex-row flex-wrap items-center gap-2',
    formLayout: 'grid grid-cols-2 gap-x-4 gap-y-2',
    gridLayout: 'grid grid-cols-3 gap-2',
    split: 'grid grid-cols-2 gap-3',
    tabSheet: 'flex flex-col gap-2',
  }

  return (
    <div
      onClick={(e) => { e.stopPropagation(); onSelect(node.id) }}
      onDragOver={isContainer ? (e) => {
        e.preventDefault()
        e.stopPropagation()
        e.dataTransfer.dropEffect = 'copy'
        onHoverDrop(node.id)
      } : undefined}
      onDrop={isContainer ? (e) => {
        e.preventDefault()
        e.stopPropagation()
        const type = e.dataTransfer.getData(DND_MIME) as ComponentType
        if (type) onDropNew(type, node.id)
        onHoverDrop(null)
      } : undefined}
      onDragLeave={isContainer ? () => onHoverDrop(null) : undefined}
      className={[
        'group/node relative rounded-sm transition-all duration-150',
        isContainer
          ? `border p-2.5 pt-3.5 ${containerLayout[node.type] ?? 'flex flex-col gap-2'}`
          : 'border border-transparent p-1',
        isSelected
          ? 'border-jmix-500 ring-1 ring-jmix-500/50'
          : isDropTarget
            ? 'border-jmix-400 bg-jmix-500/10'
            : isContainer
              ? 'border-surface-border/80 hover:border-gray-500'
              : 'hover:border-surface-border',
      ].join(' ')}
    >
      {/* type chip */}
      <span
        className={`pointer-events-none absolute -top-1.5 left-1.5 z-10 rounded-sm border px-1 text-[8px] leading-3 tracking-wide transition-colors ${
          isSelected
            ? 'border-jmix-500 bg-jmix-500/20 text-jmix-300'
            : 'border-surface-border bg-surface-light text-gray-500 group-hover/node:text-gray-400'
        }`}
      >
        {node.type}{isSelected ? ` · ${node.id}` : ''}
      </span>

      {isContainer ? (
        <>
          {node.type === 'tabSheet' && (
            <div className="flex gap-px self-start rounded-t border border-surface-border bg-surface-light text-[9px]">
              {node.children.slice(0, 3).map((c, i) => (
                <span key={c.id} className={`px-2 py-0.5 ${i === 0 ? 'bg-surface-lighter text-gray-300' : 'text-gray-500'}`}>
                  {c.properties.caption ?? c.id}
                </span>
              ))}
              {node.children.length === 0 && <span className="px-2 py-0.5 text-gray-500">Tab 1</span>}
            </div>
          )}
          {node.children.map((child) => (
            <NodeView
              key={child.id}
              node={child}
              depth={depth + 1}
              selectedId={selectedId}
              dropTargetId={dropTargetId}
              onSelect={onSelect}
              onDropNew={onDropNew}
              onHoverDrop={onHoverDrop}
            />
          ))}
          {node.children.length === 0 && (
            <div className="flex h-10 items-center justify-center rounded-sm border border-dashed border-surface-border/70 text-[9px] text-gray-600">
              {isDropTarget ? 'Release to drop' : 'Drop components here'}
            </div>
          )}
        </>
      ) : (
        <LeafPreview node={node} />
      )}
    </div>
  )
}

// ─── Small form primitives ───────────────────────────────────────────────────

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">{label}</span>
      {children}
    </label>
  )
}

function SectionHeader({ icon: Icon, title, count, action }: {
  icon: LucideIcon
  title: string
  count?: number
  action?: ReactNode
}) {
  return (
    <div className="flex items-center justify-between border-b border-surface-border px-3 py-2">
      <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
        <Icon size={12} className="text-jmix-400" />
        {title}
        {count !== undefined && (
          <span className="rounded-full bg-surface-lighter px-1.5 py-px text-[9px] font-medium normal-case tracking-normal text-gray-400">
            {count}
          </span>
        )}
      </div>
      {action}
    </div>
  )
}

// ─── Main component ──────────────────────────────────────────────────────────

export default function ViewDesigner() {
  const { projectConfig, addToast, isGenerating, setIsGenerating, setLastResult } = useStore()

  const [view, setView] = useState<ViewModel>(() => ({
    viewName: 'NewView',
    packageName: `${projectConfig?.basePackage ?? 'com.example.app'}.view`,
    viewType: 'blankView',
    entityClass: '',
    layout: makeRoot(),
    dataContainers: [],
    facets: [],
    actions: [],
    messages: {},
  }))
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [dropTargetId, setDropTargetId] = useState<string | null>(null)
  const [cssText, setCssText] = useState('')

  const uid = useRef(1)
  const nextId = useCallback((type: string) => `${type}${uid.current++}`, [])

  const layout = view.layout
  const selected = selectedId ? findNode(layout, selectedId) : null
  const selectedPath = selectedId ? findPath(layout, selectedId) : null
  const componentCount = countNodes(layout) - 1

  // Keep the css-class draft in sync when selection changes
  useEffect(() => {
    const node = selectedId ? findNode(layout, selectedId) : null
    setCssText(node?.cssClasses.join(' ') ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  // Delete key removes the selected component (ignored while typing)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Delete') return
      const t = e.target as HTMLElement
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName) || t.isContentEditable) return
      if (selectedId && selectedId !== 'root') handleRemove(selectedId)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, layout])

  const setLayout = (fn: (l: ComponentModel) => ComponentModel) =>
    setView((v) => ({ ...v, layout: fn(v.layout) }))

  // Nearest container ancestor of the selection (falls back to root)
  const getAddTargetId = (): string => {
    if (!selectedPath) return 'root'
    for (let i = selectedPath.length - 1; i >= 0; i--) {
      if (CONTAINERS.has(selectedPath[i].type)) return selectedPath[i].id
    }
    return 'root'
  }

  const addComponent = (type: ComponentType, parentId: string) => {
    const comp = createComponent(type, nextId)
    setLayout((l) => insertChild(l, parentId, comp))
    setSelectedId(comp.id)
  }

  const handleRemove = (id: string) => {
    const path = findPath(layout, id)
    const parentId = path && path.length > 1 ? path[path.length - 2].id : null
    setLayout((l) => removeNode(l, id))
    setSelectedId(parentId && parentId !== id ? parentId : null)
  }

  const handleDuplicate = (id: string) => {
    setLayout((l) => duplicateNode(l, id, nextId))
  }

  const updateSelected = (patch: Partial<ComponentModel>) => {
    if (!selectedId) return
    setLayout((l) => updateNode(l, selectedId, (n) => ({ ...n, ...patch })))
  }

  // ── Data containers ────────────────────────────────────────────────────────

  const addContainer = () => {
    setView((v) => {
      const n = v.dataContainers.length + 1
      const container: DataContainerModel = {
        id: `container${n}`,
        type: 'collection',
        entityClass: v.entityClass ?? '',
        fetchPlan: { name: '_base', properties: [] },
        loader: { id: `container${n}Loader`, query: '', cacheable: false },
      }
      return { ...v, dataContainers: [...v.dataContainers, container] }
    })
  }

  const updateContainer = (index: number, patch: Partial<DataContainerModel>) => {
    setView((v) => ({
      ...v,
      dataContainers: v.dataContainers.map((c, i) => (i === index ? { ...c, ...patch } : c)),
    }))
  }

  const removeContainer = (index: number) => {
    setView((v) => ({ ...v, dataContainers: v.dataContainers.filter((_, i) => i !== index) }))
  }

  // ── Generate ───────────────────────────────────────────────────────────────

  const handleGenerate = async () => {
    if (!view.viewName.trim()) {
      addToast('View name is required', 'error')
      return
    }
    setIsGenerating(true)
    try {
      const result = await bridge.generateView(view)
      setLastResult(result)
      if (result.success) {
        addToast(`View "${view.viewName}" generated — ${result.filesWritten.length} file(s) written`, 'success')
      } else {
        addToast(result.errors?.[0] ?? 'View generation failed', 'error')
      }
    } catch {
      addToast('View generation failed — bridge unavailable', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="flex h-full flex-col bg-surface [color-scheme:dark]">
      {/* Top bar */}
      <header className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-surface-border bg-surface-light/60 px-3 py-2">
        <div className="flex items-center gap-2">
          <LayoutTemplate size={15} className="text-jmix-400" />
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-300">View Designer</h2>
        </div>

        <label className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Name
          <input
            value={view.viewName}
            onChange={(e) => setView((v) => ({ ...v, viewName: e.target.value }))}
            className="w-36 py-1 text-xs normal-case tracking-normal"
            placeholder="CustomerDetailView"
          />
        </label>

        <label className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Package
          <input
            value={view.packageName}
            onChange={(e) => setView((v) => ({ ...v, packageName: e.target.value }))}
            className="w-48 py-1 font-mono text-xs tracking-normal"
          />
        </label>

        <label className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Type
          <select
            value={view.viewType}
            onChange={(e) => setView((v) => ({ ...v, viewType: e.target.value as ViewType }))}
            className="py-1 text-xs"
          >
            {VIEW_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>

        <label className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Entity
          <input
            value={view.entityClass ?? ''}
            onChange={(e) => setView((v) => ({ ...v, entityClass: e.target.value }))}
            className="w-44 py-1 font-mono text-xs tracking-normal"
            placeholder="com.example.entity.Order"
          />
        </label>

        <div className="ml-auto flex items-center gap-2">
          <span className="rounded-full border border-surface-border bg-surface-lighter px-2 py-0.5 text-[10px] text-gray-400">
            {componentCount} component{componentCount === 1 ? '' : 's'}
          </span>
          <button onClick={handleGenerate} disabled={isGenerating} className={btnPrimary}>
            {isGenerating ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            Generate View
          </button>
        </div>
      </header>

      {/* Three-pane workspace */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: palette + data containers */}
        <aside className="flex w-56 shrink-0 flex-col border-r border-surface-border bg-surface-light/40">
          <div className="flex-1 overflow-y-auto">
            {PALETTE.map((group) => (
              <div key={group.category} className="border-b border-surface-border/60">
                <div className="flex items-center gap-1 px-3 pb-1 pt-2.5 text-[9px] font-semibold uppercase tracking-widest text-gray-500">
                  <ChevronRight size={10} className="text-jmix-400" />
                  {group.category}
                </div>
                <div className="grid grid-cols-2 gap-0.5 px-1.5 pb-2">
                  {group.items.map((item) => (
                    <button
                      key={item.type}
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.setData(DND_MIME, item.type)
                        e.dataTransfer.effectAllowed = 'copy'
                      }}
                      onClick={() => addComponent(item.type, getAddTargetId())}
                      title={`${item.label} — click to add to selected container, or drag onto the canvas`}
                      className="group flex cursor-grab items-center gap-1.5 rounded border border-transparent px-1.5 py-1.5 text-left text-[10px] text-gray-400 transition-all hover:border-surface-border hover:bg-surface-lighter hover:text-gray-200 active:scale-[0.97] active:cursor-grabbing"
                    >
                      <item.icon size={12} className="shrink-0 text-gray-500 transition-colors group-hover:text-jmix-400" />
                      <span className="truncate">{item.label}</span>
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>

          {/* Data containers */}
          <div className="flex max-h-[42%] shrink-0 flex-col border-t border-surface-border">
            <SectionHeader
              icon={Database}
              title="Data Containers"
              count={view.dataContainers.length}
              action={
                <button onClick={addContainer} className={btnIcon} title="Add data container" aria-label="Add data container">
                  <Plus size={13} />
                </button>
              }
            />
            <div className="flex-1 space-y-2 overflow-y-auto p-2">
              {view.dataContainers.length === 0 && (
                <p className="px-1 py-2 text-[10px] leading-relaxed text-gray-600">
                  No containers yet. Add an instance or collection container to bind fields to entity properties.
                </p>
              )}
              {view.dataContainers.map((c, i) => (
                <div key={`${c.id}-${i}`} className="space-y-1.5 rounded border border-surface-border bg-surface p-2 transition-colors hover:border-gray-500">
                  <div className="flex items-center gap-1.5">
                    <select
                      value={c.type}
                      onChange={(e) => updateContainer(i, { type: e.target.value as DataContainerModel['type'] })}
                      className="w-[86px] shrink-0 py-0.5 text-[10px]"
                    >
                      <option value="instance">instance</option>
                      <option value="collection">collection</option>
                    </select>
                    <input
                      value={c.id}
                      onChange={(e) => updateContainer(i, { id: e.target.value })}
                      className="min-w-0 flex-1 py-0.5 text-[11px]"
                      aria-label="Container id"
                    />
                    <button
                      onClick={() => removeContainer(i)}
                      className={btnIcon}
                      title="Remove container"
                      aria-label="Remove container"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                  <input
                    value={c.entityClass}
                    onChange={(e) => updateContainer(i, { entityClass: e.target.value })}
                    className="w-full py-0.5 font-mono text-[10px]"
                    placeholder="Entity class"
                  />
                  <input
                    value={c.fetchPlan?.name ?? ''}
                    onChange={(e) => updateContainer(i, { fetchPlan: { name: e.target.value, properties: c.fetchPlan?.properties ?? [] } })}
                    className="w-full py-0.5 text-[10px]"
                    placeholder="Fetch plan (_base)"
                  />
                  <input
                    value={c.loader?.query ?? ''}
                    onChange={(e) => updateContainer(i, {
                      loader: { id: c.loader?.id ?? `${c.id}Loader`, query: e.target.value, cacheable: c.loader?.cacheable ?? false },
                    })}
                    className="w-full py-0.5 font-mono text-[10px]"
                    placeholder="Loader query (JPQL)"
                  />
                </div>
              ))}
            </div>
          </div>
        </aside>

        {/* Center: canvas */}
        <section className="flex min-w-0 flex-1 flex-col">
          <div
            className="flex-1 overflow-auto p-6"
            style={{
              backgroundImage: 'radial-gradient(circle, rgba(69,71,90,0.45) 1px, transparent 1px)',
              backgroundSize: '18px 18px',
            }}
            onClick={() => setSelectedId(null)}
          >
            <div className="mx-auto min-h-full max-w-3xl rounded-md border border-surface-border bg-surface-light/30 p-4 shadow-2xl shadow-black/30">
              <NodeView
                node={layout}
                depth={0}
                selectedId={selectedId}
                dropTargetId={dropTargetId}
                onSelect={setSelectedId}
                onDropNew={addComponent}
                onHoverDrop={setDropTargetId}
              />
              {layout.children.length === 0 && (
                <p className="pointer-events-none -mt-8 pb-6 text-center text-[11px] text-gray-600">
                  Drag components from the palette, or click a palette item to add it to the root layout.
                </p>
              )}
            </div>
          </div>

          {/* Status strip */}
          <footer className="flex items-center gap-4 border-t border-surface-border bg-surface-light/60 px-3 py-1 text-[10px] text-gray-500">
            <span>{componentCount} components</span>
            <span>{view.dataContainers.length} data containers</span>
            <span className="truncate">
              {selectedPath
                ? selectedPath.map((n) => n.id).join(' › ')
                : 'Nothing selected'}
            </span>
            <span className="ml-auto hidden text-gray-600 sm:block">Del — remove selected</span>
          </footer>
        </section>

        {/* Right: properties inspector */}
        <aside className="flex w-64 shrink-0 flex-col overflow-y-auto border-l border-surface-border bg-surface-light/40">
          <SectionHeader icon={Tag} title="Properties" />

          {!selected ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-center">
              <MousePointerClick size={22} className="text-gray-600" />
              <p className="text-[11px] leading-relaxed text-gray-500">
                Select a component on the canvas to edit its properties.
              </p>
            </div>
          ) : (
            <div className="space-y-3 p-3">
              {/* Selection identity */}
              <div className="flex items-center justify-between rounded border border-surface-border bg-surface px-2 py-1.5">
                <span className="font-mono text-[11px] text-jmix-300">{selected.type}</span>
                {selected.id !== 'root' && (
                  <div className="flex items-center gap-0.5">
                    <button
                      onClick={() => setLayout((l) => moveNode(l, selected.id, -1))}
                      className={btnIcon} title="Move up" aria-label="Move up"
                    >
                      <ArrowUp size={12} />
                    </button>
                    <button
                      onClick={() => setLayout((l) => moveNode(l, selected.id, 1))}
                      className={btnIcon} title="Move down" aria-label="Move down"
                    >
                      <ArrowDown size={12} />
                    </button>
                    <button
                      onClick={() => handleDuplicate(selected.id)}
                      className={btnIcon} title="Duplicate" aria-label="Duplicate"
                    >
                      <Copy size={12} />
                    </button>
                    <button
                      onClick={() => handleRemove(selected.id)}
                      className="rounded p-1 text-gray-500 transition-colors hover:bg-red-500/15 hover:text-red-400"
                      title="Delete" aria-label="Delete"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                )}
              </div>

              <Field label="Id">
                <input
                  value={selected.id}
                  disabled={selected.id === 'root'}
                  onChange={(e) => updateSelected({ id: e.target.value.replace(/\s+/g, '') })}
                  className={`${inputSm} font-mono disabled:opacity-50`}
                />
              </Field>

              <div className="grid grid-cols-2 gap-2">
                <Field label="Width">
                  <input
                    value={selected.width ?? ''}
                    onChange={(e) => updateSelected({ width: e.target.value || undefined })}
                    className={inputSm}
                    placeholder="100%"
                  />
                </Field>
                <Field label="Height">
                  <input
                    value={selected.height ?? ''}
                    onChange={(e) => updateSelected({ height: e.target.value || undefined })}
                    className={inputSm}
                    placeholder="auto"
                  />
                </Field>
              </div>

              <Field label="Data Binding">
                <select
                  value={selected.dataBinding ?? ''}
                  onChange={(e) => updateSelected({ dataBinding: e.target.value || undefined })}
                  className={inputSm}
                >
                  <option value="">— none —</option>
                  {view.dataContainers.map((c) => (
                    <option key={c.id} value={c.id}>{c.id} ({c.type})</option>
                  ))}
                </select>
              </Field>

              <Field label="Property Binding">
                <input
                  value={selected.propertyBinding ?? ''}
                  onChange={(e) => updateSelected({ propertyBinding: e.target.value || undefined })}
                  className={`${inputSm} font-mono`}
                  placeholder="entity.name"
                />
              </Field>

              <Field label="CSS Classes">
                <input
                  value={cssText}
                  onChange={(e) => {
                    setCssText(e.target.value)
                    updateSelected({ cssClasses: e.target.value.split(/[\s,]+/).filter(Boolean) })
                  }}
                  className={`${inputSm} font-mono`}
                  placeholder="gap-2 p-4"
                />
              </Field>

              <div className="flex items-center gap-4 pt-1">
                <label className="flex cursor-pointer items-center gap-1.5 text-[11px] text-gray-400">
                  <input
                    type="checkbox"
                    checked={selected.visible}
                    onChange={(e) => updateSelected({ visible: e.target.checked })}
                    className="h-3.5 w-3.5 accent-jmix-500"
                  />
                  Visible
                </label>
                <label className="flex cursor-pointer items-center gap-1.5 text-[11px] text-gray-400">
                  <input
                    type="checkbox"
                    checked={selected.enabled}
                    onChange={(e) => updateSelected({ enabled: e.target.checked })}
                    className="h-3.5 w-3.5 accent-jmix-500"
                  />
                  Enabled
                </label>
              </div>

              {/* Custom properties */}
              <div className="border-t border-surface-border/70 pt-3">
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-[10px] font-medium uppercase tracking-wider text-gray-500">
                    Custom Properties
                  </span>
                  <button
                    onClick={() => {
                      const key = `customProp${Object.keys(selected.properties).length + 1}`
                      updateSelected({ properties: { ...selected.properties, [key]: '' } })
                    }}
                    className={btnIcon}
                    title="Add property"
                    aria-label="Add custom property"
                  >
                    <Plus size={12} />
                  </button>
                </div>
                {Object.keys(selected.properties).length === 0 && (
                  <p className="text-[10px] text-gray-600">No custom properties.</p>
                )}
                <div className="space-y-1.5">
                  {Object.entries(selected.properties).map(([key, value]) => (
                    <div key={key} className="flex items-center gap-1.5">
                      <input
                        value={key}
                        onChange={(e) => {
                          const props = { ...selected.properties }
                          delete props[key]
                          props[e.target.value] = value
                          updateSelected({ properties: props })
                        }}
                        className="w-24 shrink-0 py-0.5 font-mono text-[10px]"
                        aria-label="Property name"
                      />
                      <input
                        value={String(value ?? '')}
                        onChange={(e) => updateSelected({ properties: { ...selected.properties, [key]: e.target.value } })}
                        className="min-w-0 flex-1 py-0.5 text-[10px]"
                        aria-label="Property value"
                      />
                      <button
                        onClick={() => {
                          const props = { ...selected.properties }
                          delete props[key]
                          updateSelected({ properties: props })
                        }}
                        className={btnIcon}
                        title="Remove property"
                        aria-label="Remove property"
                      >
                        <X size={11} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
