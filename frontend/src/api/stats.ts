import request from '../utils/request'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

export function getDashboardStats() {
  if (USE_MOCK) {
    const dates = Array.from({ length: 7 }, (_item, index) => `2026-03-${String(index + 1).padStart(2, '0')}`)
    return Promise.resolve({
      totalSeats: 120,
      available: 45,
      occupied: 75,
      maintenance: 12,
      trend: {
        dates,
        counts: [120, 132, 101, 134, 90, 230, 210]
      }
    })
  }
  return request({
    url: '/stats/dashboard',
    method: 'get'
  })
}

export function getHeatmapData(date?: string, simulate: boolean = true) {
  if (USE_MOCK) {
    const hours = ['8:00', '10:00', '12:00', '14:00', '16:00', '18:00', '20:00', '22:00']
    const areas = ['A区', 'B区', 'C区', 'D区', 'E区']
    const data = areas.map((_area, i) => {
      return hours.map((_hour, j) => {
        return [i, j, Math.floor(Math.random() * 100)]
      })
    }).flat()
    return Promise.resolve({ data, areas })
  }
  return request({
    url: '/stats/heatmap',
    method: 'get',
    params: { date, simulate }
  })
}

export function getCongestionData(date?: string, simulate: boolean = true) {
  return request({
    url: '/stats/congestion',
    method: 'get',
    params: { date, simulate }
  })
}

export function getViolationTrend() {
  return request({
    url: '/stats/violation-trend',
    method: 'get'
  })
}
