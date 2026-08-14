import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../features/auth/useAuth'
import { getUnreadCount } from '../api/adminApi'
import './AppNav.css'

export function AppNav() {
  const { isAuthenticated, role, logout } = useAuth()

  const { data: unreadCount } = useQuery({
    queryKey: ['admin', 'notifications', 'unread-count'],
    queryFn: getUnreadCount,
    enabled: role === 'ADMIN',
    refetchInterval: 30000,
  })

  return (
    <nav className="app-nav">
      <span className="wordmark">FLASH SALE</span>
      <div className="nav-links">
        <Link className="nav-link" to="/orders">我的訂單</Link>
        {role === 'ADMIN' && (
          <Link to="/admin/notifications" className="nav-bell" aria-label="通知中心">
            🔔
            {(unreadCount ?? 0) > 0 && (
              <span className="badge" data-testid="nav-bell-badge">{unreadCount}</span>
            )}
          </Link>
        )}
        {isAuthenticated && <button className="nav-logout" onClick={() => logout()}>登出</button>}
      </div>
    </nav>
  )
}
