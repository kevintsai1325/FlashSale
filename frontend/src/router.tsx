import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'
import { FlashSaleDetailPage } from './features/flash-sales/FlashSaleDetailPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { LoginPage } from './features/auth/LoginPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { PurchaseStatusPage } from './features/purchase/PurchaseStatusPage'
import { MyOrdersPage } from './features/orders/MyOrdersPage'
import { OrderDetailPage } from './features/orders/OrderDetailPage'
import { RequireAdmin } from './features/admin/RequireAdmin'
import { AdminDashboardPage } from './features/admin/AdminDashboardPage'
import { ApiLogsPage } from './features/admin/ApiLogsPage'
import { AdminOrdersPage } from './features/admin/AdminOrdersPage'
import { AdminOrderDetailPage } from './features/admin/AdminOrderDetailPage'
import { AdminProductsPage } from './features/admin/AdminProductsPage'
import { AdminFlashSalesPage } from './features/admin/AdminFlashSalesPage'
import { AdminNotificationsPage } from './features/admin/AdminNotificationsPage'
import { AdminNotificationDetailPage } from './features/admin/AdminNotificationDetailPage'

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
  {
    element: <RequireAdmin />,
    children: [
      { path: '/admin', element: <AdminDashboardPage /> },
      { path: '/admin/api-logs', element: <ApiLogsPage /> },
      { path: '/admin/orders', element: <AdminOrdersPage /> },
      { path: '/admin/products', element: <AdminProductsPage /> },
      { path: '/admin/flash-sales', element: <AdminFlashSalesPage /> },
      { path: '/admin/orders/:orderId', element: <AdminOrderDetailPage /> },
      { path: '/admin/notifications', element: <AdminNotificationsPage /> },
      { path: '/admin/notifications/:id', element: <AdminNotificationDetailPage /> },
    ],
  },
])
