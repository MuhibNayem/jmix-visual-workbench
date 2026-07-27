import type { GenerationResult, ProjectConfig } from '../types'

type BridgeCallback = (action: string, result: any) => void

declare global {
  interface Window {
    javaBridge?: {
      send: (action: string, payload: any) => void
    }
    onBridgeResponse?: (action: string, result: any) => void
    onBridgeReady?: () => void
  }
}

class Bridge {
  private listeners: BridgeCallback[] = []
  private ready = false
  private pendingQueue: { action: string; payload: any }[] = []

  constructor() {
    window.onBridgeResponse = (action: string, result: any) => {
      this.listeners.forEach(cb => cb(action, result))
    }

    window.onBridgeReady = () => {
      this.ready = true
      this.pendingQueue.forEach(({ action, payload }) => this.send(action, payload))
      this.pendingQueue = []
    }

    // If bridge is already available (e.g., dev mode without JCEF)
    if (window.javaBridge) {
      this.ready = true
    }
  }

  send(action: string, payload: any = {}) {
    if (!this.ready || !window.javaBridge) {
      this.pendingQueue.push({ action, payload })
      // In dev mode, simulate response
      if (import.meta.env.DEV) {
        console.log(`[Bridge] ${action}`, payload)
        setTimeout(() => {
          this.listeners.forEach(cb => cb(action, {
            success: true,
            filesWritten: [`generated/${action}.java`],
            errors: [],
          }))
        }, 300)
      }
      return
    }
    window.javaBridge.send(action, payload)
  }

  onResponse(callback: BridgeCallback) {
    this.listeners.push(callback)
    return () => {
      this.listeners = this.listeners.filter(cb => cb !== callback)
    }
  }

  async request<T = any>(action: string, payload: any = {}): Promise<T> {
    return new Promise((resolve) => {
      const unsub = this.onResponse((respAction, result) => {
        if (respAction === action || respAction === 'error') {
          unsub()
          resolve(result)
        }
      })
      this.send(action, payload)
    })
  }

  generateEntity(entity: any) {
    return this.request<GenerationResult>('generateEntity', entity)
  }

  generateCrud(entity: any, options: any) {
    return this.request<GenerationResult>('generateCrud', { entity, options })
  }

  generateView(view: any) {
    return this.request<GenerationResult>('generateView', view)
  }

  generateMigration(migration: any) {
    return this.request<GenerationResult>('generateMigration', migration)
  }

  generateRole(role: any) {
    return this.request<GenerationResult>('generateRole', role)
  }

  generateBpm(entityName: string) {
    return this.request<GenerationResult>('generateBpm', { entityName })
  }

  getProjectConfig() {
    return this.request<ProjectConfig>('getProjectConfig')
  }
}

export const bridge = new Bridge()
