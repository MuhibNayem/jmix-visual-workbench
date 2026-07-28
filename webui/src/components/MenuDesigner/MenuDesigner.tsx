import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  ArrowDown, ArrowUp, BarChart3, Calendar, ChevronDown, ChevronRight, Database,
  FilePlus2, FileText, Folder, FolderPlus, HelpCircle, Home, ListTree, Loader2,
  Mail, Menu as MenuIcon, Play, Settings, Shield, Trash2, Users,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type { GenerationResult } from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

// ─── Model ───────────────────────────────────────────────────────────────────

interface MenuNode {
  id: string
  caption: string
  icon: string
  viewId: string
  shortcut: string
  order: number
  children: MenuNode[]
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
}

const iconFor = (name: string): LucideIcon => ICON_MAP[name.toLowerCase()] ?? FileText

// ─── Tree helpers (pure, immutable) ──────────────────────────────────────────

function findMenuNode(nodes: MenuNode[], id: string): MenuNode | null {
  for (const n of nodes) {
    if (n.id === id) return n
    const found = findMenuNode(n.children, id)
    if (found) return found
  }
  return null
}

function updateMenuNode(nodes: MenuNode[], id: string, patch: Partial<MenuNode>): MenuNode[] {
  return nodes.map((n) =>
    n.id === id
      ? { ...n, ...patch }
      : { ...n, children: updateMenuNode(n.children, id, patch) },
  )
}

function removeMenuNode(nodes: MenuNode[], id: string): MenuNode[] {
  return nodes
    .filter((n) => n.id !== id)
    .map((n) => ({ ...n, children: removeMenuNode(n.children, id) }))
}

function appendChild(nodes: MenuNode[], parentId: string, child: MenuNode): MenuNode[] {
  return nodes.map((n) =>
    n.id === parentId
      ? { ...n, children: [...n.children, child] }
      : { ...n, children: appendChild(n.children, parentId, child) },
  )
}

function moveMenuNode(nodes: MenuNode[], id: string, dir: -1 | 1): MenuNode[] {
  const idx = nodes.findIndex((n) => n.id === id)
  if (idx !== -1) {
    const target = idx + dir
    if (target < 0 || target >= nodes.length) return nodes
    const next = [...nodes]
    ;[next[idx], next[target]] = [next[target], next[idx]]
    return next
  }
  return nodes.map((n) => ({ ...n, children: moveMenuNode(n.children, id, dir) }))
}

function countMenuNodes(nodes: MenuNode[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countMenuNodes(n.children), 0)
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const btnPrimary =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-50'
const btnGhost =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300'
const btnIcon =
  'rounded p-1 text-gray-500 transition-colors hover:bg-surface-lighter hover:text-gray-200'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">{label}</span>
      {children}
    </label>
  )
}

// ─── Main component ──────────────────────────────────────────────────────────

