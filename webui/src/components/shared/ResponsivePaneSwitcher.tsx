import type { ReactNode } from 'react'

export interface ResponsivePaneOption<T extends string> {
  id: T
  label: string
  icon?: ReactNode
  badge?: string | number
}

export default function ResponsivePaneSwitcher<T extends string>({
  value,
  onChange,
  options,
  label = 'Workspace panels',
  id = 'responsive-workspace-panel',
}: {
  value: T
  onChange: (value: T) => void
  options: ResponsivePaneOption<T>[]
  label?: string
  id?: string
}) {
  return (
    <div
      className="flex min-w-0 shrink-0 items-center gap-2 border-b border-surface-border bg-surface-light/70 px-3 py-2 lg:hidden"
    >
      <label htmlFor={id} className="shrink-0 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
        Panel
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value as T)}
        aria-label={label}
        className="min-w-0 flex-1 rounded border border-surface-border bg-surface px-2 py-1.5 text-[11px] text-gray-200"
      >
        {options.map((option) => (
          <option key={option.id} value={option.id}>
            {option.label}{option.badge !== undefined ? ` (${option.badge})` : ''}
          </option>
        ))}
      </select>
    </div>
  )
}
