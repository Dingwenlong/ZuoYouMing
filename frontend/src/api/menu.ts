// 本地菜单配置，根据用户角色过滤
export interface MenuItem {
  id: number
  parentId: number | null
  name: string
  path: string
  title?: string
  icon?: string
  roles?: string[]
  meta?: {
    title: string
    icon?: string
    keepAlive?: boolean
    requiresAuth?: boolean
    roles?: string[]
  }
  children?: MenuItem[]
}

// 本地菜单配置，根据用户角色过滤
const localMenus: MenuItem[] = [
  {
    id: 1,
    parentId: null,
    name: 'Dashboard',
    path: '/dashboard',
    meta: { title: '系统首页', icon: 'DashboardOutlined', roles: ['student', 'librarian', 'admin', 'guest'] }
  },
  {
    id: 2,
    parentId: null,
    name: 'Seat',
    path: '/seat',
    meta: { title: '座位预约', icon: 'DesktopOutlined', roles: ['student', 'librarian', 'admin', 'guest'] }
  },
  {
    id: 3,
    parentId: null,
    name: 'CheckIn',
    path: '/checkin',
    meta: { title: '座位签到', icon: 'EnvironmentOutlined', roles: ['student', 'librarian', 'admin'] }
  },
  {
    id: 4,
    parentId: null,
    name: 'Square',
    path: '/square',
    meta: { title: '消息广场', icon: 'CommentOutlined', roles: ['student', 'librarian', 'admin'] }
  },
  {
    id: 5,
    parentId: null,
    name: 'History',
    path: '/profile/history',
    meta: { title: '预约/签到记录', icon: 'HistoryOutlined', roles: ['student', 'librarian', 'admin'] }
  },
  {
    id: 6,
    parentId: null,
    name: 'Stats',
    path: '/stats',
    meta: { title: '数据统计', icon: 'BarChartOutlined', roles: ['librarian', 'admin'] }
  },
  {
    id: 7,
    parentId: null,
    name: 'System',
    path: '/system',
    meta: { title: '系统管理', icon: 'SettingOutlined', roles: ['admin', 'librarian'] },
    children: [
      {
        id: 8,
        parentId: 7,
        name: 'SystemUser',
        path: '/system/user',
        meta: { title: '用户管理', roles: ['admin'] }
      },
      {
        id: 9,
        parentId: 7,
        name: 'SystemSeat',
        path: '/system/seat',
        meta: { title: '座位管理', roles: ['admin', 'librarian'] }
      },
      {
        id: 10,
        parentId: 7,
        name: 'SystemLog',
        path: '/system/log',
        meta: { title: '系统日志', roles: ['admin'] }
      },
      {
        id: 11,
        parentId: 7,
        name: 'SystemConfig',
        path: '/system/config',
        meta: { title: '系统配置', roles: ['admin'] }
      }
    ]
  }
]

// 递归过滤菜单根据角色
const filterMenusByRole = (menus: MenuItem[], role: string): MenuItem[] => {
  return menus
    .filter(menu => {
      if (!menu.meta?.roles || menu.meta.roles.length === 0) return true
      return menu.meta.roles.includes(role)
    })
    .map(menu => {
      if (menu.children) {
        return { ...menu, children: filterMenusByRole(menu.children, role) }
      }
      return menu
    })
    .filter(menu => {
      // 如果原本有子菜单但过滤后没有了，则隐藏父级目录
      if (menu.children && menu.children.length === 0) {
        return false
      }
      return true
    })
}

// 获取用户菜单 - 前端本地鉴权
export const getMenus = async (role: string): Promise<MenuItem[]> => {
  // 前端本地根据角色过滤菜单
  return filterMenusByRole(localMenus, role)
}
