package com.metrology.lab.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.CalibrationOrder;
import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.domain.RawRecordVersion;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.RawRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;
import com.metrology.lab.repository.RawRecordVersionRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

/**
 * 模块四：检定委托与流转单。
 *
 * <p>状态沿 {@link MetroDict#FLOW_ORDER} 推进：
 * 收样 RECEIVED → 检定中 CALIBRATING → 核验 VERIFYING → 出证 CERTIFYING → 已发放 ISSUED。
 *
 * <p>建单与详情都从<b>器具编号</b>出发 —— 定位器具走 {@link InstrumentService#loadByNaturalId(String)}
 * 的 {@code @NaturalId} 自然主键加载，不在本类里另开派生查询。
 */
@Service
public class CalibrationOrderService {

    private final CalibrationOrderRepository orderRepository;
    private final InstrumentService instrumentService;
    private final InstrumentRepository instrumentRepository;
    private final ClientRepository clientRepository;
    private final ProcedureRepository procedureRepository;
    private final StandardDeviceRepository deviceRepository;
    private final OrderDeviceService orderDeviceService;
    private final RawRecordService rawRecordService;
    private final RawRecordVersionRepository recordVersionRepository;

    public CalibrationOrderService(CalibrationOrderRepository orderRepository,
                                   InstrumentService instrumentService,
                                   InstrumentRepository instrumentRepository,
                                   ClientRepository clientRepository,
                                   ProcedureRepository procedureRepository,
                                   StandardDeviceRepository deviceRepository,
                                   OrderDeviceService orderDeviceService,
                                   RawRecordService rawRecordService,
                                   RawRecordVersionRepository recordVersionRepository) {
        this.orderRepository = orderRepository;
        this.instrumentService = instrumentService;
        this.instrumentRepository = instrumentRepository;
        this.clientRepository = clientRepository;
        this.procedureRepository = procedureRepository;
        this.deviceRepository = deviceRepository;
        this.orderDeviceService = orderDeviceService;
        this.rawRecordService = rawRecordService;
        this.recordVersionRepository = recordVersionRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.OrderView> list(String status, Long clientId) {
        List<CalibrationOrder> orders;
        if (status != null && !status.isBlank()) {
            orders = orderRepository.findAllByStatusOrderByReceiveDateAsc(status.trim());
        } else if (clientId != null) {
            orders = orderRepository.findAllByClientIdOrderByReceiveDateDesc(clientId);
        } else {
            orders = orderRepository.findAllByOrderByReceiveDateDescIdAsc();
        }
        ViewContext context = context();
        Map<Long, OrderDeviceService.LinkSummary> summaries =
                orderDeviceService.activeSummary(orders.stream().map(CalibrationOrder::getId)
                        .collect(java.util.stream.Collectors.toSet()));
        java.util.Set<Long> blockedIds = orderDeviceService.blockedOrderIds();
        Map<Long, Integer> versionNoMap = currentVersionNos(orders);
        List<Views.OrderView> list = new ArrayList<>();
        for (CalibrationOrder order : orders) {
            list.add(toView(order, context, summaries, false, blockedIds.contains(order.getId()), versionNoMap));
        }
        return list;
    }

    /** 左侧常驻「待办队列」：按流转状态分组 */
    @Transactional(readOnly = true)
    public List<Views.QueueGroupView> queue() {
        ViewContext context = context();
        LocalDate today = LocalDate.now();
        List<CalibrationOrder> queued = new ArrayList<>();
        for (String status : MetroDict.FLOW_ORDER) {
            queued.addAll(orderRepository.findAllByStatusOrderByReceiveDateAsc(status));
        }
        Map<Long, OrderDeviceService.LinkSummary> summaries =
                orderDeviceService.activeSummary(queued.stream().map(CalibrationOrder::getId)
                        .collect(java.util.stream.Collectors.toSet()));
        java.util.Set<Long> blockedIds = orderDeviceService.blockedOrderIds();

        List<Views.QueueGroupView> groups = new ArrayList<>();
        for (String status : MetroDict.FLOW_ORDER) {
            List<Views.QueueItemView> items = new ArrayList<>();
            for (CalibrationOrder order : orderRepository.findAllByStatusOrderByReceiveDateAsc(status)) {
                Integer waiting = order.getReceiveDate() == null ? null
                        : (int) ChronoUnit.DAYS.between(order.getReceiveDate(), today);
                OrderDeviceService.LinkSummary s = summaries.getOrDefault(order.getId(),
                        new OrderDeviceService.LinkSummary(0, null));
                items.add(new Views.QueueItemView(
                        order.getId(),
                        order.getOrderNo(),
                        order.getInstNo(),
                        context.instrumentName.getOrDefault(order.getInstrumentId(), order.getInstNo()),
                        context.clientName.getOrDefault(order.getClientId(), ""),
                        order.getReceiveDate(),
                        waiting,
                        s.count(),
                        s.earliestValidUntil(),
                        blockedIds.contains(order.getId())));
            }
            groups.add(new Views.QueueGroupView(status, MetroDict.flowStatusName(status), items.size(), items));
        }
        return groups;
    }

    /** 顶部概览计数器 */
    @Transactional(readOnly = true)
    public Views.OverviewView overview() {
        LocalDate today = LocalDate.now();
        long instrumentTotal = 0;
        long dueSoon = 0;
        long overdue = 0;
        for (Instrument instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            instrumentTotal++;
            LocalDate due = instrument.getNextDueDate();
            if (due != null) {
                long days = ChronoUnit.DAYS.between(today, due);
                if (days < 0) {
                    overdue++;
                } else if (days <= MetroDict.INSTRUMENT_DUE_WARN_DAYS) {
                    dueSoon++;
                }
            }
        }
        long orderTotal = 0;
        long orderOpen = 0;
        BigDecimal monthFee = BigDecimal.ZERO;
        int thisMonth = today.getMonthValue();
        for (CalibrationOrder order : orderRepository.findAllByOrderByReceiveDateDescIdAsc()) {
            orderTotal++;
            if (!"ISSUED".equals(order.getStatus()) && !MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
                orderOpen++;
            }
            if (order.getReceiveDate() != null && order.getReceiveDate().getMonthValue() == thisMonth
                    && order.getFee() != null) {
                monthFee = monthFee.add(order.getFee());
            }
        }
        long deviceExpiring = 0;
        for (var device : deviceRepository.findAllByOrderByCertValidUntilAsc()) {
            if (device.getCertValidUntil() != null) {
                long days = ChronoUnit.DAYS.between(today, device.getCertValidUntil());
                if (days <= MetroDict.DEVICE_VALID_WARN_DAYS) {
                    deviceExpiring++;
                }
            }
        }
        return new Views.OverviewView(instrumentTotal, dueSoon, overdue, orderTotal, orderOpen,
                deviceExpiring, monthFee);
    }

    @Transactional(readOnly = true)
    public Views.OrderView detail(Long id) {
        CalibrationOrder order = require(id);
        // 详情每次都按当前时间实时判定 —— 停用、证书改期、异常记录在重新进入页面时立即可见
        OrderDeviceService.GateCheck gate = orderDeviceService.checkGate(id, false);
        List<Views.OrderDeviceView> devices = orderDeviceService.activeDeviceViews(id);
        List<Views.DeviceLinkHistoryView> history = orderDeviceService.historyViews(id);
        LocalDate earliest = devices.stream()
                .map(Views.OrderDeviceView::certValidUntil)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        Views.RecordPanelView panel = rawRecordService.panel(id);
        return toView(order, context(),
                new OrderDeviceService.LinkSummary(devices.size(), earliest), true,
                gate, devices, history, panel);
    }

    // ============================ 挂接入口 ============================

    /** 挂接选择器候选（带实时占用冲突） */
    @Transactional(readOnly = true)
    public List<Views.DeviceOptionView> deviceOptions(Long id) {
        require(id);
        return orderDeviceService.options(id);
    }

    /** 收样态整单调整挂接清单（挂接/替换/解除走同一个服务端判定入口） */
    @Transactional
    public Views.OrderView saveDevices(Long id, CalibrationOrderDtos.DeviceLinkReq req) {
        orderDeviceService.replaceLinks(id, req.getDeviceIds());
        return detail(id);
    }

    /** 委托作废：未发放均可作废，释放标准器占用；历史数据保留可查 */
    @Transactional
    public Views.OrderView voidOrder(Long id) {
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("委托已作废，无需重复操作");
        }
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("已发放的委托不能作废，如需更正请另开委托单");
        }
        boolean hadReceived = !"RECEIVED".equals(order.getStatus());
        order.setStatus(MetroDict.STATUS_VOIDED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        orderDeviceService.releaseForVoid(id);
        if (hadReceived) {
            // 已开始检定又终止的，器具台账恢复在用，避免长期挂在「送检中」
            restoreInstrument(order);
        }
        return detail(id);
    }

