import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listFlashSales } from '../../api/flashSaleApi'

export function FlashSaleListPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['flash-sales'], queryFn: listFlashSales })

  if (isLoading) return <div>Loading flash sales…</div>
  if (isError) return <div role="alert">Failed to load flash sales.</div>

  return (
    <>
      <nav>
        <Link to="/orders">我的訂單</Link>
      </nav>
      <ul>
        {data!.map((sale) => (
          <li key={sale.id}>
            <Link to={`/flash-sales/${sale.id}`}>{sale.productName}</Link>
            <span> ${sale.salePrice.toFixed(2)}</span>
            <span> {sale.status}</span>
          </li>
        ))}
      </ul>
    </>
  )
}
