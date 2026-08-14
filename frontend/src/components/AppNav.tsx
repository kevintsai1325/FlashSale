import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/useAuth'
import './AppNav.css'

export function AppNav() {
  const { isAuthenticated, logout } = useAuth()
  return (
    <nav className="app-nav">
      <span className="wordmark">FLASH SALE</span>
      <div className="nav-links">
        <Link className="nav-link" to="/orders">我的訂單</Link>
        {isAuthenticated && <button className="nav-logout" onClick={() => logout()}>登出</button>}
      </div>
    </nav>
  )
}
