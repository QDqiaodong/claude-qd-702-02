# 计量器具批量编号查询：逐行对位与状态化返回

## 修复的问题

旧 `GET /api/instruments/batch?instNos=...` 直接把 Hibernate
`byMultipleNaturalId(...).enableOrderedReturn(false)` 的结果集返回：

- 未命中的编号在结果里是 null，被服务端直接剔除，**后续器具整体前移**到错误的输入行；
- 关闭了有序返回，**返回顺序随数据库**（主键/执行计划）变化；
- 空行被吞掉、重复编号折叠，页面无法把结果对回输入位置；
- 入参是一条无限长的 GET 地址参数，清单一长就撞 URL/网关长度上限。

只在前端补空行、只给结果重排序、遇缺失整批失败都不能解决「按编号自身回填」这个根问题，
本次在服务端按输入位置重建结果。

## 新接口：`POST /api/instruments/batch-lookup`

请求体（二选一）：`{ "text": "整段粘贴文本" }` 或 `{ "instNos": ["逐行", "..."] }`。

- `text` 支持换行（`\n`/`\r\n`/`\r`）、半角逗号、全角逗号 `，`、顿号 `、` **混合分隔**；
  服务端统一拆分，每个分隔片段保留原始字符（不 trim），末尾自带换行不额外造空行。
- 限制：文本 ≤ 16 000 字符、≤ 500 行，超限返回 400 并提示分批——明确拒绝而不是静默截断。
- 返回 `InstrumentBatchView`：
  - `apiVersion: "batch-instruments-v2"`，`total/foundCount/notFoundCount/emptyCount/invalidCount/duplicateCount`；
  - `rows[]` **与输入等长、下标即原始位置**（另带 0 基 `index`），每行：
    `rawInput`（原始输入）、`normalized`（规范化编号）、`status/statusName/message`、
    `duplicateOfIndex`（重复时指向首次行）、`instrument`（命中时的完整台账视图）。
  - 状态：`FOUND` 已找到 / `NOT_FOUND` 未找到 / `EMPTY` 空行 / `INVALID` 格式错误
    （超过 32 位、含换行制表符等控制字符）。重复行不再单列状态：其最终状态随首次行
    （FOUND 或 NOT_FOUND），但 `duplicateOfIndex` 与说明文字保留，位置不压缩。

### 顺序、查重与查询效率

1. 规范化规则与现有单查一致：只去首尾空白；大小写等价与库规则一致
   （MySQL `utf8mb4_unicode_ci` 大小写不敏感），等价键 = trim + 小写折叠。
2. 同一请求内相同编号只查库一次（按等价键去重），首次行查完后把结果回填到每个重复位置；
   未找到的编号重复时每个位置仍逐行保留。
3. 去重后的编号按 100 个一块调 `byMultipleNaturalId`（不是逐条查），分块多批；
   回填**只按编号自身**，`enableOrderedReturn(false)` 返回乱序也不会错位。
4. 任何一行无效都不影响其他行，绝不整批失败。

## 旧接口兼容

`GET /api/instruments/batch` **保留**，返回结构不变（裸 `List<InstrumentView>`，
不静默换成 v2 包装），但修复了错位：按输入首次出现顺序稳定返回、重复折叠、未命中剔除
（旧语义本就如此）。响应带 `Deprecation: true` 与 `Warning` 头指明替代接口。
单编号查询 `GET /api/instruments/{instNo}` 与台账列表、到期清单均不受影响。

## 前端（计量器具台账页顶部「批量编号核对」面板）

- 粘贴多行或逗号混合分隔，行数/字符数实时提示；POST body 提交，不拼超长 URL。
- 结果表格按原始行逐行展示：行号、原始输入、规范化编号、状态徽标、说明、完整台账信息
  （编号/名称/型号/准确度/台账状态/客户/到期预警），失败行红底、空行灰底。
- NOT_FOUND / INVALID 行提供行内编辑框，「只重查失败行」只把这些行的当前输入重新提交，
  已成功行不参与请求，结果原地覆盖、列表仍不压缩。
- 长清单前端按 200 行/批自动拆分（`src/batch/instrumentBatch.js`），合并时校验
  行数一致、行号不越界不重复，任何异常宁肯中止合并也不允许错位。
- 请求乱序保护（`useBatchLookup.js` 单调序号）：较早发起的请求晚返回时结果直接丢弃，
  不会覆盖页面上更新的查询结果。

## 验证

- 后端集成测试 `InstrumentBatchLookupTest`（H2 + MockMvc）：首项缺失、中间缺失、
  连续重复（含大小写等价）、未找到编号重复、全部无效、混合分隔符、500 行分块/超限拒绝/
  超文本长度拒绝、数据库返回乱序（反向主键 + 打乱输入）、完整台账视图、
  旧 GET 接口结构与稳定顺序及废弃头、单查与列表回归。
- 前端 Vitest 22 个用例：分隔符解析（含 `\r\n`）、分批合并按位落位、响应乱序、
  行号越界/重复/行数不符拒绝合并、只重查失败行的请求载荷与原地回填、
  子批 `duplicateOfIndex` 全局换算、较早请求晚返回不覆盖新结果（可控延迟 Promise）、
  面板组件粘贴/就地改重查。

> 备注：本仓 `RawRecordVersioningTest` 有 4 个失败在本次改动**之前就存在**
> （在未改动的原始代码上运行同样失败：`submittedVersionIsLocked`、
> `roleSeparationEnforcedServerSide`、`confirmSupersedesOldVersion`、
> `voidedOrderRecordReadonly`，属记录留痕模块的角色/生命周期断言，与器具批量查询无关），
> 本次未修改该模块；其余 26 个后端测试（含新增 10 个）全部通过。
> 测试库 H2 连接加了 `COLLATION=ENGLISH STRENGTH PRIMARY`，仅为对齐生产
> MySQL `utf8mb4_unicode_ci` 的大小写不敏感，使编号大小写等价规则能被真实覆盖。
