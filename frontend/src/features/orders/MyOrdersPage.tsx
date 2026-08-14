import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listMyOrders } from '../../api/orderApi'

export function MyOrdersPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['orders', 'me'], queryFn: listMyOrders })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load orders.</div>
  if (data.length === 0) return <p>尚無訂單</p>

  return (
    <ul>
      {data.map((order) => (
        <li key={order.id}>
          <Link to={`/orders/${order.id}`}>
            {order.orderNo} — {order.status} — ${order.totalAmount.toFixed(2)}
          </Link>
        </li>
      ))}
    </ul>
  )
}
