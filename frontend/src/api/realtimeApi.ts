import { getAccessToken } from './httpClient'

export interface GmvMetric {
  type: 'gmv'
  windowEnd: number
  amount: number
  orderCount: number
}

export interface TopProductsMetric {
  type: 'topProducts'
  windowEnd: number
  items: { productId: number; productName: string; quantity: number }[]
}

export type RealtimeMetric = GmvMetric | TopProductsMetric

/**
 * 訂閱即時大屏的事件流。
 *
 * **不用 EventSource**：它不能帶 Authorization 標頭，而這條端點需要 ADMIN。
 * 另一個選項是把 token 放進查詢字串，但那會讓它出現在 Nginx 的存取日誌裡。
 * 代價是自動重連要自己寫——下面那個 while 迴圈就是。
 */
export async function subscribeRealtimeMetrics(
  onMetric: (metric: RealtimeMetric) => void,
  signal: AbortSignal,
): Promise<void> {
  while (!signal.aborted) {
    try {
      const response = await fetch('/api/realtime/metrics', {
        headers: { Authorization: `Bearer ${getAccessToken() ?? ''}`, Accept: 'text/event-stream' },
        signal,
      })
      if (!response.ok || !response.body) {
        throw new Error(`realtime stream failed: ${response.status}`)
      }
      await readEventStream(response.body, onMetric)
    } catch {
      if (signal.aborted) return
      // 斷線是常態（部署、網路抖動）。等一秒再連，而不是把錯誤丟給使用者——
      // 大屏應該自己恢復。
      await new Promise((resolve) => setTimeout(resolve, 1000))
    }
  }
}

async function readEventStream(
  body: ReadableStream<Uint8Array>,
  onMetric: (metric: RealtimeMetric) => void,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    // SSE 以空行分隔事件。一定要等到空行才解析——一個事件可能跨多個 chunk 抵達。
    let separator = buffer.indexOf('\n\n')
    while (separator !== -1) {
      const rawEvent = buffer.slice(0, separator)
      buffer = buffer.slice(separator + 2)
      const data = rawEvent
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('')
      if (data) {
        try {
          onMetric(JSON.parse(data) as RealtimeMetric)
        } catch {
          // 壞掉的一筆不該讓整條連線斷掉。
        }
      }
      separator = buffer.indexOf('\n\n')
    }
  }
}
