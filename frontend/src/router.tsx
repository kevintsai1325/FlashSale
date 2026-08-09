import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'
import { FlashSaleDetailPage } from './features/flash-sales/FlashSaleDetailPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { LoginPage } from './features/auth/LoginPage'

export const router = createBrowserRouter([
  { path: '/', element: <FlashSaleListPage /> },
  { path: '/flash-sales/:id', element: <FlashSaleDetailPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/login', element: <LoginPage /> },
])
