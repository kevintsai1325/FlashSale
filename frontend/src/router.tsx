import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'

export const router = createBrowserRouter([
  { path: '/', element: <FlashSaleListPage /> },
])