    /** 【自然主键】按器具编号建委托单 */
    @Transactional
    public Views.OrderView create(CalibrationOrderDtos.OrderSaveReq req) {
        if (orderRepository.existsByOrderNo(req.getOrderNo())) {
            throw new BizException("委托单号已存在：" + req.getOrderNo());
        }
        Instrument instrument = instrumentService.loadByNaturalId(req.getInstNo());
        if ("SCRAPPED".equals(instrument.getStatus())) {
            throw new BizException("器具 " + instrument.getInstNo() + " 已报废，不能受理委托");
        }
        if (instrument.getClientId() == null) {
            throw new BizException("器具 " + instrument.getInstNo() + " 尚未登记所属送检客户");
        }
        var procedure = procedureRepository.findByProcNo(req.getProcNo())
                .orElseThrow(() -> new BizException("规程不存在：" + req.getProcNo()));
        if (req.getConclusion() != null && !req.getConclusion().isBlank()
                && !MetroDict.isValidConclusion(req.getConclusion())) {
            throw new BizException("检定结论只能是 PASS / LIMITED / FAIL");
        }
        CalibrationOrder order = new CalibrationOrder();
        order.setOrderNo(req.getOrderNo().trim());
        order.setClientId(instrument.getClientId());
        order.setInstrumentId(instrument.getId());
        order.setInstNo(instrument.getInstNo());
        order.setProcedureId(procedure.getId());
        order.setReceiveDate(parseDate(req.getReceiveDate(), LocalDate.now()));
        order.setVerifier(blankToNull(req.getVerifier()));
        order.setChecker(blankToNull(req.getChecker()));
        order.setMeasuredData(blankToNull(req.getMeasuredData()));
        order.setConclusion(blankToNull(req.getConclusion()));
        order.setCertNo(blankToNull(req.getCertNo()));
        order.setStatus("RECEIVED");
        order.setFee(procedure.getUnitFee());
        order.setRemark(blankToNull(req.getRemark()));
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        instrument.setStatus("IN_CALIBRATION");
        instrument.setUpdatedAt(LocalDateTime.now());
        instrumentRepository.save(instrument);
        return detail(order.getId());
    }

