import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getFlashSale } from '../../api/flashSaleApi'

export function FlashSaleDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['flash-sales', id],
    queryFn: () => getFlashSale(Number(id)),
    enabled: !!id,
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load flash sale.</div>

  return (
    <article>
      <h2>{data.productName}</h2>
      <p>{data.productDescription}</p>
      <p>Price: ${data.salePrice.toFixed(2)}</p>
      <p>Status: {data.status}</p>
      <p>Ends at: {new Date(data.endsAt).toLocaleString()}</p>
    </article>
  )
}
