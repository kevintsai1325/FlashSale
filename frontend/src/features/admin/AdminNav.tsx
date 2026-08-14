import { NavLink } from 'react-router-dom'
import './AdminNav.css'

function navLinkClassName({ isActive }: { isActive: boolean }) {
  return isActive ? 'admin-nav-link admin-nav-link-active' : 'admin-nav-link'
}

export function AdminNav() {
  return (
    <nav className="admin-nav">
      <span className="admin-nav-wordmark">後台管理</span>
      <div className="admin-nav-links">
        <NavLink to="/admin" end className={navLinkClassName}>
          儀表板
        </NavLink>
        <NavLink to="/admin/api-logs" className={navLinkClassName}>
          API 紀錄
        </NavLink>
        <NavLink to="/admin/orders" className={navLinkClassName}>
          訂單查詢
        </NavLink>
        <NavLink to="/admin/notifications" className={navLinkClassName}>
          通知中心
        </NavLink>
      </div>
    </nav>
  )
}
