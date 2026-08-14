import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'
import { FlashSaleDetailPage } from './features/flash-sales/FlashSaleDetailPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { LoginPage } from './features/auth/LoginPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { PurchaseStatusPage } from './features/purchase/PurchaseStatusPage'
import { MyOrdersPage } from './features/orders/MyOrdersPage'
import { OrderDetailPage } from './features/orders/OrderDetailPage'

export const router = createBrowserRouter([
  { path: '/flash-sales/:id', element: <FlashSaleDetailPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      { path: '/', element: <FlashSaleListPage /> },
      { path: '/purchase-requests/:requestId', element: <PurchaseStatusPage /> },
      { path: '/orders', element: <MyOrdersPage /> },
      { path: '/orders/:orderId', element: <OrderDetailPage /> },
    ],
  },
])
