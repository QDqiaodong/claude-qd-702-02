package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.CalibrationOrder;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.dto.InstrumentDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;

import jakarta.persistence.EntityManager;

/**
 * 模块二：计量器具台账。
 *
 * <p><b>本项目的数据访问主线：JPA {@code @NaturalId} 自然主键加载。</b>
 * 器具编号 {@code instNo} 在 {@link Instrument} 上标注为 {@code @NaturalId(mutable = false)}，
 * 于是本类里的定位操作一律换成 Hibernate Session 的自然主键 API：
 * <ul>
 *   <li>{@code session.bySimpleNaturalId(Instrument.class).load(instNo)} —— 单个加载</li>
 *   <li>{@code session.byMultipleNaturalId(Instrument.class).multiLoad(nos)} —— 批量加载</li>
 * </ul>
 * 除此之外只用普通派生查询（列表 / 到期清单）。
 */
@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final ClientRepository clientRepository;
    private final ProcedureRepository procedureRepository;
    private final CalibrationOrderRepository orderRepository;
    private final EntityManager entityManager;

    public InstrumentService(InstrumentRepository instrumentRepository,
                             ClientRepository clientRepository,
                             ProcedureRepository procedureRepository,
                             CalibrationOrderRepository orderRepository,
                             EntityManager entityManager) {
        this.instrumentRepository = instrumentRepository;
        this.clientRepository = clientRepository;
        this.procedureRepository = procedureRepository;
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
    }

    /** 取出 Hibernate Session：自然主键加载 API 挂在它上面 */
    private Session session() {
        return entityManager.unwrap(Session.class);
    }

    /**
     * 【自然主键加载】按器具编号取器具。
     *
     * <p>等价于「用业务编号查库」，但走的是 {@code @NaturalId} 通道。
     */
    @Transactional(readOnly = true)
    public Instrument loadByNaturalId(String instNo) {
        Instrument instrument = session()
                .bySimpleNaturalId(Instrument.class)
                .load(instNo == null ? "" : instNo.trim());
        if (instrument == null) {
            throw new BizException("器具台账中不存在编号为 " + instNo + " 的计量器具");
        }
        return instrument;
    }

    /** 【自然主键加载】按器具编号取器具视图 */
    @Transactional(readOnly = true)
    public Views.InstrumentView findByInstNo(String instNo) {
        return toView(loadByNaturalId(instNo), clientNameMap());
    }

    /**
     * 【自然主键批量加载 · 旧版兼容】逗号分隔的多个器具编号一次性加载。
     *
     * <p><b>旧调用方契约保留：</b>仍返回命中器具的纯数组（未命中丢弃、不保证顺序），
     * 不静默改成新的逐行结构；新功能请用 {@link #batchQuery(List)}。
     *
     * @deprecated 改用 {@code POST /api/instruments/batch-query}，按原始输入逐行返回状态，
     *             未找到/空行/格式错误/重复都保留位置，不再丢行。
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<Views.InstrumentView> batchByInstNos(String instNos) {
        // Hibernate 6 里批量自然主键加载走 byMultipleNaturalId：
        // Session.byMultipleIds() 只认 @Id 代理主键，传业务编号会报 Error coercing value
        List<Instrument> loaded = multiLoadDistinct(legacyParse(instNos));
        Map<Long, String> clientName = clientNameMap();
        List<Views.InstrumentView> list = new ArrayList<>();
        for (Instrument instrument : loaded) {
            if (instrument != null) {
                list.add(toView(instrument, clientName));
            }
        }
        return list;
    }

    /** 旧版入参解析：仅按英文逗号切分、去空白、丢空串（行为保持不变）。 */
    private static List<String> legacyParse(String instNos) {
        List<String> nos = new ArrayList<>();
        String raw = instNos == null ? "" : instNos;
        for (String no : raw.split(",", -1)) {
            String trimmed = no.trim();
            if (!trimmed.isEmpty()) {
                nos.add(trimmed);
            }
        }
        return nos;
    }

    /** 清理编号前后的 Unicode 空白（含全角空格），保留内部空白以便按格式错误识别。 */
    private static String stripOuterSpace(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    // ============================ 批量核对（逐行结果） ============================

    /** 新批量核对返回结构的显式标识，旧调用方凭它区分新旧结构 */
    public static final String BATCH_RESULT_TYPE = "instrument-batch-result/v1";

    /** 单次批量核对允许的最大输入行数（原始行，含空行），超限直接 400 提示分批 */
    public static final int BATCH_MAX_LINES = 5000;

    /** 单次批量核对允许的最大切分后编号数（逗号混排后） */
    public static final int BATCH_MAX_TOKENS = 10000;

    /** 单次 IN 批量加载的最大编号数：大清单在服务端切片，避免一条巨型 IN 语句 */
    public static final int DB_CHUNK_SIZE = 200;

    /** 器具编号合法字符：字母、数字、连字符、下划线、点、斜杠、井号（如 JLB-0025 / JJG 21-2021 类不含空格） */
    private static final Pattern VALID_INST_NO = Pattern.compile("[A-Za-z0-9_.\\-/#]{1,32}");

    /** 逗号类分隔符：英文逗号、中文逗号（含全角空格不属于分隔符，正常 trim 掉） */
    private static final Pattern COMMA_SPLIT = Pattern.compile("[,，]");

    /**
     * 批量核对主入口：按用户输入的原始顺序逐行返回，命中回填完整台账，
     * 未找到 / 空行 / 格式错误 / 重复分别标状态，绝不压缩列表、绝不让一行无效拖垮整批。
     *
     * @param rawLines 用户粘贴的原始行（每行还可以再用中英文逗号混排多个编号）
     */
    @Transactional(readOnly = true)
    public Views.InstrumentBatchResultView batchQuery(List<String> rawLines) {
        if (rawLines != null && rawLines.size() > BATCH_MAX_LINES) {
            throw new BizException("一次最多核对 " + BATCH_MAX_LINES + " 行，请拆分成多批后再查询");
        }

        // 1) 逐行展开成 token：保留每个 token 的原始文本与展平后的位置（index 与前端约定一致）
        List<String> tokens = new ArrayList<>();
        List<String> tokenRaw = new ArrayList<>();
        if (rawLines != null) {
            for (String line : rawLines) {
                String safe = line == null ? "" : line;
                for (String part : COMMA_SPLIT.split(safe, -1)) {
                    tokenRaw.add(part);
                    // 编号前后空白统一清理：除半角空格外还含全角空格等 Unicode 空白；内部空白不清理（判格式错误）
                    tokens.add(stripOuterSpace(part));
                }
            }
        }
        if (tokens.size() > BATCH_MAX_TOKENS) {
            throw new BizException("一次最多核对 " + BATCH_MAX_TOKENS
                    + " 个编号（当前 " + tokens.size() + " 个），请拆分成多批后再查询");
        }

        // 2) 逐行分类：BLANK / INVALID 先定；合法编号标记首次出现位置，后续出现标 DUPLICATE
        Map<String, Integer> firstIndexByKey = new HashMap<>();
        String[] normalized = new String[tokens.size()];
        int[] dupOf = new int[tokens.size()];
        boolean[] valid = new boolean[tokens.size()];
        java.util.Arrays.fill(dupOf, -1);

        List<String> uniqueToQuery = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.isEmpty()) {
                continue; // BLANK
            }
            if (t.length() > 32 || !VALID_INST_NO.matcher(t).matches() || t.chars().anyMatch(Character::isWhitespace)) {
                continue; // INVALID
            }
            valid[i] = true;
            normalized[i] = t;
            String key = t.toLowerCase(java.util.Locale.ROOT);
            Integer first = firstIndexByKey.get(key);
            if (first == null) {
                firstIndexByKey.put(key, i);
                uniqueToQuery.add(t);
            } else {
                dupOf[i] = first;
            }
        }

        // 3) 相同编号在本请求内只查库一次；服务端切片批量自然主键加载，绝不逐条访问数据库
        Map<String, Instrument> foundByKey = new HashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (int from = 0; from < uniqueToQuery.size(); from += DB_CHUNK_SIZE) {
            int to = Math.min(from + DB_CHUNK_SIZE, uniqueToQuery.size());
            List<Instrument> chunk = multiLoadDistinct(uniqueToQuery.subList(from, to));
            for (Instrument ins : chunk) {
                if (ins != null) {
                    foundByKey.put(ins.getInstNo().toLowerCase(java.util.Locale.ROOT), ins);
                }
            }
            for (int i = from; i < to; i++) {
                String no = uniqueToQuery.get(i);
                if (!foundByKey.containsKey(no.toLowerCase(java.util.Locale.ROOT))) {
                    unresolved.add(no);
                }
            }
        }
        // 4) 大小写兜底：自然主键批量加载在大小写敏感的库上可能漏掉「大小写不同但同一编号」的器具，
        //    对未命中编号再做一次 LOWER 查询（仍按批切片），规则与台账唯一约束一致
        for (int from = 0; from < unresolved.size(); from += DB_CHUNK_SIZE) {
            int to = Math.min(from + DB_CHUNK_SIZE, unresolved.size());
            List<String> lower = new ArrayList<>();
            for (int i = from; i < to; i++) {
                lower.add(unresolved.get(i).toLowerCase(java.util.Locale.ROOT));
            }
            for (Instrument ins : instrumentRepository.findCaseInsensitive(lower)) {
                foundByKey.putIfAbsent(ins.getInstNo().toLowerCase(java.util.Locale.ROOT), ins);
            }
        }

        // 5) 组装逐行视图：先渲染首次行（FOUND / NOT_FOUND），重复行回填首次行结果
        Map<Long, String> clientName = clientNameMap();
        Views.InstrumentBatchRowView[] rowArr = new Views.InstrumentBatchRowView[tokens.size()];
        int found = 0, notFound = 0, blank = 0, invalid = 0, duplicate = 0;

        for (int i = 0; i < tokens.size(); i++) {
            String raw = tokenRaw.get(i);
            String displayRaw = raw == null ? "" : raw;
            if (!valid[i] && dupOf[i] < 0) {
                if (tokens.get(i).isEmpty()) {
                    blank++;
                    rowArr[i] = row(i, displayRaw, null, "BLANK", null, "空行：未填写编号", null);
                } else {
                    invalid++;
                    rowArr[i] = row(i, displayRaw, null, "INVALID", null,
                            "编号格式有误：仅支持字母、数字与 - _ . / #，长度不超过 32 位", null);
                }
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (!valid[i]) {
                continue;
            }
            if (dupOf[i] >= 0) {
                continue; // 稍后回填
            }
            Instrument ins = foundByKey.get(normalized[i].toLowerCase(java.util.Locale.ROOT));
            if (ins != null) {
                found++;
                rowArr[i] = row(i, tokenRaw.get(i), normalized[i], "FOUND", null,
                        "已命中台账", toView(ins, clientName));
            } else {
                notFound++;
                rowArr[i] = row(i, tokenRaw.get(i), normalized[i], "NOT_FOUND", null,
                        "台账中未找到编号 " + normalized[i], null);
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (dupOf[i] >= 0) {
                duplicate++;
                int first = dupOf[i];
                Views.InstrumentBatchRowView head = rowArr[first];
                String same = "FOUND".equals(head.status())
                        ? "与第 " + (first + 1) + " 行为同一器具，未重复查询"
                        : "与第 " + (first + 1) + " 行编号重复，该编号台账未找到";
                rowArr[i] = row(i, tokenRaw.get(i), normalized[i], "DUPLICATE", first, same, head.instrument());
            }
        }

        List<Views.InstrumentBatchRowView> rows = new ArrayList<>(tokens.size());
        java.util.Collections.addAll(rows, rowArr);
        return new Views.InstrumentBatchResultView(
                BATCH_RESULT_TYPE, rows.size(), found, notFound, blank, invalid, duplicate, rows);
    }

    private static Views.InstrumentBatchRowView row(int index, String rawInput, String instNo, String status,
                                                    Integer duplicateOf, String message,
                                                    Views.InstrumentView instrument) {
        return new Views.InstrumentBatchRowView(index, rawInput, instNo, status, batchStatusName(status),
                duplicateOf, message, instrument);
    }

    /** 批量核对行状态中文名（状态码全部服务端判定，前端只展示）。 */
    public static String batchStatusName(String status) {
        return switch (status) {
            case "FOUND" -> "已命中";
            case "NOT_FOUND" -> "未找到";
            case "BLANK" -> "空行";
            case "INVALID" -> "格式错误";
            case "DUPLICATE" -> "重复输入";
            default -> status;
        };
    }

    /**
     * 自然主键批量加载的唯一出口：入参先去重（相同编号只查库一次），
     * 不要求数据库保证返回顺序（enableOrderedReturn(false)），顺序由调用方自己按编号对齐。
     *
     * <p>protected 是为了让自动化测试能替换它，确定制造「数据库乱序返回」的场景。
     */
    protected List<Instrument> multiLoadDistinct(List<String> nos) {
        if (nos.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String no : nos) {
            if (no != null && !no.isBlank()) {
                distinct.putIfAbsent(no.trim(), no.trim());
            }
        }
        if (distinct.isEmpty()) {
            return new ArrayList<>();
        }
        return session()
                .byMultipleNaturalId(Instrument.class)
                .enableOrderedReturn(false)
                .multiLoad(new ArrayList<>(distinct.values()));
    }

    @Transactional(readOnly = true)
    public List<Views.InstrumentView> list() {
        Map<Long, String> clientName = clientNameMap();
        List<Views.InstrumentView> list = new ArrayList<>();
        for (Instrument instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            list.add(toView(instrument, clientName));
        }
        return list;
    }

    /** 派生查询：30 天内到期（含已超期）的器具，按到期日升序 */
    @Transactional(readOnly = true)
    public List<Views.InstrumentView> dueSoon(int days) {
        LocalDate deadline = LocalDate.now().plusDays(days);
        Map<Long, String> clientName = clientNameMap();
        List<Views.InstrumentView> list = new ArrayList<>();
        for (Instrument instrument : instrumentRepository
                .findAllByNextDueDateBeforeOrderByNextDueDateAsc(deadline)) {
            list.add(toView(instrument, clientName));
        }
        return list;
    }

    @Transactional
    public Views.InstrumentView create(InstrumentDtos.InstrumentSaveReq req) {
        String instNo = req.getInstNo() == null ? "" : req.getInstNo().trim();
        if (instNo.isEmpty()) {
            throw new BizException("器具编号不能为空");
        }
        // 编号是否已被占用：同样用自然主键加载判断，保持只有一条查询通路
        if (session().bySimpleNaturalId(Instrument.class).load(instNo) != null) {
            throw new BizException("器具编号已存在：" + instNo);
        }
        String status = req.getStatus() == null || req.getStatus().isBlank()
                ? "IN_USE" : req.getStatus().trim();
        if (!MetroDict.isValidInstrumentStatus(status)) {
            throw new BizException("器具状态不合法，应为 在用 / 停用 / 送检中 / 报废");
        }
        int cycleMonths = req.getCycleMonths() == null ? 12 : req.getCycleMonths();
        if (cycleMonths <= 0) {
            throw new BizException("检定周期（月）必须大于 0");
        }
        LocalDate lastCalDate = parseDate(req.getLastCalDate());
        LocalDate nextDueDate = parseDate(req.getNextDueDate());
        if (nextDueDate == null && lastCalDate != null) {
            nextDueDate = lastCalDate.plusMonths(cycleMonths);
        }

        Instrument instrument = new Instrument();
        instrument.setInstNo(instNo);
        instrument.setName(req.getName());
        instrument.setModelSpec(req.getModelSpec());
        instrument.setMeasureRange(req.getMeasureRange());
        instrument.setAccuracyGrade(req.getAccuracyGrade());
        instrument.setManufacturer(req.getManufacturer());
        instrument.setFactoryNo(req.getFactoryNo());
        instrument.setCycleMonths(cycleMonths);
        instrument.setLastCalDate(lastCalDate);
        instrument.setNextDueDate(nextDueDate);
        instrument.setStatus(status);
        instrument.setClientId(req.getClientId());
        instrument.setRemark(req.getRemark());
        instrument.setUpdatedAt(LocalDateTime.now());
        instrumentRepository.save(instrument);
        return toView(instrument, clientNameMap());
    }

    /**
     * 台账「送检」：按器具编号（自然主键）定位器具 → 生成一张「收样」状态的检定委托单，
     * 同时把器具状态置为「送检中」。
     */
    @Transactional
    public Views.InstrumentView submit(String instNo, InstrumentDtos.SubmitReq req) {
        Instrument instrument = loadByNaturalId(instNo);
        if ("SCRAPPED".equals(instrument.getStatus())) {
            throw new BizException("器具 " + instrument.getInstNo() + " 已报废，不能直接送检");
        }
        var procedure = procedureRepository.findByProcNo(req.getProcNo())
                .orElseThrow(() -> new BizException("规程不存在：" + req.getProcNo()));
        if (instrument.getClientId() == null) {
            throw new BizException("器具 " + instrument.getInstNo() + " 尚未登记所属客户，请先在台账补充客户信息");
        }

        CalibrationOrder order = new CalibrationOrder();
        order.setOrderNo(nextOrderNo());
        order.setClientId(instrument.getClientId());
        order.setInstrumentId(instrument.getId());
        order.setInstNo(instrument.getInstNo());
        order.setProcedureId(procedure.getId());
        order.setReceiveDate(LocalDate.now());
        order.setVerifier(req.getVerifier());
        order.setStatus("RECEIVED");
        order.setFee(procedure.getUnitFee());
        order.setRemark("台账「送检」按钮生成，按 " + procedure.getProcNo() + " 检定");
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        instrument.setStatus("IN_CALIBRATION");
        instrument.setUpdatedAt(LocalDateTime.now());
        instrumentRepository.save(instrument);
        return toView(instrument, clientNameMap());
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDate.parse(text.trim());
    }

    private String nextOrderNo() {
        long seq = orderRepository.count() + 1;
        return "WT" + LocalDate.now().getYear() + "-" + String.format("%04d", seq);
    }

    private Map<Long, String> clientNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (var client : clientRepository.findAllByOrderByCodeAsc()) {
            map.put(client.getId(), client.getName());
        }
        return map;
    }

    private Views.InstrumentView toView(Instrument instrument, Map<Long, String> clientName) {
        LocalDate nextDue = instrument.getNextDueDate();
        Integer dueDays = nextDue == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), nextDue);
        boolean overdue = dueDays != null && dueDays < 0;
        boolean dueWarn = dueDays != null && dueDays >= 0 && dueDays <= MetroDict.INSTRUMENT_DUE_WARN_DAYS;
        return new Views.InstrumentView(
                instrument.getId(),
                instrument.getInstNo(),
                instrument.getName(),
                instrument.getModelSpec(),
                instrument.getMeasureRange(),
                instrument.getAccuracyGrade(),
                instrument.getManufacturer(),
                instrument.getFactoryNo(),
                instrument.getCycleMonths(),
                instrument.getLastCalDate(),
                nextDue,
                instrument.getStatus(),
                MetroDict.instrumentStatusName(instrument.getStatus()),
                instrument.getClientId(),
                clientName.getOrDefault(instrument.getClientId(), ""),
                dueDays,
                overdue,
                dueWarn,
                instrument.getRemark(),
                instrument.getUpdatedAt());
    }
}
