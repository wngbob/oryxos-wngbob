/**
 * 管理台的唯一数据通道（契约 admin-console.md §4）。
 *
 * 只读纪律在这里落地成一条代码事实：本文件只导出 `get`，且方法名写死为 'GET'。
 * 将来谁想加写操作，必须先把这条约束推翻——而不是在某个页面里顺手写一个 fetch。
 *
 * 响应信封是 `{code, message, data, timestamp}`（裁决 ①），`code` 就是 HTTP 状态码：
 * 所以判定成败看信封本身，不只看 HTTP 200——`code` 非 200 时把 `message` 交给页面显示，
 * 这正是契约 §6 要求的「读不到就要说出来」，不是白屏。
 */

/** 对外接口的统一前缀。所有请求都必须落在它下面（不含主机名，同源部署）。 */
export const API_PREFIX = '/api/v1'

/**
 * 发起一次只读请求。
 *
 * @param {string} path 以 /api/v1 开头的路径
 * @param {Record<string, string>} [params] 查询参数
 * @returns {Promise<any>} 信封里的 data
 */
export async function get(path, params) {
  const query = params ? '?' + new URLSearchParams(params).toString() : ''
  let response
  try {
    response = await fetch(path + query, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
  } catch (cause) {
    // 网络层失败（服务没起、被防火墙拦）与「服务回了错误」是两件事，措辞要分开
    throw new Error('连接失败：' + (cause && cause.message ? cause.message : '无法访问服务'))
  }

  let body = null
  try {
    body = await response.json()
  } catch (cause) {
    body = null
  }

  if (!body || typeof body.code !== 'number') {
    throw new Error('响应不是约定的信封（HTTP ' + response.status + '）')
  }
  if (body.code !== 200) {
    throw new Error(body.message || '请求失败（HTTP ' + response.status + '）')
  }
  return body.data
}
