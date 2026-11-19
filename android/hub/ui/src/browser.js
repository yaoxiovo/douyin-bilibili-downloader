import { createApp, h, ref } from 'vue'
import { Button } from 'fuxsto-design-pro/button'
import 'fuxsto-design-pro/styles.css'
import './theme.css'
import { request } from './bridge.js'

createApp({
  setup() {
    const host = ref('')
    const update = () => request('address').then(value => { host.value = value.host }).catch(() => {})
    window.addEventListener('hub-browser-address', update)
    update()
    const button = (label, description, method) =>
      h(Button, { label, 'aria-label': description, variant: 'ghost', appear: false, onClick: () => request(method).catch(() => {}) })
    return () => h('header', { class: 'browser-toolbar' }, [
      button('‹', '返回网页', 'back'),
      h('strong', host.value),
      button('↻', '刷新网页', 'refresh'),
      button('×', '关闭浏览页', 'close'),
    ])
  },
}).mount('#app')
