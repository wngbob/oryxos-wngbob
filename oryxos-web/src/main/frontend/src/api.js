/**
 * 管理台的唯一数据通道（契约 admin-console.md §4）。
 *
 * 只读纪律在 2026-10-20（第 28 节）按课件开了一个**精确的口子**：定时任务页要「立即执行 / 启用停用」，
 * 所以本文件新增 `post` / `put` 两个写助手。可写的动作只有这两个、可写的路径只有
 * `/api/v1/schedules/**`——其余页面与其它资源仍然只读，谁想再开一个写口，必须先把这条约束推翻，
 * 而不是在某个页面里顺手写一个 fetch。`AdminConsoleSourceTest` 逐文件判卷这条边界。
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
  return unwrap(response)
}

/**
 * 发起一次写请求（第 28 节：只给定时任务页用）。
 *
 * @param {string} path 以 /api/v1 开头的路径
 * @param {any} [body] 请求体，序列化为 JSON
 * @returns {Promise<any>} 信封里的 data
 */
export async function post(path, body) {
  return send('POST', path, body)
}

/**
 * 发起一次写请求（第 28 节：只给定时任务页用）。
 *
 * @param {string} path 以 /api/v1 开头的路径
 * @param {any} [body] 请求体，序列化为 JSON
 * @returns {Promise<any>} 信封里的 data
 */
export async function put(path, body) {
  return send('PUT', path, body)
}

/**
 * POST / PUT 的共同实现。
 *
 * @param {string} method 'POST' 或 'PUT'
 * @param {string} path 以 /api/v1 开头的路径
 * @param {any} [body] 请求体
 * @returns {Promise<any>} 信封里的 data
 */
async function send(method, path, body) {
  const init = { method, headers: { Accept: 'application/json' } }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  let response
  try {
    response = await fetch(path, init)
  } catch (cause) {
    throw new Error('连接失败：' + (cause && cause.message ? cause.message : '无法访问服务'))
  }
  return unwrap(response)
}

/**
 * 拆统一信封：`code` 非 200 一律当失败，把 `message` 交给页面。
 *
 * @param {Response} response fetch 的响应
 * @returns {Promise<any>} 信封里的 data
 */
async function unwrap(response) {
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
