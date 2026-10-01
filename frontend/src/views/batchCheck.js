/**
 * 批量核对的纯逻辑：与服务端同一套切分/规范化规则，页面与自动化测试共用。
 *
 * 关键约定：
 *  - 用户粘贴的多行文本先按行保留（含空行），每行再按中英文逗号展开；
 *  - 展平后每个 token 的下标 index 即服务端返回行的 index，回填严格按下标对齐，不靠“猜顺序”；
 *  - textarea 结尾换行天然会多一个空行，只去掉这一个尾随空元素，其余空行全部保留；
 *  - 大清单在前端按 BATCH_CHUNK 拆请求，每批携带 indexOffset，服务端各自按本批 0..n 编号，
 *    合并时加回偏移，乱序返回也能回到正确输入行。
 */

/** 与后端一致：英文逗号、中文逗号都算分隔符 */
export const COMMA_RE = /[,，]/

/** 单次请求最多携带的原始行数（服务端硬上限 5000，前端留余量） */
export const MAX_LINES_PER_REQUEST = 1000

/** 行状态码（服务端判定，前端只做展示与重查筛选） */
export const ROW_STATUS = {
  FOUND: 'FOUND',
  NOT_FOUND: 'NOT_FOUND',
  BLANK: 'BLANK',
  INVALID: 'INVALID',
  DUPLICATE: 'DUPLICATE'
}

export const STATUS_NAME = {
  FOUND: '已命中',
  NOT_FOUND: '未找到',
  BLANK: '空行',
  INVALID: '格式错误',
  DUPLICATE: '重复输入'
}

/** 需要“重新查询失败行”的状态（命中/重复行/空行不重查：重复行跟随首次行，空行无编号） */
export const RETRYABLE = new Set([ROW_STATUS.NOT_FOUND, ROW_STATUS.INVALID])

/**
 * 把粘贴文本解析成「原始行」数组。
 * - 按 \r?\n 切分，行内空白原样保留交给服务端规范化；
 * - 只丢弃 textarea 末尾换行产生的那一个尾随空串，中间空行全部保留位置。
 */
export function parseRawLines(text) {
  if (text == null) return []
  const lines = String(text).split(/\r?\n/)
  if (lines.length > 1 && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}

/**
 * 在前端把行数组展平为 token 列表（index 与服务端 rows[].index 一致），用于：
 *  1) 大清单按 token 边界拆批；
 *  2) 重查失败行时按 index 回收原始 token。
 * 返回 [{ index, line, token }]
 */
export function flattenTokens(lines) {
  const out = []
  let index = 0
  for (const line of lines || []) {
    const safe = line == null ? '' : String(line)
    for (const part of safe.split(COMMA_RE)) {
      out.push({ index: index++, line: safe, token: part })
    }
  }
  return out
}

/** 与服务端一致的行数组展平（只要 token 文本） */
export function splitLinesToTokens(lines) {
  return flattenTokens(lines).map((t) => t.token)
}

/**
 * 把输入切成多个请求块：先按行展开成 token（每行还可能含逗号），再按 token 边界切片，
 * 因此即使一行里用逗号粘了 2300 个编号也会被拆开，不会形成巨型请求。
 * 每批把 token 当作「每元素一个编号」的行数组发给服务端（服务端对无逗号的行等价直传）。
 * 返回 [{ lines, indexOffset, tokenCount }]
 */
export function chunkLines(lines, maxTokens = MAX_LINES_PER_REQUEST) {
  const tokens = flattenTokens(lines).map((t) => t.token)
  if (tokens.length === 0) return []
  const chunks = []
  for (let from = 0; from < tokens.length; from += maxTokens) {
    const piece = tokens.slice(from, from + maxTokens)
    chunks.push({ lines: piece, indexOffset: from, tokenCount: piece.length })
  }
  return chunks
}

/**
 * 合并多个分批响应：按 indexOffset 平移每批的行下标后，严格按 index 排序输出。
 * 分批请求即使乱序/晚到，也不影响合并后的行位置（顺序由 index 决定，不由到达顺序决定）。
 */
export function mergeBatchResults(responses) {
  const collected = []
  const counters = { found: 0, notFound: 0, blank: 0, invalid: 0, duplicate: 0 }
  for (const resp of responses) {
    if (!resp || !Array.isArray(resp.rows)) continue
    const offset = resp.indexOffset || 0
    for (const row of resp.rows) {
      collected.push({ ...row, index: offset + row.index })
    }
  }
  collected.sort((a, b) => a.index - b.index)
  for (const row of collected) {
    if (row.status === ROW_STATUS.FOUND) counters.found++
    else if (row.status === ROW_STATUS.NOT_FOUND) counters.notFound++
    else if (row.status === ROW_STATUS.BLANK) counters.blank++
    else if (row.status === ROW_STATUS.INVALID) counters.invalid++
    else if (row.status === ROW_STATUS.DUPLICATE) counters.duplicate++
  }
  return {
    type: 'instrument-batch-result/v1',
    total: collected.length,
    foundCount: counters.found,
    notFoundCount: counters.notFound,
    blankCount: counters.blank,
    invalidCount: counters.invalid,
    duplicateCount: counters.duplicate,
    rows: collected
  }
}

/** 失败行（未找到/格式错误）去重后取原始 token，作为“只重新查询失败行”的请求体。 */
export function buildRetryLines(rows) {
  const seen = new Set()
  const lines = []
  for (const row of rows || []) {
    if (!RETRYABLE.has(row.status)) continue
    const raw = row.rawInput == null ? '' : String(row.rawInput)
    if (seen.has(raw)) continue
    seen.add(raw)
    lines.push(raw)
  }
  return lines
}
