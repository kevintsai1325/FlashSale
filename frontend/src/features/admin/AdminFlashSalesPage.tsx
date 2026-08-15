import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  listAdminFlashSales,
  listProducts,
  createFlashSale,
  type AdminFlashSaleSummary,
} from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminFlashSalesPage.css'

export function AdminFlashSalesPage() {
  const queryClient = useQueryClient()
  const [productId, setProductId] = useState('')
  const [salePrice, setSalePrice] = useState('')
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [purchaseLimitPerUser, setPurchaseLimitPerUser] = useState('1')
  const [totalQuantity, setTotalQuantity] = useState('')

  const salesQuery = useQuery({
    queryKey: ['admin', 'flash-sales'],
    queryFn: listAdminFlashSales,
  })

  const productsQuery = useQuery({
    queryKey: ['admin', 'products'],
    queryFn: listProducts,
  })

  const mutation = useMutation({
    mutationFn: () => createFlashSale({
      productId: Number(productId),
      salePrice: Number(salePrice),
      // The <input type="datetime-local"> value has no timezone info and is interpreted as
      // local wall-clock time — new Date(...) parses it the same way, so .toISOString()
      // correctly converts it to the UTC instant string the backend's `Instant` expects.
      startsAt: new Date(startsAt).toISOString(),
      endsAt: new Date(endsAt).toISOString(),
      purchaseLimitPerUser: Number(purchaseLimitPerUser),
      totalQuantity: Number(totalQuantity),
    }),
    onSuccess: () => {
      setSalePrice('')
      setStartsAt('')
      setEndsAt('')
      setTotalQuantity('')
      queryClient.invalidateQueries({ queryKey: ['admin', 'flash-sales'] })
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  const products = productsQuery.data ?? []

  return (
    <>
      <AdminNav />
      <div className="admin-flash-sales-page">
        {products.length === 0 && !productsQuery.isLoading && (
          <p role="alert">請先建立商品</p>
        )}

        <form className="admin-form" onSubmit={handleSubmit}>
          <label htmlFor="flash-sale-product">
            商品
            <select id="flash-sale-product" value={productId} onChange={(e) => setProductId(e.target.value)} required>
              <option value="" disabled>請選擇商品</option>
              {products.map((product) => (
                <option key={product.id} value={product.id}>{product.name}</option>
              ))}
            </select>
          </label>
          <label htmlFor="flash-sale-price">
            售價
            <input id="flash-sale-price" type="number" step="0.01" value={salePrice} onChange={(e) => setSalePrice(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-starts">
            開始時間
            <input id="flash-sale-starts" type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-ends">
            結束時間
            <input id="flash-sale-ends" type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-limit">
            每人限購
            <input id="flash-sale-limit" type="number" min="1" value={purchaseLimitPerUser} onChange={(e) => setPurchaseLimitPerUser(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-quantity">
            庫存數量
            <input id="flash-sale-quantity" type="number" min="1" value={totalQuantity} onChange={(e) => setTotalQuantity(e.target.value)} required />
          </label>
          <button type="submit" className="btn btn-primary" disabled={products.length === 0}>新增搶購活動</button>
          {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
        </form>

        {salesQuery.isLoading ? (
          <div>Loading…</div>
        ) : salesQuery.isError || !salesQuery.data ? (
          <div role="alert">Failed to load flash sales.</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>售價</th>
                <th>開始</th>
                <th>結束</th>
                <th>狀態</th>
              </tr>
            </thead>
            <tbody>
              {salesQuery.data.length === 0 ? (
                <tr><td colSpan={5} className="admin-table-empty">尚無搶購活動</td></tr>
              ) : (
                salesQuery.data.map((sale: AdminFlashSaleSummary) => (
                  <tr key={sale.id}>
                    <td>{sale.productName}</td>
                    <td>${sale.salePrice.toFixed(2)}</td>
                    <td>{new Date(sale.startsAt).toLocaleString()}</td>
                    <td>{new Date(sale.endsAt).toLocaleString()}</td>
                    <td>{sale.status}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
