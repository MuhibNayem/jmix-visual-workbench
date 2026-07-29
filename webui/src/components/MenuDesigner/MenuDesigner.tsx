import { useEffect, useMemo, useRef, useState } from 'react'
import type { DragEvent, ReactNode } from 'react'
import {
  ArrowDown, ArrowUp, BarChart3, Calendar, ChevronDown, ChevronRight, CornerDownRight,
  CornerUpLeft, Database, FilePlus2, FileText, Folder, FolderPlus, GripVertical,
  HelpCircle, Home, ListTree, Loader2, Mail, Menu as MenuIcon, Minus, Play,
  Settings, Shield, Trash2, Users, Workflow,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type {
  GenerationResult,
  MenuSourceSnapshot,
  MenuSpringBeanSnapshot,
  MenuWorkspaceResponse,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

type MenuNodeKind = 'menu' | 'view' | 'bean' | 'separator'

interface MenuNode {
  id: string
  kind: MenuNodeKind
  caption: string
  titleExpression: string
  description: string
  icon: string
  classNames: string
  opened: boolean
  viewId: string
  shortcut: string
  openedBy: string
  bean: string
  beanMethod: string
  order: number
  syntheticId: boolean
  properties: Record<string, string>
  routeParameters: Record<string, string>
  urlQueryParameters: Record<string, string>
  preservedAttributes: Record<string, string>
  children: MenuNode[]
}

interface VisibleRow {
  node: MenuNode
  depth: number
  parentId: string | null
}

const ICON_MAP: Record<string, LucideIcon> = {
  home: Home,
  users: Users,
  settings: Settings,
  database: Database,
  folder: Folder,
  file: FileText,
  chart: BarChart3,
  shield: Shield,
  mail: Mail,
  calendar: Calendar,
  help: HelpCircle,
  workflow: Workflow,
}

const iconFor = (name: string): LucideIcon => ICON_MAP[name.toLowerCase()] ?? FileText

const defaultNode = (
  id: string,
  kind: MenuNodeKind,
  caption: string,
  order: number,
  children: MenuNode[] = [],
): MenuNode => ({
  id,
  kind,
  caption,
  titleExpression: '',
  description: '',
  icon: kind === 'menu' ? 'folder' : 'file',
  classNames: '',
  opened: kind === 'menu',
  viewId: '',
  shortcut: '',
  openedBy: '',
  bean: '',
  beanMethod: '',
  order,
  syntheticId: false,
  properties: {},
  routeParameters: {},
  urlQueryParameters: {},
  preservedAttributes: {},
  children,
})

const STARTER_MENU: MenuNode[] = [
  {
    ...defaultNode('application', 'menu', 'Application', 10),
    children: [
      {
        ...defaultNode('operations', 'menu', 'Operations', 10),
        children: [
          { ...defaultNode('customers', 'view', 'Customers', 10), icon: 'users', viewId: 'Customer.list' },
          { ...defaultNode('workflowQueue', 'view', 'Workflow Queue', 20), icon: 'workflow', viewId: 'WorkflowQueue.list' },
        ],
      },
      {
        ...defaultNode('reporting', 'menu', 'Reporting', 20),
        children: [
          { ...defaultNode('portfolioReport', 'view', 'Portfolio Report', 10), icon: 'chart', viewId: 'PortfolioReport.view' },
        ],
      },
    ],
  },
]

function findMenuNode(nodes: MenuNode[], id: string): MenuNode | null {
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findMenuNode(node.children, id)
    if (found) return found
  }
  return null
}

function findParentId(nodes: MenuNode[], id: string, parentId: string | null = null): string | null {
  for (const node of nodes) {
    if (node.id === id) return parentId
    const found = findParentId(node.children, id, node.id)
    if (found !== null) return found
  }
  return null
}

function updateMenuNode(nodes: MenuNode[], id: string, patch: Partial<MenuNode>): MenuNode[] {
  return nodes.map((node) =>
    node.id === id
      ? { ...node, ...patch }
      : { ...node, children: updateMenuNode(node.children, id, patch) },
  )
}

function removeMenuNode(nodes: MenuNode[], id: string): MenuNode[] {
  return nodes
    .filter((node) => node.id !== id)
    .map((node) => ({ ...node, children: removeMenuNode(node.children, id) }))
}

function insertMenuNode(
  nodes: MenuNode[],
  parentId: string | null,
  child: MenuNode,
  index?: number,
): MenuNode[] {
  if (parentId === null) {
    const next = [...nodes]
    next.splice(index ?? next.length, 0, child)
    return next
  }
  return nodes.map((node) => {
    if (node.id === parentId) {
      const children = [...node.children]
      children.splice(index ?? children.length, 0, child)
      return { ...node, children }
    }
    return { ...node, children: insertMenuNode(node.children, parentId, child, index) }
  })
}

function childIndex(nodes: MenuNode[], parentId: string | null, id: string): number {
  const siblings = parentId === null ? nodes : findMenuNode(nodes, parentId)?.children ?? []
  return siblings.findIndex((node) => node.id === id)
}

function isDescendant(nodes: MenuNode[], ancestorId: string, candidateId: string): boolean {
  const ancestor = findMenuNode(nodes, ancestorId)
  return ancestor ? findMenuNode(ancestor.children, candidateId) !== null : false
}

function reparentMenuNode(
  nodes: MenuNode[],
  id: string,
  parentId: string | null,
  index?: number,
): MenuNode[] {
  const moving = findMenuNode(nodes, id)
  if (!moving || id === parentId || (parentId && isDescendant(nodes, id, parentId))) return nodes
  return insertMenuNode(removeMenuNode(nodes, id), parentId, moving, index)
}

function moveMenuNode(nodes: MenuNode[], id: string, direction: -1 | 1): MenuNode[] {
  const parentId = findParentId(nodes, id)
  const index = childIndex(nodes, parentId, id)
  if (index < 0) return nodes
  const siblings = parentId === null ? nodes : findMenuNode(nodes, parentId)?.children ?? []
  const target = index + direction
  if (target < 0 || target >= siblings.length) return nodes
  return reparentMenuNode(nodes, id, parentId, target)
}

function countMenuNodes(nodes: MenuNode[]): number {
  return nodes.reduce((sum, node) => sum + 1 + countMenuNodes(node.children), 0)
}

function maximumDepth(nodes: MenuNode[], depth = 1): number {
  return nodes.reduce(
    (max, node) => Math.max(max, node.children.length ? maximumDepth(node.children, depth + 1) : depth),
    nodes.length ? depth : 0,
  )
}

function visibleMenuRows(
  nodes: MenuNode[],
  collapsed: Set<string>,
  depth = 0,
  parentId: string | null = null,
): VisibleRow[] {
  return nodes.flatMap((node) => [
    { node, depth, parentId },
    ...(node.kind === 'menu' && !collapsed.has(node.id)
      ? visibleMenuRows(node.children, collapsed, depth + 1, node.id)
      : []),
  ])
}

function allMenuContainers(nodes: MenuNode[], path: string[] = []): { id: string; label: string }[] {
  return nodes.flatMap((node) => {
    const nextPath = [...path, node.caption || node.id]
    return node.kind === 'menu'
      ? [{ id: node.id, label: nextPath.join(' / ') }, ...allMenuContainers(node.children, nextPath)]
      : []
  })
}

function flattenForGeneration(nodes: MenuNode[], parentId: string | null = null): Record<string, unknown>[] {
  return nodes.flatMap((node, index) => [
    {
      id: node.id,
      caption: node.caption,
      parentId,
      icon: node.icon || null,
      order: node.order || (index + 1) * 10,
      viewId: node.viewId || null,
      shortcut: node.shortcut || null,
      openedBy: node.openedBy || null,
      type: node.kind.toUpperCase(),
      description: node.description || null,
      classNames: node.classNames || null,
      opened: node.opened,
      bean: node.bean || null,
      beanMethod: node.beanMethod || null,
      title: node.titleExpression || null,
    },
    ...flattenForGeneration(node.children, node.id),
  ])
}

function fromSnapshot(node: MenuSourceSnapshot['nodes'][number]): MenuNode {
  return {
    id: node.id,
    kind: node.kind,
    caption: node.caption,
    titleExpression: node.titleExpression ?? '',
    description: node.description ?? '',
    icon: node.icon ?? (node.kind === 'menu' ? 'folder' : 'file'),
    classNames: node.classNames ?? '',
    opened: node.opened,
    viewId: node.viewId ?? '',
    shortcut: node.shortcut ?? '',
    openedBy: node.openedBy ?? '',
    bean: node.bean ?? '',
    beanMethod: node.beanMethod ?? '',
    order: node.order,
    syntheticId: node.syntheticId,
    properties: node.properties,
    routeParameters: node.routeParameters,
    urlQueryParameters: node.urlQueryParameters,
    preservedAttributes: node.preservedAttributes,
    children: node.children.map(fromSnapshot),
  }
}

const btnPrimary =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-50'
const btnGhost =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300'
const btnIcon =
  'rounded p-1 text-gray-500 transition-colors hover:bg-surface-lighter hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-30'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">{label}</span>
      {children}
    </label>
  )
}