export default function MenuDesigner() {
  const { addToast, isGenerating, setIsGenerating, setLastResult } = useStore()

  const [items, setItems] = useState<MenuNode[]>([
    {
      id: 'application', caption: 'Application', icon: 'folder', viewId: '', shortcut: '', order: 10,
      children: [
        { id: 'home', caption: 'Home', icon: 'home', viewId: 'HomeView', shortcut: '', order: 10, children: [] },
        { id: 'customers', caption: 'Customers', icon: 'users', viewId: 'CustomerListView', shortcut: 'ALT+C', order: 20, children: [] },
      ],
    },
  ])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const [activePane, setActivePane] = useState<'structure' | 'preview' | 'properties'>('preview')

  const uid = useRef(1)
  const selected = selectedId ? findMenuNode(items, selectedId) : null
  const selectedIsGroup = selectedId ? items.some((g) => g.id === selectedId) : false
  const total = countMenuNodes(items)
  const selectNode = (id: string) => {
    setSelectedId(id)
    setActivePane('properties')
  }

  const toggleCollapsed = (id: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  // ── Add / remove / move ────────────────────────────────────────────────────

  const handleAddGroup = () => {
    const id = `menuGroup${uid.current++}`
    setItems((prev) => [
      ...prev,
      { id, caption: 'New Group', icon: 'folder', viewId: '', shortcut: '', order: (prev.length + 1) * 10, children: [] },
    ])
    setSelectedId(id)
  }

  const handleAddItem = () => {
    // Resolve target group: selected group → itself, selected item → its parent, else first group
    let group: MenuNode | undefined
    if (selectedId) {
      group = selectedIsGroup
        ? items.find((g) => g.id === selectedId)
        : items.find((g) => g.children.some((c) => c.id === selectedId))
    }
    group = group ?? items[0]
    if (!group) {
      addToast('Add a menu group first', 'info')
      return
    }
    const id = `menuItem${uid.current++}`
    const item: MenuNode = {
      id, caption: 'New Item', icon: 'file', viewId: '', shortcut: '',
      order: (group.children.length + 1) * 10, children: [],
    }
    setItems((prev) => appendChild(prev, group!.id, item))
    setCollapsed((prev) => {
      const next = new Set(prev)
      next.delete(group!.id)
      return next
    })
    setSelectedId(id)
  }

  const handleRemove = (id: string) => {
    setItems((prev) => removeMenuNode(prev, id))
    if (selectedId === id) setSelectedId(null)
  }

  const handleUpdate = (patch: Partial<MenuNode>) => {
    if (!selectedId) return
    setItems((prev) => updateMenuNode(prev, selectedId, patch))
  }

  // ── Generate ───────────────────────────────────────────────────────────────

  const handleGenerate = async () => {
    if (items.length === 0) {
      addToast('Add at least one menu group', 'error')
      return
    }
    const ids: string[] = []
    const collect = (ns: MenuNode[]) => ns.forEach((n) => { ids.push(n.id); collect(n.children) })
    collect(items)
    const dupes = ids.filter((id, i) => ids.indexOf(id) !== i)
    if (dupes.length > 0) {
      addToast(`Duplicate menu id: "${dupes[0]}"`, 'error')
      return
    }

    setIsGenerating(true)
    try {
      const result = await bridge.request<GenerationResult>('generateMenu', { items })
      setLastResult(result)
      if (result.success) {
        addToast(`Menu generated — ${result.filesWritten.length} file(s) written`, 'success')
      } else {
        addToast(result.errors?.[0] ?? 'Menu generation failed', 'error')
      }
    } catch {
      addToast('Menu generation failed — bridge unavailable', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  // ── Tree row renderers ─────────────────────────────────────────────────────

  const rowActions = (node: MenuNode) => (
    <span className="ml-auto hidden items-center gap-0.5 group-hover/row:flex">
      <button
        onClick={(e) => { e.stopPropagation(); setItems((prev) => moveMenuNode(prev, node.id, -1)) }}
        className={btnIcon} title="Move up" aria-label="Move up"
      >
        <ArrowUp size={11} />
      </button>
      <button
        onClick={(e) => { e.stopPropagation(); setItems((prev) => moveMenuNode(prev, node.id, 1)) }}
        className={btnIcon} title="Move down" aria-label="Move down"
      >
        <ArrowDown size={11} />
      </button>
      <button
        onClick={(e) => { e.stopPropagation(); handleRemove(node.id) }}
        className="rounded p-1 text-gray-500 transition-colors hover:bg-red-500/15 hover:text-red-400"
        title="Delete" aria-label="Delete"
      >
        <Trash2 size={11} />
      </button>
    </span>
  )

  const renderItemRow = (item: MenuNode) => {
    const Icon = iconFor(item.icon)
    const isSelected = selectedId === item.id
    return (
      <div
        key={item.id}
        role="button"
        tabIndex={0}
        onClick={() => selectNode(item.id)}
        onKeyDown={(event) => event.key === 'Enter' && selectNode(item.id)}
        className={`group/row flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-[11px] transition-all ${
          isSelected
            ? 'bg-jmix-500/15 text-jmix-300 ring-1 ring-inset ring-jmix-500/40'
            : 'text-gray-400 hover:bg-surface-lighter hover:text-gray-200'
        }`}
      >
        <Icon size={12} className={isSelected ? 'text-jmix-400' : 'text-gray-500'} />
        <span className="truncate">{item.caption || item.id}</span>
        {item.viewId && (
          <span className="truncate rounded bg-surface-lighter px-1 py-px font-mono text-[9px] text-gray-500">
            {item.viewId}
          </span>
        )}
        {rowActions(item)}
      </div>
    )
  }

  const renderGroupRow = (group: MenuNode) => {
    const Icon = iconFor(group.icon)
    const isSelected = selectedId === group.id
    const isCollapsed = collapsed.has(group.id)
    return (
      <div key={group.id} className="mb-0.5">
        <div
          role="button"
          tabIndex={0}
          onClick={() => selectNode(group.id)}
          onKeyDown={(e) => e.key === 'Enter' && selectNode(group.id)}
          className={`group/row flex w-full cursor-pointer items-center gap-1.5 rounded px-2 py-1.5 text-left text-xs font-medium transition-all ${
            isSelected
              ? 'bg-jmix-500/15 text-jmix-300 ring-1 ring-inset ring-jmix-500/40'
              : 'text-gray-300 hover:bg-surface-lighter'
          }`}
        >
          <button
            onClick={(e) => { e.stopPropagation(); toggleCollapsed(group.id) }}
            className="rounded p-px text-gray-500 transition-transform hover:text-gray-300"
            title={isCollapsed ? 'Expand' : 'Collapse'}
            aria-label={isCollapsed ? 'Expand group' : 'Collapse group'}
          >
            {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
          </button>
          <Icon size={13} className={isSelected ? 'text-jmix-400' : 'text-gray-500'} />
          <span className="truncate">{group.caption || group.id}</span>
          <span className="rounded-full bg-surface-lighter px-1.5 py-px text-[9px] font-normal text-gray-500">
            {group.children.length}
          </span>
          {rowActions(group)}
        </div>
        {!isCollapsed && (
          <div className="ml-4 mt-0.5 space-y-px border-l border-surface-border/70 pl-2">
            {group.children.map(renderItemRow)}
            {group.children.length === 0 && (
              <p className="px-2 py-1 text-[10px] italic text-gray-600">Empty group</p>
            )}
          </div>
        )}
      </div>
    )
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="flex h-full min-w-0 flex-col bg-surface [color-scheme:dark]">
      {/* Top bar */}
      <header className="flex flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light/60 px-3 py-2">
        <div className="flex items-center gap-2">
          <MenuIcon size={15} className="text-jmix-400" />
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-300">Menu Designer</h2>
        </div>

        <div className="flex flex-wrap items-center gap-1.5 sm:ml-2">
          <button onClick={handleAddGroup} className={btnGhost}>
            <FolderPlus size={12} className="text-jmix-400" /> Add Group
          </button>
          <button onClick={handleAddItem} className={btnGhost}>
            <FilePlus2 size={12} className="text-jmix-400" /> Add Item
          </button>
        </div>

        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          <span className="rounded-full border border-surface-border bg-surface-lighter px-2 py-0.5 text-[10px] text-gray-400">
            {total} node{total === 1 ? '' : 's'}
          </span>
          <button onClick={handleGenerate} disabled={isGenerating} className={btnPrimary}>
            {isGenerating ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            Generate Menu
          </button>
        </div>
      </header>

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

      {/* Workspace */}
      <div className="flex min-h-0 flex-1 overflow-hidden">
        {/* Left: structure tree */}
        <aside className={`${activePane === 'structure' ? 'flex' : 'hidden'} min-h-0 w-full shrink-0 flex-col bg-surface-light/40 min-[1600px]:flex min-[1600px]:w-64 min-[1600px]:border-r min-[1600px]:border-surface-border`}>
          <div className="flex items-center gap-1.5 border-b border-surface-border px-3 py-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
            <ListTree size={12} className="text-jmix-400" /> Structure
          </div>
          <div className="flex-1 overflow-y-auto p-2">
            {items.map(renderGroupRow)}
            {items.length === 0 && (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <FolderPlus size={20} className="text-gray-600" />
                <p className="text-[11px] leading-relaxed text-gray-500">
                  No menu structure yet. Start by adding a menu group.
                </p>
                <button onClick={handleAddGroup} className={btnGhost}>
                  <FolderPlus size={12} /> Add Group
                </button>
              </div>
            )}
          </div>
        </aside>

        {/* Center: live preview */}
        <section
          className={`${activePane === 'preview' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-1 flex-col items-center overflow-auto p-3 sm:p-6 min-[1600px]:flex`}
          style={{
            backgroundImage: 'radial-gradient(circle, rgba(69,71,90,0.45) 1px, transparent 1px)',
            backgroundSize: '18px 18px',
          }}
        >
          <p className="mb-3 self-start text-[10px] font-semibold uppercase tracking-widest text-gray-500">
            Live Preview — application sidebar
          </p>
          <div className="grid w-full max-w-5xl overflow-hidden rounded-lg border border-surface-border bg-surface-light shadow-2xl shadow-black/40 sm:grid-cols-[minmax(13rem,250px)_minmax(0,1fr)]">
            <div className="min-w-0 border-surface-border sm:border-r">
              <div className="border-b border-surface-border px-3 py-2.5">
                <p className="text-xs font-bold tracking-wide text-jmix-400">APPLICATION</p>
                <p className="text-[9px] text-gray-500">main menu</p>
              </div>
              <nav className="space-y-2 p-2" aria-label="Menu preview">
                {items.map((group) => {
                  const GroupIcon = iconFor(group.icon)
                  return (
                    <div key={group.id}>
                      <div className="flex items-center gap-1.5 px-2 py-1 text-[9px] font-semibold uppercase tracking-widest text-gray-500">
                        <GroupIcon size={10} />
                        {group.caption || group.id}
                      </div>
                      <div className="mt-0.5 space-y-px">
                        {group.children.map((item) => {
                          const ItemIcon = iconFor(item.icon)
                          const isSelected = selectedId === item.id
                          return (
                            <button
                              key={item.id}
                              onClick={() => selectNode(item.id)}
                              className={`flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-[11px] transition-all ${
                                isSelected
                                  ? 'bg-jmix-500/15 font-medium text-jmix-300'
                                  : 'text-gray-400 hover:bg-surface-lighter hover:text-gray-200'
                              }`}
                            >
                              <ItemIcon size={13} className={isSelected ? 'text-jmix-400' : 'text-gray-500'} />
                              <span className="truncate">{item.caption || item.id}</span>
                              {item.shortcut && (
                                <kbd className="ml-auto shrink-0 rounded border border-surface-border bg-surface px-1 py-px text-[8px] text-gray-500">
                                  {item.shortcut}
                                </kbd>
                              )}
                            </button>
                          )
                        })}
                        {group.children.length === 0 && (
                          <p className="px-2 py-1 text-[10px] italic text-gray-600">No items</p>
                        )}
                      </div>
                    </div>
                  )
                })}
                {items.length === 0 && (
                  <p className="px-2 py-6 text-center text-[10px] text-gray-600">Menu preview is empty</p>
                )}
              </nav>
            </div>
            <div className="hidden min-w-0 flex-col bg-surface/70 sm:flex">
              <div className="flex items-center gap-2 border-b border-surface-border px-4 py-3">
                <span className="h-2 w-2 rounded-full bg-emerald-400" />
                <span className="truncate text-[10px] text-gray-500">Authenticated application preview</span>
              </div>
              <div className="flex flex-1 flex-col justify-center p-5">
                <div className="text-[9px] font-semibold uppercase tracking-widest text-jmix-400">
                  Selected destination
                </div>
                <div className="mt-2 truncate text-lg font-semibold text-gray-200">
                  {selected?.caption || 'Choose a menu item'}
                </div>
                <div className="mt-1 truncate font-mono text-[10px] text-gray-500">
                  {selected?.viewId || 'No view is connected yet'}
                </div>
                <div className="mt-5 grid gap-2 lg:grid-cols-3">
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

        {/* Right: editor */}
        <aside className={`${activePane === 'properties' ? 'flex' : 'hidden'} min-h-0 w-full shrink-0 flex-col overflow-y-auto bg-surface-light/40 min-[1600px]:flex min-[1600px]:w-64 min-[1600px]:border-l min-[1600px]:border-surface-border`}>
          <div className="flex items-center justify-between border-b border-surface-border px-3 py-2">
            <span className="text-[10px] font-semibold uppercase tracking-widest text-gray-500">Properties</span>
            {selected && (
              <span className={`rounded-full px-2 py-px text-[9px] font-semibold uppercase tracking-wider ${
                selectedIsGroup ? 'bg-amber-500/15 text-amber-300' : 'bg-jmix-500/15 text-jmix-300'
              }`}>
                {selectedIsGroup ? 'Group' : 'Item'}
              </span>
            )}
          </div>

          {!selected ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-center">
              <MenuIcon size={22} className="text-gray-600" />
              <p className="text-[11px] leading-relaxed text-gray-500">
                Select a group or item to edit its properties.
              </p>
            </div>
          ) : (
            <div className="space-y-3 p-3">
              <Field label="Id">
                <input
                  value={selected.id}
                  onChange={(e) => handleUpdate({ id: e.target.value.replace(/\s+/g, '') })}
                  className="w-full py-1 font-mono text-xs"
                />
              </Field>

              <Field label="Caption">
                <input
                  value={selected.caption}
                  onChange={(e) => handleUpdate({ caption: e.target.value })}
                  className="w-full py-1 text-xs"
                  placeholder="Customers"
                />
              </Field>

              <Field label="Icon">
                <input
                  value={selected.icon}
                  onChange={(e) => handleUpdate({ icon: e.target.value })}
                  className="w-full py-1 font-mono text-xs"
                  placeholder="users"
                />
              </Field>
              <div className="flex flex-wrap gap-1">
                {Object.keys(ICON_MAP).map((name) => {
                  const Icon = ICON_MAP[name]
                  const active = selected.icon === name
                  return (
                    <button
                      key={name}
                      onClick={() => handleUpdate({ icon: name })}
                      title={name}
                      aria-label={`Set icon ${name}`}
                      className={`rounded border p-1.5 transition-all ${
                        active
                          ? 'border-jmix-500 bg-jmix-500/20 text-jmix-300'
                          : 'border-surface-border bg-surface text-gray-500 hover:border-gray-500 hover:text-gray-300'
                      }`}
                    >
                      <Icon size={13} />
                    </button>
                  )
                })}
              </div>

              <Field label="View Id">
                <input
                  value={selected.viewId}
                  disabled={selectedIsGroup}
                  onChange={(e) => handleUpdate({ viewId: e.target.value })}
                  className="w-full py-1 font-mono text-xs disabled:cursor-not-allowed disabled:opacity-40"
                  placeholder="CustomerListView"
                />
              </Field>

              <div className="grid grid-cols-2 gap-2">
                <Field label="Shortcut">
                  <input
                    value={selected.shortcut}
                    disabled={selectedIsGroup}
                    onChange={(e) => handleUpdate({ shortcut: e.target.value.toUpperCase() })}
                    className="w-full py-1 font-mono text-xs disabled:cursor-not-allowed disabled:opacity-40"
                    placeholder="ALT+C"
                  />
                </Field>
                <Field label="Order">
                  <input
                    type="number"
                    value={selected.order}
                    onChange={(e) => handleUpdate({ order: Number(e.target.value) || 0 })}
                    className="w-full py-1 text-xs"
                  />
                </Field>
              </div>

              <div className="border-t border-surface-border/70 pt-3">
                <button
                  onClick={() => handleRemove(selected.id)}
                  className="inline-flex w-full items-center justify-center gap-1.5 rounded border border-red-500/30 bg-red-500/10 px-2 py-1.5 text-[11px] text-red-400 transition-colors hover:bg-red-500/20"
                >
                  <Trash2 size={12} />
                  Delete {selectedIsGroup ? 'group' : 'item'}
                </button>
              </div>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
