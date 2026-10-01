# 计量器具批量编号核对：逐行结果、不丢行、不乱序

## 问题

旧批量查询 `GET /api/instruments/batch?instNos=a,b,c` 有三个结构性缺陷：

- 服务端用 `byMultipleNaturalId(...).enableOrderedReturn(false)` 取回后直接透传，
  未找到的编号位置返回的 null 被剔除 —— **列表被压缩**，前端按下标对齐必然错位，
  后续器具会显示到错误的输入行；
- 返回顺序由数据库决定（明确关闭了有序返回），不稳定；
- 编号全部拼进一条 GET 地址参数，清单一长就撞上地址长度上限，且前端无法区分
  未找到 / 空行 / 格式错误 / 重复输入。

只给结果重新排序、只在前端补空行、或遇到缺失项让整批失败都不能解决错位根因。

## 新接口 `POST /api/instruments/batch-query`

请求体（不再拼地址参数，支持长清单）：

```json
{ "instNos": ["JLB-0001", " JLB-0002，JLB-0003", "", "JLB-4040,JLB-4040"] }
```

- 每个数组元素是用户粘贴的一行；行内可再用**英文逗号或中文逗号**混排多个编号。
- 编号前后空白（含全角空格 `　`）统一清理；内部空白不清理，按格式错误标出。
- 大小写规则与台账唯一约束一致（MySQL 为 `utf8mb4_unicode_ci` 大小写不敏感；
  H2 等大小写敏感库上由服务端对未命中编号补一次 `LOWER(...)` 批量查询兜底）。

返回固定携带 `"type": "instrument-batch-result/v1"` 结构版本标识，旧调用方不会把它误认成旧数组；
`rows` 与输入展平后的编号位**严格一一对应、顺序一致、不压缩**：

| 字段 | 含义 |
| --- | --- |
| `index` | 展平后的输入位置，从 0 开始（前端回填只认它） |
| `rawInput` | 该编号位的原始输入文本（不清理） |
| `instNo` | 规范化编号；空行/格式错误行为 null |
| `status` | `FOUND` / `NOT_FOUND` / `BLANK` / `INVALID` / `DUPLICATE` |
| `duplicateOf` | 重复行指向首次出现位置 |
| `message` | 可直接展示的一句话说明 |
| `instrument` | FOUND 时完整台账视图；DUPLICATE 回填首次行同一器具；其余 null |

顶层另有 `total/foundCount/notFoundCount/blankCount/invalidCount/duplicateCount` 汇总。

规则：

1. **相同编号同一请求只查库一次**（按规范化小写键去重），但结果回填到每个输入位置，
   连续重复的每一行都带回完整台账；重复一个不存在的编号时，首行 NOT_FOUND、后续行 DUPLICATE。
2. **任何一行无效都不影响其他行**，也绝不整批失败；仅在超过体量上限
   （> 5000 原始行 / > 10000 展平编号位）时返回 400 并提示分批。
3. **查询效率**：唯一编号去重后走自然主键批量加载，每 200 个编号切一片
   （`DB_CHUNK_SIZE`），不存在逐条访问库；大小写兜底查询也按批切片。
4. 顺序完全由服务端按输入 index 组装 —— 已验证数据库乱序返回（测试中固定反转批量加载结果）时逐行仍对齐。

## 旧接口兼容

`GET /api/instruments/batch`（及 service 方法 `batchByInstNos`）保留但标记 `@Deprecated`：
仍是命中器具的纯数组、未命中丢弃的旧契约，**没有静默改成另一种无法识别的结构**。
旧调用方可继续工作；新结构只出现在新的 POST 路径，且带显式 `type` 标识便于识别。
单编号查询 `GET /api/instruments/{instNo}` 与台账页面不变。

## 前端

- 台账页新增「批量核对」入口 → `/instruments/batch-check`（`BatchCheckView.vue`）。
- textarea 粘贴多行，中英文逗号混排；空行保留位置；结果表格逐行显示
  原始输入 / 规范化编号 / 状态标签 / 说明 / 完整台账列。
- 「只重新查询失败行」：仅取 NOT_FOUND / INVALID 行的原始输入去重后重查，
  回来按原始输入文本归位；FOUND / BLANK / DUPLICATE 行不动，跟随首次行的重复行自动补齐。
- `src/views/batchCheck.js` 为纯逻辑（解析 / 展平 / 分批 / 合并 / 重查清单构造），
  `src/views/batchRunner.js` 为请求执行器，二者可单测：
  - 大清单按 **token 边界**（不是原始行边界）拆成 ≤1000 个编号/批并行 POST，
    即使一行里用逗号粘了几千个编号也会被切开；
  - 每批响应在本批内从 0 编号，合并时加 `indexOffset` 并按 index 排序 ——
    **批次乱序返回不影响行位置**；
  - runner 维护单调递增代次，较早发起的查询（含其分批）晚返回一律判 stale 丢弃，
    **不会用旧结果覆盖页面上较新的查询结果**。

## 自动化测试

- 后端 `InstrumentBatchQueryTest`（14 项，H2 + MockMvc）：首项缺失、中间缺失+空行、
  连续重复且逐位回填、重复不存在编号、全部无效不压列表、混合分隔符+全角空格、
  大小写等价、2300 行超长清单按 200 切片且行序不乱、**数据库乱序返回**（子类固定反转
  `multiLoadDistinct`）、超量 400、单编号与台账仍可用、旧 GET /batch 仍是纯数组旧契约、空请求体。
- 前端 `src/views/batchCheck.test.js`（Vitest，9 项）：多行/混合分隔符解析保位、
  token 拆批与 offset、**批次乱序合并**、失败行去重重查、**前端请求乱序（旧晚返回不覆盖新结果）**、
  2300 行拆 3 批并行。

> 说明：`RawRecordVersioningTest` 有 4 项失败在改动前的原始代码上同样存在
> （H2 不支持 MySQL 生成列等环境差异），与本次改动无关。
