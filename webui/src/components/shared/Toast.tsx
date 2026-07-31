import { useStore } from '../../store'

export default function Toast() {
  const { toasts, removeToast } = useStore()

  if (toasts.length === 0) return null

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          onClick={() => removeToast(toast.id)}
          className={`px-4 py-2.5 rounded-lg shadow-lg text-xs cursor-pointer max-w-sm animate-in slide-in-from-right ${
            toast.type === 'success'
              ? 'bg-green-900/90 text-green-200 border border-green-700'
              : toast.type === 'error'
              ? 'bg-red-900/90 text-red-200 border border-red-700'
              : 'bg-blue-900/90 text-blue-200 border border-blue-700'
          }`}
        >
          {toast.message}
        </div>
      ))}
    </div>
  )
}
