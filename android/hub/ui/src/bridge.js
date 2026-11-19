let sequence = 0
const pending = new Map()

if (window.YaoxiNative) {
  window.YaoxiNative.onmessage = ({ data }) => {
    const message = JSON.parse(data)
    const request = pending.get(message.id)
    if (!request) return
    pending.delete(message.id)
    clearTimeout(request.timer)
    if (message.error) request.reject(new Error(message.error))
    else request.resolve(message.value)
  }
}

export function request(method, payload = {}) {
  return new Promise((resolve, reject) => {
    if (!window.YaoxiNative) {
      reject(new Error('需要在 Yaoxi Hub 中打开。'))
      return
    }
    const id = ++sequence
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error('请求超时，请重试。'))
    }, 50000)
    pending.set(id, { resolve, reject, timer })
    window.YaoxiNative.postMessage(JSON.stringify({ id, method, payload }))
  })
}
