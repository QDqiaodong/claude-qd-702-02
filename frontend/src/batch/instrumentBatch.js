/**
 * 计量器具「批量核对」纯逻辑：
 * 解析（多行/逗号混合分隔，保留空行与重复位置）→ 分批（避免无限长请求体）→
 * 逐批查询 → 按原始位置合并。
 *
 * 这一文件不依赖 Vue / Element，单独可测；useBatchLookup 只是它在页面上的响应式壳。
 */

/** 单批最多行数：与服务端 BATCH_MAX_ROWS(500) 对齐，前端再保守一些，单批失败只重查一小段 */
export const CHUNK_SIZE = 200
/** 与服务端 BATCH_MAX_TEXT_LENGTH 对齐 */
export const MAX_TEXT_LENGTH = 16000

/**
 * 把粘贴文本拆成逐行原始输入。
 *
 * 分隔符：换行（\r\n / \r / \n）、半角逗号、全角逗号、中文顿号，可混用。
 * 每个分隔片段原样保留（不 trim），空片段 = 空行位置；
 * 仅当整段文本以分隔符结尾（典型：粘贴多行时末尾自带换行）时不额外生成一行空行。
 */
export function splitBatchText(text) {
  if (!text) return []
  const tokens = []
  let start = 0
  const isSep = (ch) => ch === '\n' || ch === '\r' || ch === ',' || ch === '，' || ch === '、'
  for (let i = 0; i < text.length; i++) {
    if (isSep(text.charAt(i))) {
      tokens.push(text.slice(start, i))
      // Windows 换行 \r\n 只算一个分隔符，不能在中间制造空行
      if (text.charAt(i) === '\r' && text.charAt(i + 1) === '\n') i++
      start = i + 1
    }
  }
  tokens.push(text.slice(start))
  if (tokens.length > 1 && tokens[tokens.length - 1] === '' && isSep(text.charAt(text.length - 1))) {
    tokens.pop()
  }
  return tokens
}

/** 把数组按 size 切成多段（段内顺序保留） */
export function chunkRows(rows, size = CHUNK_SIZE) {
  const chunks = []
  for (let from = 0; from < rows.length; from += size) {
    chunks.push(rows.slice(from, from + size))
  }
  return chunks
}

const ZERO_COUNTS = () => ({
  total: 0, foundCount: 0, notFoundCount: 0,
  emptyCount: 0, invalidCount: 0, duplicateCount: 0
})

/**
 * 顺序执行多批查询并按原始位置合并：
 * - 每批返回的行号是「批内 0 基」，这里换算成整单原始位置后落位；
 * - 行号缺失/越界/行数对不上直接抛错，宁肯不显示也不允许错位；
 * - 前几批已成功的数据保留，抛错由调用方提示后用户可只重试失败行。
 *
 * @param rawInputs 逐行原始输入（含空行）
 * @param request   async (chunkRows) => 服务端 batch-lookup 响应
 * @returns {{rows: Array, counts: object}} rows 长度恒等于 rawInputs.length，位置一一对应
 */
export async function runBatchedLookup(rawInputs, request, size = CHUNK_SIZE) {
  const merged = new Array(rawInputs.length)
  const counts = ZERO_COUNTS()
  counts.total = rawInputs.length
  let offset = 0
  for (const chunk of chunkRows(rawInputs, size)) {
    const resp = await request(chunk)
    if (!resp || !Array.isArray(resp.rows)) {
      throw new Error('批量核对返回结构无法识别，已中止合并以免错位')
    }
    if (resp.rows.length !== chunk.length) {
      throw new Error('批量核对返回行数与请求不一致，已中止合并以免错位')
    }
    for (const row of resp.rows) {
      if (!Number.isInteger(row.index) || row.index < 0 || row.index >= chunk.length) {
        throw new Error('批量核对返回行号越界，已中止合并以免错位')
      }
      // 批内序号 → 整单位置；同一位置不应被覆盖两次
      const globalIndex = offset + row.index
      if (merged[globalIndex] !== undefined) {
        throw new Error('批量核对返回出现重复行号，已中止合并以免错位')
      }
      merged[globalIndex] = { ...row, index: globalIndex }
    }
    counts.foundCount += resp.foundCount || 0
    counts.notFoundCount += resp.notFoundCount || 0
    counts.emptyCount += resp.emptyCount || 0
    counts.invalidCount += resp.invalidCount || 0
    counts.duplicateCount += resp.duplicateCount || 0
    offset += chunk.length
  }
  return { rows: merged, counts }
}

/** 可重试行：未找到 / 格式错误（空行无需重查，重复行跟随首次行） */
export function isRetryableRow(row) {
  return !!row && (row.status === 'NOT_FOUND' || row.status === 'INVALID')
}
