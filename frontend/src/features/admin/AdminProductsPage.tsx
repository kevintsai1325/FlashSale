import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listProducts, createProduct, type ProductView } from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminProductsPage.css'

export function AdminProductsPage() {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'products'],
    queryFn: listProducts,
  })

  const mutation = useMutation({
    mutationFn: () => createProduct(name, description),
    onSuccess: () => {
      setName('')
      setDescription('')
      queryClient.invalidateQueries({ queryKey: ['admin', 'products'] })
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <>
      <AdminNav />
      <div className="admin-products-page">
        <form className="admin-form" onSubmit={handleSubmit}>
          <label htmlFor="product-name">
            名稱
            <input id="product-name" value={name} onChange={(e) => setName(e.target.value)} required />
          </label>
          <label htmlFor="product-description">
            說明
            <input id="product-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <button type="submit" className="btn btn-primary">新增商品</button>
          {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
        </form>

        {isLoading ? (
          <div>載入中…</div>
        ) : isError || !data ? (
          <div role="alert">無法載入商品列表。</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th>名稱</th>
                <th>說明</th>
              </tr>
            </thead>
            <tbody>
              {data.length === 0 ? (
                <tr><td colSpan={2} className="admin-table-empty">尚無商品</td></tr>
              ) : (
                data.map((product: ProductView) => (
                  <tr key={product.id}>
                    <td>{product.name}</td>
                    <td>{product.description}</td>
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
