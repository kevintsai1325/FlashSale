import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listMyOrders } from '../../api/orderApi'
import { AppNav } from '../../components/AppNav'
import { StatusPill } from '../../components/StatusPill'
import './MyOrdersPage.css'

export function MyOrdersPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['orders', 'me'], queryFn: listMyOrders })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load orders.</div>

  return (
    <>
      <AppNav />
      {data.length === 0 ? (
        <div className="orders-empty">
          <div className="ticket-icon" aria-hidden="true" />
          <p>尚無訂單</p>
        </div>
      ) : (
        <ul className="order-grid">
          {data.map((order) => (
            <li key={order.id} className="order-card">
              <Link className="order-card-link" to={`/orders/${order.id}`}>
                <span className="order-card-no">{order.orderNo}</span>
                <StatusPill status={order.status} />
                <span className="order-card-amount">${order.totalAmount.toFixed(2)}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
