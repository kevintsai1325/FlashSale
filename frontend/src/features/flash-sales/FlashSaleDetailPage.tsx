import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getFlashSale } from '../../api/flashSaleApi'
import { createPurchaseRequest } from '../../api/purchaseApi'
import { useAuth } from '../auth/useAuth'

export function FlashSaleDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { isAuthenticated } = useAuth()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['flash-sales', id],
    queryFn: () => getFlashSale(Number(id)),
    enabled: !!id,
  })

  const mutation = useMutation({
    mutationFn: () => createPurchaseRequest(Number(id), crypto.randomUUID()),
    onSuccess: (result) => navigate(`/purchase-requests/${result.requestId}`),
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load flash sale.</div>

  const handlePurchase = () => {
    if (!isAuthenticated) {
      navigate('/login', { state: { from: `/flash-sales/${id}` } })
      return
    }
    mutation.mutate()
  }

  return (
    <article>
      <h2>{data.productName}</h2>
      <p>{data.productDescription}</p>
      <p>Price: ${data.salePrice.toFixed(2)}</p>
      <p>Status: {data.status}</p>
      <p>Ends at: {new Date(data.endsAt).toLocaleString()}</p>
      <button onClick={handlePurchase} disabled={data.status !== 'ACTIVE' || mutation.isPending}>
        搶購
      </button>
      {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
    </article>
  )
}
