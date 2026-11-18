const params = new URLSearchParams(location.hash.slice(1));
history.replaceState(null, '', location.pathname);
const status = document.getElementById('status');
const state = params.get('state');
const token = params.get('access_token');
if (state && token) {
  fetch('/auth/complete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state, token })
  }).then(async reply => {
    const result = await reply.json();
    status.textContent = result.completed ? '登录已完成，请返回 YaoXi Hub。' : result.error;
  }).catch(() => { status.textContent = '网络请求失败，请返回 App 重试。'; });
} else {
  status.textContent = '登录凭据缺失，请从 App 重新开始。';
}
