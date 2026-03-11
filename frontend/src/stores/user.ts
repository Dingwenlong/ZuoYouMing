import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { type LoginParams, type RegisterParams, type UserInfo, login, register, guestLogin, wechatLogin, logout } from '../api/auth'
import { getMenus, type MenuItem } from '../api/menu'
import { getActiveReservation } from '../api/reservation'

interface ReservationState {
  id: number
  seatId: number
  seatNo: string
  slot?: string
  startTime: number
  deadline: number | null
  status: 'reserved' | 'checked_in' | 'away'
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  // 从 localStorage 恢复 userInfo，防止刷新丢失
  const userInfo = ref<UserInfo | null>(
    localStorage.getItem('userInfo') 
      ? JSON.parse(localStorage.getItem('userInfo')!) 
      : null
  )
  const menus = ref<MenuItem[]>([])
  const isSessionInitialized = ref(false)
  let sessionInitPromise: Promise<void> | null = null
  
  // 是否绑定了个人信息
  const isInfoBound = computed(() => {
    if (!userInfo.value) return true // 未登录时不触发重定向
    if (userInfo.value.role === 'admin' || userInfo.value.role === 'guest') return true
    return !!(userInfo.value.phone && userInfo.value.realName)
  })

  // 新用户标记：如果未绑定信息，则视为新用户/需要绑定用户
  const isNewUser = computed(() => !isInfoBound.value)

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem('token', newToken)
  }

  function setUserInfo(info: UserInfo) {
    userInfo.value = info
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  // 预约状态管理
  const reservation = ref<ReservationState | null>(
    localStorage.getItem('reservation') 
      ? JSON.parse(localStorage.getItem('reservation')!) 
      : null
  )

  function persistReservation(data: ReservationState | null) {
    reservation.value = data
    if (data) {
      localStorage.setItem('reservation', JSON.stringify(data))
    } else {
      localStorage.removeItem('reservation')
    }
  }

  function resetSessionInitialization() {
    isSessionInitialized.value = false
    sessionInitPromise = null
  }

  function setReservation(
    id: number,
    seatId: number,
    seatNo: string,
    startTime: number,
    deadline: number | null,
    status: ReservationState['status'] = 'reserved',
    slot?: string
  ) {
    const data = { id, seatId, seatNo, startTime, deadline, status, slot }
    reservation.value = data
    persistReservation(data)
  }

  function setAway() {
    if (!reservation.value) return
    const deadline = Date.now() + 30 * 60 * 1000 // 30分钟暂离时间
    const data = { ...reservation.value, deadline, status: 'away' as const }
    persistReservation(data)
  }

  function checkIn() {
    if (!reservation.value) return
    const data = { ...reservation.value, deadline: null, status: 'checked_in' as const }
    persistReservation(data)
  }

  function clearReservation() {
    persistReservation(null)
  }

  function clearToken() {
    token.value = ''
    userInfo.value = null
    menus.value = []
    resetSessionInitialization()
    clearReservation() // 登出时清除预约
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  async function handleLogin(params: LoginParams) {
    try {
      const res = await login(params)
      
      setToken(res.token)
      setUserInfo(res.userInfo)
      resetSessionInitialization()
      
      return true
    } catch (error) {
      console.error(error)
      return false
    }
  }

  async function handleRegister(params: RegisterParams) {
    try {
      const res = await register(params)
      
      setToken(res.token)
      setUserInfo(res.userInfo)
      resetSessionInitialization()
      
      return true
    } catch (error) {
      console.error(error)
      return false
    }
  }

  async function handleGuestLogin() {
    try {
      const res = await guestLogin()
      
      setToken(res.token)
      setUserInfo(res.userInfo)
      resetSessionInitialization()
      
      return true
    } catch (error) {
      console.error(error)
      return false
    }
  }

  // 微信登录模拟
  async function handleWeChatLogin(code: string) {
    try {
      const res = await wechatLogin(code)
      
      setToken(res.token)
      setUserInfo(res.userInfo)
      resetSessionInitialization()
      
      return true
    } catch (error) {
      return false
    }
  }

  // 完成绑定后更新用户信息
  function completeBinding(updatedInfo?: Partial<UserInfo>) {
    if (userInfo.value && updatedInfo) {
      setUserInfo({ ...userInfo.value, ...updatedInfo })
    }
  }

  async function handleLogout() {
    try {
      await logout()
    } finally {
      clearToken()
    }
  }

  async function fetchMenus() {
    if (!userInfo.value?.role) return
    try {
      const res = await getMenus(userInfo.value.role)
      menus.value = res
    } catch (error) {
      console.error('Failed to fetch menus', error)
    }
  }

  async function initializeSession() {
    if (!token.value || !userInfo.value) {
      resetSessionInitialization()
      return
    }

    if (isSessionInitialized.value) {
      return
    }

    if (sessionInitPromise) {
      return sessionInitPromise
    }

    sessionInitPromise = (async () => {
      try {
        if (menus.value.length === 0) {
          await fetchMenus()
        }

        if (userInfo.value?.role === 'guest') {
          clearReservation()
        } else {
          await syncReservationStatus()
        }
      } finally {
        isSessionInitialized.value = true
        sessionInitPromise = null
      }
    })()

    return sessionInitPromise
  }

  async function syncReservationStatus() {
    if (!token.value || !userInfo.value || userInfo.value.role === 'guest') {
      clearReservation()
      return
    }

    try {
      const activeRes = await getActiveReservation()
      
      if (activeRes) {
        // 同步后端状态到本地
        const deadline = activeRes.deadline ? new Date(activeRes.deadline).getTime() : null
        const startTime = activeRes.startTime ? new Date(activeRes.startTime).getTime() : Date.now()
        setReservation(
          activeRes.id,
          activeRes.seatId,
          activeRes.seatNo || '',
          startTime,
          deadline,
          activeRes.status as ReservationState['status'],
          activeRes.slot
        )
      } else {
        // 如果后端没有活跃预约，清理本地缓存
        clearReservation()
      }
    } catch (e) {
      console.error('Failed to sync reservation status:', e)
    }
  }

  return {
    token,
    userInfo,
    menus,
    isNewUser,
    reservation,
    setReservation,
    setAway,
    checkIn,
    clearReservation,
    clearToken,
    handleLogin,
    handleRegister,
    handleGuestLogin,
    handleWeChatLogin,
    completeBinding,
    handleLogout,
    fetchMenus,
    initializeSession,
    syncReservationStatus
  }
})
