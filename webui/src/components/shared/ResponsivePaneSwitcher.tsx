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
}: {
  value: T
  onChange: (value: T) => void
  options: ResponsivePaneOption<T>[]
  label?: string
}) {
  return (
    <div className="flex min-w-0 shrink-0 gap-1 overflow-hidden border-b border-surface-border bg-surface-light/70 px-2 py-2 min-[1600px]:hidden" role="tablist" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.id}
          type="button"
          role="tab"
          aria-selected={value === option.id}
          onClick={() => onChange(option.id)}
          className={`inline-flex min-w-0 flex-1 items-center justify-center gap-1 rounded px-1.5 py-1.5 text-[11px] font-medium transition-colors sm:gap-1.5 sm:px-3 ${
            value === option.id
              ? 'bg-jmix-500 text-white shadow-sm'
              : 'border border-surface-border bg-surface text-gray-400 hover:text-gray-200'
          }`}
        >
          {option.icon}
          <span className="truncate">{option.label}</span>
          {option.badge !== undefined && (
            <span className={`rounded-full px-1.5 py-px text-[9px] ${
              value === option.id ? 'bg-white/15 text-white' : 'bg-surface-lighter text-gray-500'
            }`}>
              {option.badge}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}
