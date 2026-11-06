import axios from 'axios'

export const api = axios.create({
  baseURL: '/api'
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export function fileUrl(resource, id, kind) {
  const parameters = resource === 'video' ? { videoId: id } : { eventId: id, kind }
  return `/api/files?${new URLSearchParams(parameters)}`
}
