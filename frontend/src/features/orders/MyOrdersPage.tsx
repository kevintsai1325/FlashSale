import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listMyOrders } from '../../api/orderApi'
import { AppNav } from '../../components/AppNav'
import { StatusPill } from '../../components/StatusPill'
import './MyOrdersPage.css'

export function MyOrdersPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['orders', 'me'], queryFn: listMyOrders })

  if (isLoading) return <div>載入中…</div>
  if (isError || !data) return <div role="alert">無法載入訂單列表。</div>

  return (
    <>
      <AppNav />
      {data.length === 0 ? (
        <div className="orders-empty">
          <div className="ticket-icon" aria-hidden="true" />
          <p>尚無訂單</p>
          <p className="orders-empty-subtitle">搶購成功後會出現在這裡</p>
        </div>
      ) : (
        <ul className="order-grid">
          {data.map((order) => (
            <li key={order.id} className="order-card">
              <Link className="order-card-link" to={`/orders/${order.id}`}>
                <span className="order-card-no">{order.orderNo}</span>
                <StatusPill status={order.status} />
                <ul className="order-card-items" aria-label="訂購商品">
                  {order.items.map((item) => (
                    <li key={item.productId}>
                      <span>{item.productName}</span>
                      <span className="order-card-item-quantity">{item.quantity} × ${item.unitPrice.toFixed(2)}</span>
                    </li>
                  ))}
                </ul>
                <span className="order-card-amount">${order.totalAmount.toFixed(2)}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