export default function MenuDesigner() {
  const { addToast, isGenerating, setIsGenerating, setLastResult } = useStore()
  const [items, setItems] = useState<MenuNode[]>(STARTER_MENU)
  const [selectedId, setSelectedId] = useState<string | null>('operations')
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const [dropTargetId, setDropTargetId] = useState<string | null>(null)
  const [activePane, setActivePane] = useState<'structure' | 'preview' | 'properties'>('preview')
  const [menuSources, setMenuSources] = useState<MenuSourceSnapshot[]>([])
  const [springBeans, setSpringBeans] = useState<MenuSpringBeanSnapshot[]>([])
  const [selectedSourcePath, setSelectedSourcePath] = useState('')
  const [workspaceLoading, setWorkspaceLoading] = useState(true)
  const uid = useRef(1)

  const selected = selectedId ? findMenuNode(items, selectedId) : null
  const selectedIsMenu = selected?.kind === 'menu'
  const selectedParentId = selectedId ? findParentId(items, selectedId) : null
  const total = countMenuNodes(items)
  const depth = maximumDepth(items)
  const rows = useMemo(() => visibleMenuRows(items, collapsed), [items, collapsed])
  const containers = useMemo(() => allMenuContainers(items), [items])
  const selectedBeanMatches = useMemo(
    () => selected?.kind === 'bean'
      ? springBeans.filter((bean) => bean.name === selected.bean)
      : [],
    [selected, springBeans],
  )
  const selectedIndexedBean = selectedBeanMatches.length === 1 &&
    !selectedBeanMatches[0].ambiguous
    ? selectedBeanMatches[0]
    : null
  const selectedMethodMatches = selectedIndexedBean && selected?.kind === 'bean'
    ? selectedIndexedBean.methods.filter((method) => method.name === selected.beanMethod)
    : []

  useEffect(() => {
    let active = true
    bridge.request<MenuWorkspaceResponse>('getMenuWorkspace', {})
      .then((workspace) => {
        if (!active) return
        setMenuSources(workspace.sources)
        setSpringBeans(workspace.springBeans ?? [])
        if (workspace.sources.length > 0) {
          const first = workspace.sources[0]
          setSelectedSourcePath(first.relativePath)
          setItems(first.nodes.map(fromSnapshot))
          setSelectedId(first.nodes[0]?.id ?? null)
          const warnings = [...workspace.warnings, ...first.warnings]
          if (warnings.length > 0) addToast(warnings[0], 'info')
        }
      })
      .catch(() => {
        if (active) addToast('Using a new menu draft; no indexed menu source was available.', 'info')
      })
      .finally(() => active && setWorkspaceLoading(false))
    return () => { active = false }
  }, [addToast])

  const selectNode = (id: string) => {
    setSelectedId(id)
    setActivePane('properties')
  }

  const selectSource = (relativePath: string) => {
    const source = menuSources.find((candidate) => candidate.relativePath === relativePath)
    if (!source) return
    setSelectedSourcePath(relativePath)
    setItems(source.nodes.map(fromSnapshot))
    setSelectedId(source.nodes[0]?.id ?? null)
    setCollapsed(new Set())
  }

  const toggleCollapsed = (id: string) => {
    setCollapsed((previous) => {
      const next = new Set(previous)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const preferredParentId = (): string | null => {
    if (!selected) return null
    return selected.kind === 'menu' ? selected.id : selectedParentId
  }

  const addNode = (kind: MenuNodeKind) => {
    const parentId = kind === 'menu' ? preferredParentId() : preferredParentId()
    const parent = parentId ? findMenuNode(items, parentId) : null
    if (parent && parent.kind !== 'menu') return
    const sequence = uid.current++
    const id = kind === 'menu' ? `menuGroup${sequence}` : `${kind}Item${sequence}`
    const caption = kind === 'menu' ? 'New Submenu' : kind === 'separator' ? 'Separator' : kind === 'bean' ? 'Bean Action' : 'New View'
    const node = defaultNode(id, kind, caption, ((parent?.children.length ?? items.length) + 1) * 10)
    setItems((previous) => insertMenuNode(previous, parentId, node))
    if (parentId) {
      setCollapsed((previous) => {
        const next = new Set(previous)
        next.delete(parentId)
        return next
      })
    }
    setSelectedId(id)
  }

  const handleRemove = (id: string) => {
    setItems((previous) => removeMenuNode(previous, id))
    if (selectedId === id || (selectedId && isDescendant(items, id, selectedId))) setSelectedId(null)
  }

  const handleUpdate = (patch: Partial<MenuNode>) => {
    if (!selectedId) return
    const nextId = patch.id
    setItems((previous) => updateMenuNode(previous, selectedId, patch))
    if (nextId && nextId !== selectedId) setSelectedId(nextId)
  }

  const handleParentChange = (parentId: string) => {
    if (!selectedId) return
    setItems((previous) => reparentMenuNode(previous, selectedId, parentId || null))
  }

  const handleIndent = (id: string) => {
    const parentId = findParentId(items, id)
    const index = childIndex(items, parentId, id)
    const siblings = parentId === null ? items : findMenuNode(items, parentId)?.children ?? []
    const previous = siblings[index - 1]
    if (!previous || previous.kind !== 'menu') {
      addToast('Indent requires a submenu immediately above this node.', 'info')
      return
    }
    setItems((current) => reparentMenuNode(current, id, previous.id))
    setCollapsed((current) => {
      const next = new Set(current)
      next.delete(previous.id)
      return next
    })
  }

  const handleOutdent = (id: string) => {
    const parentId = findParentId(items, id)
    if (!parentId) return
    const grandParentId = findParentId(items, parentId)
    const parentIndex = childIndex(items, grandParentId, parentId)
    setItems((current) => reparentMenuNode(current, id, grandParentId, parentIndex + 1))
  }

  const handleDrop = (event: DragEvent, target: MenuNode) => {
    event.preventDefault()
    const movingId = draggingId
    setDraggingId(null)
    setDropTargetId(null)
    if (!movingId || movingId === target.id || isDescendant(items, movingId, target.id)) return
    if (target.kind === 'menu') {
      setItems((current) => reparentMenuNode(current, movingId, target.id))
      setCollapsed((current) => {
        const next = new Set(current)
        next.delete(target.id)
        return next
      })
      return
    }
    const parentId = findParentId(items, target.id)
    setItems((current) =>
      reparentMenuNode(current, movingId, parentId, childIndex(current, parentId, target.id) + 1),
    )
  }

  const handleGenerate = async () => {
    if (items.length === 0) {
      addToast('Add at least one menu node.', 'error')
      return
    }
    const entries = flattenForGeneration(items)
    const ids = entries.map((entry) => String(entry.id))
    const duplicate = ids.find((id, index) => ids.indexOf(id) !== index)
    if (duplicate) {
      addToast(`Duplicate menu id: "${duplicate}"`, 'error')
      return
    }
    if (items.some((node) => node.kind !== 'menu')) {
      addToast('Root-level view, bean, and separator nodes must be inside a menu.', 'error')
      return
    }
    const invalidBean = entries.find((entry) =>
      entry.type === 'BEAN' && (!entry.bean || !entry.beanMethod),
    )
    if (invalidBean) {
      addToast(`Bean item "${invalidBean.id}" needs both bean and method.`, 'error')
      return
    }
    const unsafeIndexedBean = entries.find((entry) => {
      if (entry.type !== 'BEAN') return false
      const matches = springBeans.filter((bean) => bean.name === entry.bean)
      if (matches.length > 1 || matches.some((bean) => bean.ambiguous)) return true
      if (matches.length === 0) return false
      const methods = matches[0].methods.filter((method) => method.name === entry.beanMethod)
      return methods.length !== 1 || !methods[0].callable
    })
    if (unsafeIndexedBean) {
      addToast(
        `Bean item "${unsafeIndexedBean.id}" has an ambiguous, missing, overloaded, or unsafe indexed method.`,
        'error',
      )
      return
    }

    setIsGenerating(true)
    try {
      const result = await bridge.request<GenerationResult>('generateMenu', {
        entries,
        sourcePath: selectedSourcePath || null,
        expectedRevisionFingerprint:
          menuSources.find((source) => source.relativePath === selectedSourcePath)
            ?.sourceLocator.revisionFingerprint ?? null,
      })
      setLastResult(result)
      addToast(
        result.success
          ? `Nested menu generated — ${result.filesWritten.length} file(s) changed`
          : result.errors?.[0] ?? 'Menu generation failed',
        result.success ? 'success' : 'error',
      )
    } catch {
      addToast('Menu generation failed — bridge unavailable.', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const rowActions = (row: VisibleRow) => {
    const { node, depth: rowDepth } = row
    const parentId = findParentId(items, node.id)
    const index = childIndex(items, parentId, node.id)
    const siblings = parentId === null ? items : findMenuNode(items, parentId)?.children ?? []
    return (
      <span className="ml-auto hidden shrink-0 items-center gap-0.5 group-hover/row:flex group-focus-within/row:flex">
        <button
          onClick={(event) => { event.stopPropagation(); handleIndent(node.id) }}
          disabled={index <= 0 || siblings[index - 1]?.kind !== 'menu'}
          className={btnIcon}
          title="Indent under previous submenu"
          aria-label="Indent menu node"
        >
          <CornerDownRight size={11} />
        </button>
        <button
          onClick={(event) => { event.stopPropagation(); handleOutdent(node.id) }}
          disabled={rowDepth === 0}
          className={btnIcon}
          title="Move one level out"
          aria-label="Outdent menu node"
        >
          <CornerUpLeft size={11} />
        </button>
        <button
          onClick={(event) => { event.stopPropagation(); setItems((previous) => moveMenuNode(previous, node.id, -1)) }}
          className={btnIcon}
          title="Move up"
          aria-label="Move up"
        >
          <ArrowUp size={11} />
        </button>
        <button
          onClick={(event) => { event.stopPropagation(); setItems((previous) => moveMenuNode(previous, node.id, 1)) }}
          className={btnIcon}
          title="Move down"
          aria-label="Move down"
        >
          <ArrowDown size={11} />
        </button>
        <button
          onClick={(event) => { event.stopPropagation(); handleRemove(node.id) }}
          className="rounded p-1 text-gray-500 transition-colors hover:bg-red-500/15 hover:text-red-400"
          title="Delete"
          aria-label="Delete"
        >
          <Trash2 size={11} />
        </button>
      </span>
    )
  }

  const renderTreeRow = (row: VisibleRow) => {
    const { node, depth: rowDepth } = row
    const Icon = node.kind === 'separator' ? Minus : iconFor(node.icon)
    const isSelected = selectedId === node.id
    const isCollapsed = collapsed.has(node.id)
    const isDropTarget = dropTargetId === node.id
    return (
      <div
        key={node.id}
        role="treeitem"
        aria-level={rowDepth + 1}
        aria-expanded={node.kind === 'menu' ? !isCollapsed : undefined}
        tabIndex={0}
        draggable
        onDragStart={(event) => {
          setDraggingId(node.id)
          event.dataTransfer.effectAllowed = 'move'
          event.dataTransfer.setData('text/plain', node.id)
        }}
        onDragEnd={() => { setDraggingId(null); setDropTargetId(null) }}
        onDragOver={(event) => {
          event.preventDefault()
          event.dataTransfer.dropEffect = 'move'
          setDropTargetId(node.id)
        }}
        onDragLeave={() => setDropTargetId((current) => current === node.id ? null : current)}
        onDrop={(event) => handleDrop(event, node)}
        onClick={() => selectNode(node.id)}
        onKeyDown={(event) => event.key === 'Enter' && selectNode(node.id)}
        className={`group/row relative flex w-full cursor-pointer items-center gap-1 rounded py-1.5 pr-1 text-left text-[11px] transition-all ${
          isDropTarget
            ? 'bg-emerald-500/15 text-emerald-200 ring-1 ring-inset ring-emerald-400/60'
            : isSelected
              ? 'bg-jmix-500/15 text-jmix-300 ring-1 ring-inset ring-jmix-500/40'
              : 'text-gray-400 hover:bg-surface-lighter hover:text-gray-200'
        } ${draggingId === node.id ? 'opacity-40' : ''}`}
        style={{ paddingLeft: `${6 + rowDepth * 16}px` }}
      >
        <GripVertical size={10} className="shrink-0 text-gray-600" />
        {node.kind === 'menu' ? (
          <button
            onClick={(event) => { event.stopPropagation(); toggleCollapsed(node.id) }}
            className="rounded p-px text-gray-500 hover:text-gray-300"
            title={isCollapsed ? 'Expand' : 'Collapse'}
          >
            {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
          </button>
        ) : (
          <span className="w-3 shrink-0 border-t border-surface-border/70" />
        )}
        <Icon size={12} className={`shrink-0 ${isSelected ? 'text-jmix-400' : 'text-gray-500'}`} />
        <span className="min-w-0 truncate">{node.kind === 'separator' ? '— separator —' : node.caption || node.id}</span>
        <span className="shrink-0 rounded bg-surface px-1 py-px font-mono text-[8px] text-gray-600">
          L{rowDepth + 1}
        </span>
        {node.kind === 'menu' && (
          <span className="shrink-0 rounded-full bg-surface-lighter px-1.5 py-px text-[8px] text-gray-500">
            {node.children.length}
          </span>
        )}
        {rowActions(row)}
      </div>
    )
  }

  const renderPreviewNodes = (nodes: MenuNode[], previewDepth = 0): ReactNode =>
    nodes.map((node) => {
      const Icon = node.kind === 'separator' ? Minus : iconFor(node.icon)
      const isSelected = selectedId === node.id
      if (node.kind === 'separator') {
        return <div key={node.id} className="my-1 border-t border-surface-border" />
      }
      return (
        <div key={node.id}>
          <button
            onClick={() => selectNode(node.id)}
            className={`flex w-full items-center gap-2 rounded py-1.5 pr-2 text-left text-[11px] transition-all ${
              isSelected
                ? 'bg-jmix-500/15 font-medium text-jmix-300'
                : 'text-gray-400 hover:bg-surface-lighter hover:text-gray-200'
            }`}
            style={{ paddingLeft: `${8 + previewDepth * 14}px` }}
          >
            {node.kind === 'menu' && <ChevronDown size={10} className="shrink-0 text-gray-600" />}
            <Icon size={12} className={`shrink-0 ${isSelected ? 'text-jmix-400' : 'text-gray-500'}`} />
            <span className="min-w-0 truncate">{node.caption || node.id}</span>
            {node.shortcut && (
              <kbd className="ml-auto shrink-0 rounded border border-surface-border bg-surface px-1 py-px text-[8px] text-gray-500">
                {node.shortcut}
              </kbd>
            )}
          </button>
          {node.kind === 'menu' && node.children.length > 0 && (
            <div className="relative before:absolute before:bottom-1 before:left-[13px] before:top-0 before:border-l before:border-surface-border/60">
              {renderPreviewNodes(node.children, previewDepth + 1)}
            </div>
          )}
        </div>
      )
    })

  const invalidParents = selectedId
    ? new Set([selectedId, ...containers.filter((container) => isDescendant(items, selectedId, container.id)).map((container) => container.id)])
    : new Set<string>()

  return (
    <div className="flex h-full min-w-0 flex-col bg-surface [color-scheme:dark]">
      <header className="flex flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light/60 px-3 py-2">
        <div className="flex items-center gap-2">
          <MenuIcon size={15} className="text-jmix-400" />
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-300">Menu Designer</h2>
        </div>
        <div className="flex flex-wrap items-center gap-1.5 sm:ml-2">
          <button onClick={() => addNode('menu')} className={btnGhost}>
            <FolderPlus size={12} className="text-jmix-400" /> Add Submenu
          </button>
          <button onClick={() => addNode('view')} className={btnGhost}>
            <FilePlus2 size={12} className="text-jmix-400" /> Add View
          </button>
          <button onClick={() => addNode('bean')} className={btnGhost}>Bean Action</button>
          <button onClick={() => addNode('separator')} className={btnGhost}><Minus size={12} /> Separator</button>
        </div>
        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          <span className="rounded-full border border-surface-border bg-surface-lighter px-2 py-0.5 text-[10px] text-gray-400">
            {total} nodes · {depth} levels
          </span>
          <button onClick={handleGenerate} disabled={isGenerating || workspaceLoading} className={btnPrimary}>
            {isGenerating || workspaceLoading ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            Generate Menu
          </button>
        </div>
      </header>

      {menuSources.length > 0 && (
        <div className="flex min-w-0 items-center gap-2 border-b border-surface-border bg-surface-light/30 px-3 py-1.5">
          <span className="shrink-0 text-[9px] font-semibold uppercase tracking-wider text-gray-500">Indexed source</span>
          <select
            value={selectedSourcePath}
            onChange={(event) => selectSource(event.target.value)}
            className="min-w-0 flex-1 py-1 font-mono text-[10px]"
          >
            {menuSources.map((source) => (
              <option key={source.relativePath} value={source.relativePath}>
                {source.moduleId} · {source.relativePath} · {source.maximumDepth} levels
              </option>
            ))}
          </select>
        </div>
      )}

      <ResponsivePaneSwitcher
        value={activePane}
        onChange={setActivePane}
        label="Menu designer panels"
        options={[
          { id: 'structure', label: 'Structure', icon: <ListTree size={12} />, badge: total },
          { id: 'preview', label: 'Preview', icon: <MenuIcon size={12} /> },
          { id: 'properties', label: 'Properties', icon: <Settings size={12} /> },
        ]}
      />

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <aside className={`${activePane === 'structure' ? 'flex' : 'hidden'} min-h-0 w-full shrink-0 flex-col bg-surface-light/40 lg:flex lg:w-[min(29vw,22rem)] lg:border-r lg:border-surface-border`}>
          <div className="flex items-center justify-between gap-2 border-b border-surface-border px-3 py-2">
            <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
              <ListTree size={12} className="text-jmix-400" /> Hierarchy
            </span>
            <span className="text-[9px] text-gray-600">Drag onto a submenu to nest</span>
          </div>
          <div role="tree" aria-label="Application menu hierarchy" className="flex-1 space-y-px overflow-auto p-2">
            {rows.map(renderTreeRow)}
            {items.length === 0 && (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <FolderPlus size={20} className="text-gray-600" />
                <p className="text-[11px] leading-relaxed text-gray-500">No menu nodes. Add a root submenu.</p>
              </div>
            )}
          </div>
        </aside>

        <section
          className={`${activePane === 'preview' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-1 flex-col items-center overflow-auto p-3 sm:p-5 lg:flex`}
          style={{
            backgroundImage: 'radial-gradient(circle, rgba(69,71,90,0.45) 1px, transparent 1px)',
            backgroundSize: '18px 18px',
          }}
        >
          <p className="mb-3 self-start text-[10px] font-semibold uppercase tracking-widest text-gray-500">
            Live Preview — recursive application menu
          </p>
          <div className="grid w-full max-w-5xl overflow-hidden rounded-lg border border-surface-border bg-surface-light shadow-2xl shadow-black/40 sm:grid-cols-[minmax(14rem,280px)_minmax(0,1fr)]">
            <div className="min-w-0 border-surface-border sm:border-r">
              <div className="border-b border-surface-border px-3 py-2.5">
                <p className="text-xs font-bold tracking-wide text-jmix-400">APPLICATION</p>
                <p className="text-[9px] text-gray-500">{depth}-level nested menu</p>
              </div>
              <nav className="p-2" aria-label="Menu preview">
                {renderPreviewNodes(items)}
                {items.length === 0 && <p className="px-2 py-6 text-center text-[10px] text-gray-600">Menu preview is empty</p>}
              </nav>
            </div>
            <div className="hidden min-w-0 flex-col bg-surface/70 sm:flex">
              <div className="flex items-center gap-2 border-b border-surface-border px-4 py-3">
                <span className="h-2 w-2 rounded-full bg-emerald-400" />
                <span className="truncate text-[10px] text-gray-500">Indexed hierarchy preview</span>
              </div>
              <div className="flex flex-1 flex-col justify-center p-5">
                <div className="text-[9px] font-semibold uppercase tracking-widest text-jmix-400">Selected destination</div>
                <div className="mt-2 truncate text-lg font-semibold text-gray-200">{selected?.caption || 'Choose a menu node'}</div>
                <div className="mt-1 truncate font-mono text-[10px] text-gray-500">
                  {selected?.viewId || (selected?.kind === 'menu' ? `${selected.children.length} direct children` : 'No view is connected')}
                </div>
                <div className="mt-5 grid gap-2 xl:grid-cols-3">
                  {['Menu policy', 'View policy', 'Row constraints'].map((label, index) => (
                    <div key={label} className="rounded border border-surface-border bg-surface-light px-3 py-2">
                      <div className="text-[9px] uppercase tracking-wider text-gray-600">{index + 1}</div>
                      <div className="mt-1 text-[10px] text-gray-300">{label}</div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </section>

        <aside className={`${activePane === 'properties' ? 'flex' : 'hidden'} min-h-0 w-full shrink-0 flex-col overflow-y-auto bg-surface-light/40 lg:flex lg:w-[min(29vw,22rem)] lg:border-l lg:border-surface-border`}>
          <div className="flex items-center justify-between border-b border-surface-border px-3 py-2">
            <span className="text-[10px] font-semibold uppercase tracking-widest text-gray-500">Properties</span>
            {selected && (
              <span className="rounded-full bg-jmix-500/15 px-2 py-px text-[9px] font-semibold uppercase tracking-wider text-jmix-300">
                {selected.kind}
              </span>
            )}
          </div>
          {!selected ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-center">
              <MenuIcon size={22} className="text-gray-600" />
              <p className="text-[11px] leading-relaxed text-gray-500">Select any hierarchy node to edit it.</p>
            </div>
          ) : (
            <div className="space-y-3 p-3">
              <Field label="Type">
                <select
                  value={selected.kind}
                  onChange={(event) => {
                    const kind = event.target.value as MenuNodeKind
                    if (kind !== 'menu' && selected.children.length > 0) {
                      addToast('Move or remove nested children before changing this submenu type.', 'error')
                      return
                    }
                    handleUpdate({ kind })
                  }}
                  className="w-full py-1 text-xs"
                >
                  <option value="menu">Menu / submenu</option>
                  <option value="view">View</option>
                  <option value="bean">Bean action</option>
                  <option value="separator">Separator</option>
                </select>
              </Field>
              <Field label="Parent menu">
                <select
                  value={selectedParentId ?? ''}
                  onChange={(event) => handleParentChange(event.target.value)}
                  className="w-full py-1 text-xs"
                >
                  <option value="">Root</option>
                  {containers.filter((container) => !invalidParents.has(container.id)).map((container) => (
                    <option key={container.id} value={container.id}>{container.label}</option>
                  ))}
                </select>
              </Field>
              <Field label="Id">
                <input
                  value={selected.id}
                  disabled={selected.kind === 'separator' && selected.syntheticId}
                  onChange={(event) => handleUpdate({ id: event.target.value.replace(/\s+/g, '') })}
                  className="w-full py-1 font-mono text-xs disabled:opacity-50"
                />
              </Field>
              <Field label="Caption / title">
                <input
                  value={selected.caption}
                  disabled={selected.kind === 'separator'}
                  onChange={(event) => handleUpdate({ caption: event.target.value })}
                  className="w-full py-1 text-xs disabled:opacity-50"
                />
              </Field>
              <Field label="Description">
                <input
                  value={selected.description}
                  disabled={selected.kind === 'separator'}
                  onChange={(event) => handleUpdate({ description: event.target.value })}
                  className="w-full py-1 text-xs disabled:opacity-50"
                />
              </Field>
              {selected.kind === 'view' && (
                <Field label="View Id">
                  <input
                    value={selected.viewId}
                    onChange={(event) => handleUpdate({ viewId: event.target.value })}
                    className="w-full py-1 font-mono text-xs"
                    placeholder="Customer.list"
                  />
                </Field>
              )}
              {selected.kind === 'bean' && (
                <div className="space-y-2">
                  <div className="grid grid-cols-1 gap-2">
                    <Field label="Spring bean">
                      <input
                        list="jvw-menu-spring-beans"
                        value={selected.bean}
                        onChange={(event) => {
                          const bean = event.target.value
                          handleUpdate({
                            bean,
                            beanMethod: bean === selected.bean ? selected.beanMethod : '',
                          })
                        }}
                        className="w-full py-1 font-mono text-xs"
                        placeholder="Select or enter a bean"
                        autoComplete="off"
                      />
                      <datalist id="jvw-menu-spring-beans">
                        {springBeans.map((bean) => (
                          <option
                            key={`${bean.name}:${bean.sourcePath}:${bean.declarationName}`}
                            value={bean.name}
                          >
                            {bean.ambiguous
                              ? `Ambiguous · ${bean.sourcePath}`
                              : `${bean.declarationName} · ${bean.sourcePath}`}
                          </option>
                        ))}
                      </datalist>
                    </Field>
                    <Field label="Bean method">
                      <input
                        list="jvw-menu-bean-methods"
                        value={selected.beanMethod}
                        onChange={(event) => handleUpdate({ beanMethod: event.target.value })}
                        className="w-full py-1 font-mono text-xs"
                        placeholder="Select a callable method"
                        autoComplete="off"
                      />
                      <datalist id="jvw-menu-bean-methods">
                        {(selectedIndexedBean?.methods ?? [])
                          .filter((method) => method.callable)
                          .map((method) => (
                            <option
                              key={method.signature}
                              value={method.name}
                            >
                              {method.signature}
                            </option>
                          ))}
                      </datalist>
                    </Field>
                  </div>
                  {selected.bean && selectedBeanMatches.length === 0 && (
                    <p
                      className="rounded border border-amber-500/30 bg-amber-500/10 px-2 py-1.5 text-[10px] leading-relaxed text-amber-200"
                      role="status"
                    >
                      This bean is not in the current project index. It is preserved for custom
                      Spring configurations, but verify it in the native XML editor before apply.
                    </p>
                  )}
                  {selectedBeanMatches.length > 1 && (
                    <p
                      className="rounded border border-red-500/30 bg-red-500/10 px-2 py-1.5 text-[10px] leading-relaxed text-red-200"
                      role="alert"
                    >
                      Multiple Spring declarations use this bean name. Resolve the ambiguity
                      before generating the menu.
                    </p>
                  )}
                  {selectedIndexedBean && (
                    <div className="rounded border border-emerald-500/20 bg-emerald-500/5 px-2 py-1.5 text-[10px] leading-relaxed text-gray-400">
                      <p className="font-medium text-emerald-300">
                        Indexed {selectedIndexedBean.language || 'source'} bean
                      </p>
                      <p className="mt-0.5 break-all font-mono">
                        {selectedIndexedBean.sourcePath || selectedIndexedBean.declarationName}
                      </p>
                      {selected.beanMethod && selectedMethodMatches.length === 0 && (
                        <p className="mt-1 text-red-300" role="alert">
                          Method not found on this bean.
                        </p>
                      )}
                      {selectedMethodMatches.length > 1 && (
                        <p className="mt-1 text-red-300" role="alert">
                          Overloaded menu methods are ambiguous.
                        </p>
                      )}
                      {selectedMethodMatches.length === 1 &&
                        !selectedMethodMatches[0].callable && (
                          <p className="mt-1 text-red-300" role="alert">
                            {selectedMethodMatches[0].issue ?? 'Method is not menu-callable.'}
                          </p>
                        )}
                    </div>
                  )}
                </div>
              )}
              {selected.kind !== 'separator' && (
                <>
                  <Field label="Icon">
                    <input value={selected.icon} onChange={(event) => handleUpdate({ icon: event.target.value })} className="w-full py-1 font-mono text-xs" />
                  </Field>
                  <div className="flex flex-wrap gap-1">
                    {Object.keys(ICON_MAP).map((name) => {
                      const Icon = ICON_MAP[name]
                      return (
                        <button
                          key={name}
                          onClick={() => handleUpdate({ icon: name })}
                          title={name}
                          className={`rounded border p-1.5 transition-all ${
                            selected.icon === name
                              ? 'border-jmix-500 bg-jmix-500/20 text-jmix-300'
                              : 'border-surface-border bg-surface text-gray-500 hover:border-gray-500 hover:text-gray-300'
                          }`}
                        >
                          <Icon size={13} />
                        </button>
                      )
                    })}
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <Field label="Shortcut">
                      <input value={selected.shortcut} onChange={(event) => handleUpdate({ shortcut: event.target.value.toUpperCase() })} className="w-full py-1 font-mono text-xs" placeholder="ALT-C" />
                    </Field>
                    <Field label="Order">
                      <input type="number" value={selected.order} onChange={(event) => handleUpdate({ order: Number(event.target.value) || 0 })} className="w-full py-1 text-xs" />
                    </Field>
                  </div>
                </>
              )}
              {selectedIsMenu && (
                <label className="flex items-center gap-2 rounded border border-surface-border bg-surface px-2 py-1.5 text-[11px] text-gray-400">
                  <input type="checkbox" checked={selected.opened} onChange={(event) => handleUpdate({ opened: event.target.checked })} />
                  Initially expanded
                </label>
              )}
              {(Object.keys(selected.preservedAttributes).length > 0 ||
                Object.keys(selected.properties).length > 0 ||
                Object.keys(selected.routeParameters).length > 0 ||
                Object.keys(selected.urlQueryParameters).length > 0) && (
                <div className="rounded border border-amber-500/30 bg-amber-500/10 p-2 text-[10px] leading-relaxed text-amber-200">
                  Existing advanced attributes and parameters are indexed and preserved as source metadata.
                </div>
              )}
              <div className="border-t border-surface-border/70 pt-3">
                <button
                  onClick={() => handleRemove(selected.id)}
                  className="inline-flex w-full items-center justify-center gap-1.5 rounded border border-red-500/30 bg-red-500/10 px-2 py-1.5 text-[11px] text-red-400 transition-colors hover:bg-red-500/20"
                >
                  <Trash2 size={12} /> Delete node and nested children
                </button>
              </div>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
