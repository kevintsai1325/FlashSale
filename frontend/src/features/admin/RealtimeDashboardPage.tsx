import { useEffect, useState } from 'react'
import { subscribeRealtimeMetrics, type GmvMetric, type TopProductsMetric } from '../../api/realtimeApi'
import { AdminNav } from './AdminNav'
import './RealtimeDashboardPage.css'

const HISTORY_SECONDS = 60

interface GmvPoint {
  windowEnd: number
  amount: number
  orderCount: number
}

export function RealtimeDashboardPage() {
  const [history, setHistory] = useState<GmvPoint[]>([])
  const [topProducts, setTopProducts] = useState<TopProductsMetric | null>(null)
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    setConnected(true)
    void subscribeRealtimeMetrics((metric) => {
      if (metric.type === 'gmv') {
        setHistory((previous) => mergeGmv(previous, metric))
      } else {
        // 遲到的事件會讓 Flink 重算同一個視窗並再送一次，所以這裡是覆蓋不是累加。
        setTopProducts((previous) =>
          previous && previous.windowEnd > metric.windowEnd ? previous : metric)
      }
    }, controller.signal).finally(() => setConnected(false))
    return () => controller.abort()
  }, [])

  const latest = history.length > 0 ? history[history.length - 1] : null
  const windowTotal = history.reduce((sum, point) => sum + point.amount, 0)
  const windowOrders = history.reduce((sum, point) => sum + point.orderCount, 0)
  const peak = history.reduce((max, point) => Math.max(max, point.amount), 0)

  return (
    <>
      <AdminNav />
      <main className="realtime-page">
        <header className="realtime-header">
          <h1>即時大屏</h1>
          <span className={connected ? 'realtime-live' : 'realtime-offline'}>
            {connected ? '連線中' : '已中斷，重試中…'}
          </span>
        </header>

        <section className="realtime-tiles">
          <article className="realtime-tile">
            <h2>本秒 GMV</h2>
            <p className="realtime-value">{formatAmount(latest?.amount ?? 0)}</p>
          </article>
          <article className="realtime-tile">
            <h2>本秒訂單數</h2>
            <p className="realtime-value">{latest?.orderCount ?? 0}</p>
          </article>
          <article className="realtime-tile">
            <h2>近 {HISTORY_SECONDS} 秒 GMV</h2>
            <p className="realtime-value">{formatAmount(windowTotal)}</p>
          </article>
          <article className="realtime-tile">
            <h2>近 {HISTORY_SECONDS} 秒訂單數</h2>
            <p className="realtime-value">{windowOrders}</p>
          </article>
        </section>

        <section className="realtime-chart" aria-label="每秒 GMV 走勢">
          {history.length === 0 ? (
            <p className="realtime-empty">等待第一個視窗的結果…</p>
          ) : (
            <ol className="realtime-bars">
              {history.map((point) => (
                <li key={point.windowEnd}
                    title={`${new Date(point.windowEnd).toLocaleTimeString()} — ${formatAmount(point.amount)}`}>
                  <span className="realtime-bar"
                        style={{ height: `${peak === 0 ? 0 : (point.amount / peak) * 100}%` }} />
                </li>
              ))}
            </ol>
          )}
        </section>

        <section className="realtime-top">
          <h2>熱門商品（近 10 秒）</h2>
          {!topProducts || topProducts.items.length === 0 ? (
            <p className="realtime-empty">尚無成交。</p>
          ) : (
            <ol className="realtime-top-list">
              {topProducts.items.map((item) => (
                <li key={item.productId}>
                  <span className="realtime-top-name">{item.productName}</span>
                  <span className="realtime-top-qty">{item.quantity}</span>
                </li>
              ))}
            </ol>
          )}
        </section>
      </main>
    </>
  )
}

/**
 * 以 windowEnd 為鍵覆蓋，然後只留最近 HISTORY_SECONDS 筆。
 *
 * 覆蓋而不是 append：Flink 的視窗允許遲到 10 秒，遲到的事件會讓同一個 windowEnd
 * 重新計算並再送一次。append 會讓那一秒在圖上出現兩根柱子，而且總和算兩次。
 */
function mergeGmv(previous: GmvPoint[], metric: GmvMetric): GmvPoint[] {
  const next = previous.filter((point) => point.windowEnd !== metric.windowEnd)
  next.push({ windowEnd: metric.windowEnd, amount: metric.amount, orderCount: metric.orderCount })
  next.sort((a, b) => a.windowEnd - b.windowEnd)
  return next.slice(-HISTORY_SECONDS)
}

/**
 * 自己拼幣別符號，不用 Intl 的 currency 樣式：不同執行環境的 ICU 資料不一樣，
 * Node 會把 TWD 印成「$」而瀏覽器印成「NT$」。大屏的數字不該隨執行環境改變長相，
 * 而測試更不該因為這種差異而變成偶發失敗。
 */
function formatAmount(value: number) {
  return `NT$${new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)}`
}