    /**
     * 提交复核的跨服务编排：RawRecordService 已在同一事务内冻结版本，
     * 这里继续做标准器放行并把委托推进到「核验」。任一步失败整体回滚，版本不会停在「已提交但委托没动」。
     * 收样态直接提交的，先落「检定中」（并补建空白草稿的兜底不适用——已有提交版本），再过放行关。
     */
    @Transactional
    public Views.OrderView submitRecord(Long id, Long versionId, RawRecordDtos.SubmitReq req) {
        rawRecordService.submit(id, versionId, req.getOperator());
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        if (!"CALIBRATING".equals(order.getStatus()) && !"RECEIVED".equals(order.getStatus())) {
            throw new BizException("委托当前处于「" + MetroDict.flowStatusName(order.getStatus())
                    + "」，提交复核只能在收样/检定中进行");
        }
        // 放行关与原流转一致：收样态提交（首次进入检定性环节）强制至少一件合格标准器；
        // 已在检定中则只复核已挂设备是否仍有效，历史无挂接委托不强制（兼容旧数据）。
        boolean enforce = "RECEIVED".equals(order.getStatus());
        OrderDeviceService.GateCheck gate = orderDeviceService.checkGate(id, enforce);
        if (!gate.passed()) {
            throw new BizException(buildGateMessage("VERIFYING", gate));
        }
        order.setStatus("VERIFYING");
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return detail(id);
    }

