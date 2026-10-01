package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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

    /** 单次批量核对的原始文本上限（约 16KB，超过请调用方自行分批，避免请求体无限膨胀） */
    public static final int BATCH_MAX_TEXT_LENGTH = 16_000;
    /** 单次批量核对的行数上限（含空行） */
    public static final int BATCH_MAX_ROWS = 500;
    /** 一次 multiLoad 交给数据库的编号数，避免 IN 参数与 SQL 长度失控 */
    private static final int DB_CHUNK_SIZE = 100;
    /** 器具编号库内长度上限（与 lab_instrument.inst_no VARCHAR(32) 对齐） */
    private static final int INST_NO_MAX_LENGTH = 32;
    /** 兼容旧调用方的批量接口版本标识 */
    public static final String BATCH_API_VERSION = "batch-instruments-v2";

    /**
     * 把用户粘贴的整段文本拆成「逐行原始输入」。
     *
     * <p>支持换行（含 {@code \r\n} / {@code \r}）、半角逗号、全角逗号、中文顿号混合分隔；
     * 每个分隔片段保留其原始字符（不 trim），空片段即空行位置。末尾分隔符造成的单个尾部空片段
     * 视为「文本结尾换行」而非额外一行（与粘贴多行文本的直觉一致）。
     */
    public static List<String> splitBatchInput(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isBatchSeparator(ch)) {
                tokens.add(text.substring(start, i));
                // Windows 换行 \r\n 只算一个分隔符，不能在中间制造空行
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        tokens.add(text.substring(start));
        // 整段以分隔符结尾时（典型：粘贴多行后末尾自带换行），最后一个空片段不是真实输入行
        if (tokens.size() > 1
                && tokens.get(tokens.size() - 1).isEmpty()
                && isBatchSeparator(text.charAt(text.length() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return tokens;
    }

    private static boolean isBatchSeparator(char ch) {
        return ch == '\n' || ch == '\r' || ch == ',' || ch == '，' || ch == '、';
    }

    /** 规范化编号：现有规则只做去首尾空白（{@link #loadByNaturalId} 一致）。 */
    private static String normalizeNo(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * 编号查重 / 查库的等价键：去空白后再按库的比较规则折叠大小写。
     *
     * <p>生产 MySQL 为 {@code utf8mb4_unicode_ci}（大小写不敏感），测试 H2 自然主键比较同样
     * 不区分大小写，故这里统一用 {@code toLowerCase(ROOT)} 做等价判定。
     */
    private static String equivalenceKey(String normalizedNo) {
        return normalizedNo.toLowerCase(Locale.ROOT);
    }

    /** 格式校验：1..32 位、非空白、不含任何 ISO 控制字符（换行/制表符等无法进库）。 */
    private static String invalidReason(String normalizedNo) {
        if (normalizedNo.isEmpty()) {
            return "空行";
        }
        if (normalizedNo.length() > INST_NO_MAX_LENGTH) {
            return "器具编号长度不能超过 " + INST_NO_MAX_LENGTH + " 位";
        }
        for (int i = 0; i < normalizedNo.length(); i++) {
            if (Character.isISOControl(normalizedNo.charAt(i))) {
                return "器具编号含换行、制表符等非法控制字符";
            }
        }
        return null;
    }

    /**
     * 【自然主键批量核对 · v2】按用户输入的原始顺序逐行返回结果。
     *
     * <p>保证：
     * <ul>
     *   <li>每一行输入都有对应结果（FOUND / NOT_FOUND / EMPTY / INVALID / DUPLICATE），不压缩、不整批失败；</li>
     *   <li>同一请求内相同（去空白、大小写折叠后等价）编号只查库一次，结果回填到每个输入位置；</li>
     *   <li>查库分块（{@link #DB_CHUNK_SIZE} 个一块）走 {@code byMultipleNaturalId}，不逐条访问数据库，
     *       且不依赖数据库返回顺序——按编号自身回填。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Views.InstrumentBatchView batchLookup(List<String> rawInputs) {
        List<String> rows = rawInputs == null ? List.of() : rawInputs;
        if (rows.size() > BATCH_MAX_ROWS) {
            throw new BizException("单次批量核对不能超过 " + BATCH_MAX_ROWS + " 行，当前 " + rows.size()
                    + " 行，请拆分为多个批次");
        }

        // 1) 逐行规范化 + 查重；firstIndexByKey 记录每个等价编号首次出现的位置
        Map<String, Integer> firstIndexByKey = new HashMap<>();
        List<Views.InstrumentBatchRowView> resultRows =
                new ArrayList<>(rows.size());
        // 保持首次出现顺序的「本次唯一有效编号」集合，供后续一次性查库
        Map<String, String> uniqueNosByKey = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String raw = rows.get(i) == null ? "" : rows.get(i);
            String normalized = normalizeNo(raw);
            if (normalized.isEmpty()) {
                resultRows.add(new Views.InstrumentBatchRowView(i, raw, null,
                        "EMPTY", "空行", "空行：请输入器具编号", null, null));
                continue;
            }
            String reason = invalidReason(normalized);
            if (reason != null) {
                resultRows.add(new Views.InstrumentBatchRowView(i, raw, normalized,
                        "INVALID", "格式错误", reason, null, null));
                continue;
            }
            String key = equivalenceKey(normalized);
            Integer first = firstIndexByKey.get(key);
            if (first == null) {
                firstIndexByKey.put(key, i);
                uniqueNosByKey.put(key, normalized);
                // 先占位，查库后统一回填
                resultRows.add(null);
            } else {
                resultRows.add(new Views.InstrumentBatchRowView(i, raw, normalized,
                        "DUPLICATE", "重复输入",
                        "与第 " + (first + 1) + " 行编号重复，不重复查库，结果随该行回填",
                        first, null));
            }
        }

        // 2) 分块一次性查库：每块走 multiLoad（不逐条查），结果只按编号自身回填（不信返回顺序）
        Map<String, Instrument> foundByKey = new TreeMap<>();
        List<String> uniqueNos = new ArrayList<>(uniqueNosByKey.values());
        Map<Long, String> clientName = clientNameMap();
        Session session = session();
        for (int from = 0; from < uniqueNos.size(); from += DB_CHUNK_SIZE) {
            List<String> chunk = uniqueNos.subList(from, Math.min(from + DB_CHUNK_SIZE, uniqueNos.size()));
            List<Instrument> loaded = session.byMultipleNaturalId(Instrument.class)
                    .enableOrderedReturn(false)
                    .multiLoad(chunk);
            for (Instrument instrument : loaded) {
                if (instrument != null) {
                    foundByKey.put(equivalenceKey(instrument.getInstNo()), instrument);
                }
            }
        }

        // 3) 回填首次出现行
        for (Map.Entry<String, Integer> entry : firstIndexByKey.entrySet()) {
            int index = entry.getValue();
            String key = entry.getKey();
            String raw = rows.get(index) == null ? "" : rows.get(index);
            String normalized = normalizeNo(raw);
            Instrument instrument = foundByKey.get(key);
            if (instrument != null) {
                resultRows.set(index, new Views.InstrumentBatchRowView(index, raw, instrument.getInstNo(),
                        "FOUND", "已找到", "", null, toView(instrument, clientName)));
            } else {
                resultRows.set(index, new Views.InstrumentBatchRowView(index, raw, normalized,
                        "NOT_FOUND", "未找到", "台账中不存在编号为 " + normalized + " 的计量器具",
                        null, null));
            }
        }

        // 4) 把首次行的查询结果回填给每个重复位置（同一编号只查库一次，多处共享）
        for (int i = 0; i < resultRows.size(); i++) {
            Views.InstrumentBatchRowView row = resultRows.get(i);
            if (row != null && "DUPLICATE".equals(row.status())) {
                Views.InstrumentBatchRowView firstRow = resultRows.get(row.duplicateOfIndex());
                Views.InstrumentView shared = firstRow.instrument();
                String status;
                String statusName;
                String message;
                if (shared != null) {
                    status = "FOUND";
                    statusName = "已找到";
                    message = "与第 " + (row.duplicateOfIndex() + 1) + " 行编号重复，复用同一条台账结果";
                } else {
                    status = "NOT_FOUND";
                    statusName = "未找到";
                    message = "与第 " + (row.duplicateOfIndex() + 1) + " 行编号重复，台账中同样不存在";
                }
                resultRows.set(i, new Views.InstrumentBatchRowView(i, row.rawInput(),
                        shared != null ? shared.instNo() : firstRow.normalized(),
                        status, statusName, message, row.duplicateOfIndex(), shared));
            }
        }

        int found = 0;
        int notFound = 0;
        int empty = 0;
        int invalid = 0;
        int duplicate = 0;
        for (Views.InstrumentBatchRowView row : resultRows) {
            switch (row.status()) {
                case "FOUND" -> found++;
                case "NOT_FOUND" -> notFound++;
                case "EMPTY" -> empty++;
                case "INVALID" -> invalid++;
                default -> { }
            }
            if (row.duplicateOfIndex() != null) {
                duplicate++;
            }
        }
        return new Views.InstrumentBatchView(BATCH_API_VERSION, resultRows.size(),
                found, notFound, empty, invalid, duplicate, resultRows);
    }

    /**
     * 【兼容旧调用方】GET {@code /instruments/batch?instNos=...}。
     *
     * <p>仍返回旧结构（裸的 {@code List<InstrumentView>}），不静默换成 v2 包装；
     * 但修复了旧实现「剔除未命中 + 返回顺序依赖数据库」的问题：结果按输入中首次出现的顺序稳定返回，
     * 重复编号折叠为一条。新功能（逐行状态/空行/重复回填）请走 POST {@code /instruments/batch-lookup}。
     */
    @Transactional(readOnly = true)
    public List<Views.InstrumentView> batchByInstNos(String instNos) {
        List<String> rawInputs = splitBatchInput(instNos == null ? "" : instNos);
        Views.InstrumentBatchView v2 = batchLookup(rawInputs);
        List<Views.InstrumentView> list = new ArrayList<>();
        for (Views.InstrumentBatchRowView row : v2.rows()) {
            if (row.instrument() != null && row.duplicateOfIndex() == null) {
                list.add(row.instrument());
            }
        }
        return list;
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
