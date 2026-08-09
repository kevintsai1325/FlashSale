import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { LoginPage } from './features/auth/LoginPage'

export const router = createBrowserRouter([
  { path: '/', element: <FlashSaleListPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/login', element: <LoginPage /> },
])