    /** 核验通过：版本确认（服务端角色判定）+ 委托推进到「出证」同事务完成 */
    @Transactional
    public Views.OrderView confirmRecord(Long id, Long versionId, RawRecordDtos.ConfirmReq req) {
        rawRecordService.confirm(id, versionId, req.getOperator());
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        if (!"VERIFYING".equals(order.getStatus())) {
            throw new BizException("委托当前不在「核验」环节");
        }
        if (order.getConclusion() == null || order.getConclusion().isBlank()) {
            throw new BizException("出证前必须有检定结论（已随确认版本写入）");
        }
        order.setStatus("CERTIFYING");
        if (order.getCertValidUntil() == null) {
            order.setCertValidUntil(defaultValidUntil(order));
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return detail(id);
    }

    /** 核验退回：版本退回留痕 + 派生下一版草稿 + 委托回到「检定中」（标准器已实际使用，占用保留） */
    @Transactional
    public Views.OrderView rejectRecord(Long id, Long versionId, RawRecordDtos.RejectReq req) {
        rawRecordService.reject(id, versionId, req.getOperator(), req.getReason(),
                RawRecordService.EV_REJECT);
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        order.setStatus("CALIBRATING");
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return detail(id);
    }

    /** 出证阶段保存证书编号/有效期（数据来自已确认版本，此处不可改数据） */
    @Transactional
    public Views.OrderView saveCertInfo(Long id, RawRecordDtos.CertInfoReq req) {
        LocalDate validUntil = null;
        if (req.getCertValidUntil() != null && !req.getCertValidUntil().isBlank()) {
            validUntil = LocalDate.parse(req.getCertValidUntil().trim());
        }
        rawRecordService.saveCertInfo(id, req, validUntil);
        return detail(id);
    }

    /** 人员改派 */
    @Transactional
    public Views.OrderView reassign(Long id, RawRecordDtos.ReassignReq req) {
        rawRecordService.reassign(id, req);
        return detail(id);
    }

    /**
     * 推进到下一流转状态。
     *
     * <p>每次推进都按<b>当前时间</b>对占用中的标准器重新做放行校验（服务端，不信任前端）：
     * 进入检定中要求至少挂一件且全部在用、证书未过期；其后各环节只要挂了就必须仍有效。
     * 校验不过逐件点名，事务回滚、状态不变，刷新/重复点击/重新进入页面均无法绕过。
     * 历史上不带标准器的委托（无挂接行）不强制，可继续完成原有流程。
     */
    @Transactional
    public Views.OrderView advance(Long id) {
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("委托已作废，不能继续流转");
        }
        int index = MetroDict.flowIndex(order.getStatus());
        if (index >= MetroDict.FLOW_ORDER.size() - 1) {
            throw new BizException("委托单已处于「已发放」，无法继续推进");
        }
        String next = MetroDict.FLOW_ORDER.get(index + 1);

        // 关键放行点：收样→检定中必须挂接且全部合格；后续每次推进重新按当前时间复核
        OrderDeviceService.GateCheck gate = orderDeviceService.checkGate(id, "RECEIVED".equals(order.getStatus()));
        if (!gate.passed()) {
            throw new BizException(buildGateMessage(next, gate));
        }

        if ("CALIBRATING".equals(next)) {
            // 进入检定环节：没有任何原始记录的委托自动建空白草稿（有旧文本/已有版本的不重复建）
            rawRecordService.ensureDraftOnCalibrating(id);
        }
        if ("VERIFYING".equals(next)) {
            // 通用推进按钮不允许跳过「提交复核」：必须先由检定员提交锁定版本
            throw new BizException("进入核验必须由检定员先「提交复核」锁定原始记录版本，不能直接推进；"
                    + "请在原始记录面板填写操作人后提交");
        }
        if ("CERTIFYING".equals(next)) {
            throw new BizException("进入出证必须由指派核验员对锁定版本「确认通过」，不能直接推进");
        }
        if ("ISSUED".equals(next)) {
            if (order.getCertNo() == null || order.getCertNo().isBlank()) {
                throw new BizException("发放前必须完成出证并填写证书编号");
            }
            if (order.getCurrentRecordVersionId() == null && order.getMeasuredData() == null) {
                throw new BizException("发放前必须有核验通过的原始记录版本（或升级前的历史实测数据）");
            }
            // 发放瞬间固定证书引用版本；旧文本委托无结构化版本时放行（继续读旧文本）
            rawRecordService.pinCertVersionOnIssue(id);
        }
        order.setStatus(next);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        if ("ISSUED".equals(next)) {
            closeInstrumentCycle(order);
            // 最终发放：占用释放，挂接事实作为历史留痕保留（重新打开仍可查）
            orderDeviceService.releaseForIssue(id);
        }
        return detail(id);
    }

