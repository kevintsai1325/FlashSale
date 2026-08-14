import './StatusPill.css'

const STATUS_MAP: Record<string, { tone: 'go' | 'wait' | 'stop'; label: string }> = {
  ACTIVE: { tone: 'go', label: '搶購中' },
  SCHEDULED: { tone: 'wait', label: '即將開賣' },
  ENDED: { tone: 'stop', label: '已結束' },
  PENDING: { tone: 'wait', label: '搶購處理中' },
  SUCCEEDED: { tone: 'go', label: '搶購成功' },
  SOLD_OUT: { tone: 'stop', label: '已售完' },
  REJECTED: { tone: 'stop', label: '已購買過' },
  FAILED: { tone: 'stop', label: '建單失敗' },
  PENDING_PAYMENT: { tone: 'wait', label: '待付款' },
  PAID: { tone: 'go', label: '已付款' },
  CANCELLED: { tone: 'stop', label: '已取消' },
  EXPIRED: { tone: 'stop', label: '已逾期' },
}

export function StatusPill({ status }: { status: string }) {
  const entry = STATUS_MAP[status] ?? { tone: 'stop' as const, label: status }
  return <span className={`pill ${entry.tone}`}>{entry.label}</span>
}
