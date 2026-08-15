import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getFlashSale } from '../../api/flashSaleApi'
import { createPurchaseRequest } from '../../api/purchaseApi'
import { useAuth } from '../auth/useAuth'
import { AppNav } from '../../components/AppNav'
import { StatusPill } from '../../components/StatusPill'
import './FlashSaleDetailPage.css'

function formatCountdown(msRemaining: number): string {
  if (msRemaining <= 0) return '00:00:00'
  const totalSeconds = Math.floor(msRemaining / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  return [hours, minutes, seconds].map((n) => String(n).padStart(2, '0')).join(':')
}

function useCountdown(endsAt: string | undefined) {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!endsAt) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [endsAt])

  if (!endsAt) return null
  return formatCountdown(new Date(endsAt).getTime() - now)
}

export function FlashSaleDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { isAuthenticated } = useAuth()
  const [quantity, setQuantity] = useState('1')
  const { data, isLoading, isError } = useQuery({
    queryKey: ['flash-sales', id],
    queryFn: () => getFlashSale(Number(id)),
    enabled: !!id,
  })

  const countdown = useCountdown(data?.endsAt)

  const mutation = useMutation({
    mutationFn: () => createPurchaseRequest(Number(id), crypto.randomUUID(), Number(quantity)),
    onSuccess: (result) => navigate(`/purchase-requests/${result.requestId}`),
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load flash sale.</div>

  const quantityValue = Number(quantity)
  const isQuantityValid = Number.isInteger(quantityValue) && quantityValue > 0 && quantityValue <= data.purchaseLimitPerUser

  const handlePurchase = () => {
    if (!isAuthenticated) {
      navigate('/login', { state: { from: `/flash-sales/${id}` } })
      return
    }
    mutation.mutate()
  }

  return (
    <>
      <AppNav />
      <article className="detail-hero">
        <h2>{data.productName}</h2>
        <StatusPill status={data.status} />
        <p className="detail-description">{data.productDescription}</p>
        <p className="detail-price">${data.salePrice.toFixed(2)}</p>
        {data.totalQuantity > 0 && (
          <div className="stock-meter">
            <div className="stock-meter-row">
              <span>剩餘庫存</span>
              <span className="stock-meter-count">{data.availableQuantity} / {data.totalQuantity}</span>
            </div>
            <div className="stock-bar">
              <span style={{ width: `${(data.availableQuantity / data.totalQuantity) * 100}%` }} />
            </div>
          </div>
        )}
        {countdown && <p className="countdown-strip">倒數 {countdown}</p>}
        <p className="detail-limit">每人限購 {data.purchaseLimitPerUser} 件</p>
        <div className="quantity-field">
          <label htmlFor="purchase-quantity">數量</label>
          <input
            id="purchase-quantity"
            type="number"
            min="1"
            max={data.purchaseLimitPerUser}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
          />
        </div>
        <button
          className="btn btn-primary btn-block"
          onClick={handlePurchase}
          disabled={data.status !== 'ACTIVE' || mutation.isPending || !isQuantityValid}
        >
          搶購
        </button>
        {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
      </article>
    </>
  )
}