    private String buildGateMessage(String nextStatus, OrderDeviceService.GateCheck gate) {
        String where = MetroDict.flowStatusName(nextStatus);
        StringBuilder sb = new StringBuilder("不能进入「").append(where).append("」：");
        if (gate.noDevice()) {
            sb.append("尚未挂接参与本次检定的标准器，请先在收样环节选择标准器");
        } else {
            sb.append("标准器放行校验未通过（按 ").append(LocalDate.now()).append(" 复核）——");
            sb.append(String.join("；", gate.reasons()));
        }
        return sb.toString();
    }

    /**
     * 回退一格流转状态。
     * <ul>
     *   <li>检定中→收样：标准器尚未真正使用，占用随之释放；未提交的草稿作为检定工作产物保留；</li>
     *   <li>核验→检定中：<b>不走这里</b>，核验员必须写明退回原因（rejectRecord），由系统派生下一版草稿；</li>
     *   <li>出证→核验：打回重核，当前确认版本退回留痕、派生下一版草稿，回到检定中修订后重走核验。</li>
     * </ul>
     */
    @Transactional
    public Views.OrderView rollback(Long id) {
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("委托已作废，无需回退");
        }
        int index = MetroDict.flowIndex(order.getStatus());
        if (index <= 0) {
            throw new BizException("已处于流转首环节「收样」，无法再回退");
        }
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("已发放的委托单不能回退，如需更正请另开委托单");
        }
        if ("VERIFYING".equals(order.getStatus())) {
            throw new BizException("核验环节不能直接回退：核验员只能「确认通过」或「写明原因退回」，"
                    + "退回后系统会自动生成下一版草稿，旧版本与退回意见全程留痕");
        }
        if ("CERTIFYING".equals(order.getStatus())) {
            throw new BizException("出证环节打回请使用「打回重核」并填写原因（原始记录面板），"
                    + "系统会把当前确认版本退回并派生下一版草稿");
        }
        boolean backToReceived = "CALIBRATING".equals(order.getStatus());
        order.setStatus(MetroDict.FLOW_ORDER.get(index - 1));
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        if (backToReceived) {
            orderDeviceService.releaseForRollback(id);
        }
        return detail(id);
    }

    /** 出证打回重核：当前确认版本退回留痕、派生下一版草稿、委托回到检定中 */
    @Transactional
    public Views.OrderView reopenFromCertifying(Long id, Long versionId, RawRecordDtos.RejectReq req) {
        rawRecordService.reject(id, versionId, req.getOperator(), req.getReason(),
                RawRecordService.EV_CERT_REOPEN);
        CalibrationOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + id));
        if (!"CERTIFYING".equals(order.getStatus())) {
            throw new BizException("只有出证环节可以打回重核");
        }
        order.setStatus("CALIBRATING");
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return detail(id);
    }

    /** 作废/退回收样后器具台账恢复在用（委托不再占用该器具） */
    private void restoreInstrument(CalibrationOrder order) {
        if (order.getInstrumentId() == null) {
            return;
        }
        Instrument instrument = instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        if (instrument != null && "IN_CALIBRATION".equals(instrument.getStatus())) {
            instrument.setStatus("IN_USE");
            instrument.setUpdatedAt(LocalDateTime.now());
            instrumentRepository.save(instrument);
        }
    }

    /** 发放后回写器具台账：更新上次检定日期与下次到期日 */
    private void closeInstrumentCycle(CalibrationOrder order) {
        if (order.getInstrumentId() == null) {
            return;
        }
        Instrument instrument = instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        if (instrument == null) {
            return;
        }
        LocalDate calDate = order.getReceiveDate() == null ? LocalDate.now() : order.getReceiveDate();
        int cycle = instrument.getCycleMonths() == null ? 12 : instrument.getCycleMonths();
        instrument.setLastCalDate(calDate);
        instrument.setNextDueDate(calDate.plusMonths(cycle));
        instrument.setStatus("IN_USE");
        instrument.setUpdatedAt(LocalDateTime.now());
        instrumentRepository.save(instrument);
    }

    private LocalDate defaultValidUntil(CalibrationOrder order) {
        Instrument instrument = order.getInstrumentId() == null ? null
                : instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        int cycle = instrument == null || instrument.getCycleMonths() == null ? 12 : instrument.getCycleMonths();
        LocalDate from = order.getReceiveDate() == null ? LocalDate.now() : order.getReceiveDate();
        return from.plusMonths(cycle);
    }

    private CalibrationOrder require(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("委托单不存在：id=" + id));
    }

    /** 批量取「当前确认 / 证书固定」指针版本号：版本 id → 版本号 */
    private Map<Long, Integer> currentVersionNos(List<CalibrationOrder> orders) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (CalibrationOrder o : orders) {
            if (o.getCurrentRecordVersionId() != null) {
                ids.add(o.getCurrentRecordVersionId());
            }
            if (o.getCertRecordVersionId() != null) {
                ids.add(o.getCertRecordVersionId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> map = new HashMap<>();
        for (RawRecordVersion v : recordVersionRepository.findByIdIn(ids)) {
            map.put(v.getId(), v.getVersionNo());
        }
        return map;
    }

    private Views.OrderView toView(CalibrationOrder order, ViewContext context,
                                   Map<Long, OrderDeviceService.LinkSummary> summaries,
                                   boolean detail, boolean blocked,
                                   Map<Long, Integer> versionNoMap) {
        return toView(order, context,
                summaries.getOrDefault(order.getId(), new OrderDeviceService.LinkSummary(0, null)),
                detail,
                blocked
                        ? new OrderDeviceService.GateCheck(false, false, null, List.of("标准器当前不满足放行条件，详见详情"))
                        : OrderDeviceService.GateCheck.pass(),
                List.of(), List.of(), null, versionNoMap);
    }

    private Views.OrderView toView(CalibrationOrder order, ViewContext context,
                                   OrderDeviceService.LinkSummary summary,
                                   boolean detail,
                                   OrderDeviceService.GateCheck gate,
                                   List<Views.OrderDeviceView> devices,
                                   List<Views.DeviceLinkHistoryView> history,
                                   Views.RecordPanelView panel) {
        Map<Long, Integer> versionNoMap = new HashMap<>();
        if (order.getCurrentRecordVersionId() != null
                || order.getCertRecordVersionId() != null) {
            currentVersionNos(List.of(order)).forEach(versionNoMap::putIfAbsent);
        }
        return toView(order, context, summary, detail, gate, devices, history, panel, versionNoMap);
    }

    private Views.OrderView toView(CalibrationOrder order, ViewContext context,
                                   OrderDeviceService.LinkSummary summary,
                                   boolean detail,
                                   OrderDeviceService.GateCheck gate,
                                   List<Views.OrderDeviceView> devices,
                                   List<Views.DeviceLinkHistoryView> history,
                                   Views.RecordPanelView panel,
                                   Map<Long, Integer> versionNoMap) {
        Instrument instrument = context.instrumentMap.get(order.getInstrumentId());
        var procedure = context.procedureMap.get(order.getProcedureId());
        boolean voided = MetroDict.STATUS_VOIDED.equals(order.getStatus());
        return new Views.OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getClientId(),
                context.clientName.getOrDefault(order.getClientId(), ""),
                order.getInstrumentId(),
                order.getInstNo(),
                instrument == null ? "" : instrument.getName(),
                instrument == null ? "" : instrument.getModelSpec(),
                instrument == null ? "" : instrument.getMeasureRange(),
                instrument == null ? "" : instrument.getAccuracyGrade(),
                instrument == null ? "" : instrument.getManufacturer(),
                instrument == null ? "" : instrument.getFactoryNo(),
                order.getProcedureId(),
                procedure == null ? "" : procedure.getProcNo(),
                procedure == null ? "" : procedure.getName(),
                procedure == null ? null : procedure.getUnitFee(),
                order.getReceiveDate(),
                order.getVerifier(),
                order.getChecker(),
                order.getMeasuredData(),
                order.getConclusion(),
                MetroDict.conclusionName(order.getConclusion()),
                order.getCertNo(),
                order.getStatus(),
                MetroDict.flowStatusName(order.getStatus()),
                voided ? -1 : MetroDict.flowIndex(order.getStatus()),
                order.getCertValidUntil(),
                order.getFee(),
                order.getRemark(),
                order.getUpdatedAt(),
                summary.count(),
                summary.earliestValidUntil(),
                !gate.passed(),
                gate.passed() ? List.of() : gate.reasons(),
                MetroDict.canEditDevices(order.getStatus()),
                order.getCurrentRecordVersionId() == null ? null
                        : versionNoMap.get(order.getCurrentRecordVersionId()),
                order.getCertRecordVersionId() == null ? null
                        : versionNoMap.get(order.getCertRecordVersionId()),
                order.getCurrentRecordVersionId() == null
                        && blankToNull(order.getMeasuredData()) != null,
                detail ? devices : null,
                detail ? history : null,
                detail ? panel : null);
    }

    /** 一次取全，避免逐条查询 */
    private ViewContext context() {
        Map<Long, Instrument> instrumentMap = new HashMap<>();
        Map<Long, String> instrumentName = new HashMap<>();
        for (Instrument instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            instrumentMap.put(instrument.getId(), instrument);
            instrumentName.put(instrument.getId(), instrument.getName());
        }
        Map<Long, String> clientName = new HashMap<>();
        for (Client client : clientRepository.findAllByOrderByCodeAsc()) {
            clientName.put(client.getId(), client.getName());
        }
        Map<Long, Procedure> procedureMap = new HashMap<>();
        for (Procedure procedure : procedureRepository.findAllByOrderByProcNoAsc()) {
            procedureMap.put(procedure.getId(), procedure);
        }
        return new ViewContext(instrumentMap, instrumentName, clientName, procedureMap);
    }

    private record ViewContext(Map<Long, Instrument> instrumentMap,
                               Map<Long, String> instrumentName,
                               Map<Long, String> clientName,
                               Map<Long, Procedure> procedureMap) {
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static LocalDate parseDate(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(text.trim());
    }
}
