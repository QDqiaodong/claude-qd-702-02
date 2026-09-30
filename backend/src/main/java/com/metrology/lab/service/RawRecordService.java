package com.metrology.lab.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.CalibrationOrder;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.RawRecordItem;
import com.metrology.lab.domain.RawRecordVersion;
import com.metrology.lab.domain.RecordEvent;
import com.metrology.lab.dto.RawRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.RawRecordItemRepository;
import com.metrology.lab.repository.RawRecordVersionRepository;
import com.metrology.lab.repository.RecordEventRepository;

/**
 * 原始检定记录与版本留痕核心服务。
 *
 * <p>四条不可绕过的底线（全部在服务端、在委托行悲观锁内判定）：
 * <ol>
 *   <li><b>冻结即不可变</b>：版本离开 DRAFT 后不存在修改其明细/结论的服务端路径；
 *       退回重提一律生成新版本，旧版、退回意见、提交人与时间持续可查。</li>
 *   <li><b>角色分离</b>：提交人必须是委托上指派的检定员，确认/退回必须是指派的核验员，
 *       且同一版本 submitter != confirmer；未指派（「待分配」）不能代操作。</li>
 *   <li><b>证书引用固定</b>：发放时把当时的确认版本快照到 order.certRecordVersionId，
 *       证书数据只从该版本读取，后续修订不影响已发放证书。</li>
 *   <li><b>恢复不靠删历史</b>：误提交走撤回、人员变化走改派（留痕）、退回走新版本、
 *       出证打回重核；任何写操作都是追加事件或新增行，从不物理删除版本/明细/事件。</li>
 * </ol>
 */
@Service
public class RawRecordService {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_WITHDRAWN = "WITHDRAWN";
    public static final String ST_SUPERSEDED = "SUPERSEDED";

    public static final String EV_DRAFT_CREATE = "DRAFT_CREATE";
    public static final String EV_CONVERTED = "CONVERTED";
    public static final String EV_SUBMIT = "SUBMIT";
    public static final String EV_CONFIRM = "CONFIRM";
    public static final String EV_REJECT = "REJECT";
    public static final String EV_WITHDRAW = "WITHDRAW";
    public static final String EV_SUPERSEDE = "SUPERSEDE";
    public static final String EV_CERT_REOPEN = "CERT_REOPEN";
    public static final String EV_ASSIGN = "ASSIGN";
    public static final String EV_CERT_FIXED = "CERT_FIXED";

    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_REJECT = "REJECT";
    public static final String ORIGIN_WITHDRAW = "WITHDRAW";
    public static final String ORIGIN_LEGACY = "LEGACY";

    private static final Set<String> ACTIVE_EDIT_STATUSES =
            Set.of("RECEIVED", "CALIBRATING");

    private final CalibrationOrderRepository orderRepository;
    private final RawRecordVersionRepository versionRepository;
    private final RawRecordItemRepository itemRepository;
    private final RecordEventRepository eventRepository;

    public RawRecordService(CalibrationOrderRepository orderRepository,
                            RawRecordVersionRepository versionRepository,
                            RawRecordItemRepository itemRepository,
                            RecordEventRepository eventRepository) {
        this.orderRepository = orderRepository;
        this.versionRepository = versionRepository;
        this.itemRepository = itemRepository;
        this.eventRepository = eventRepository;
    }

    // ============================ 面板聚合（委托详情） ============================

    @Transactional(readOnly = true)
    public Views.RecordPanelView panel(Long orderId) {
        CalibrationOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + orderId));
        return buildPanel(order);
    }

    private Views.RecordPanelView buildPanel(CalibrationOrder order) {
        Long orderId = order.getId();
        List<RawRecordVersion> versions = versionRepository.findByOrderIdOrderByVersionNoDescIdAsc(orderId);
        List<Long> versionIds = versions.stream().map(RawRecordVersion::getId).toList();

        Map<Long, List<RawRecordItem>> itemMap = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (RawRecordItem item : itemRepository.findByVersionIds(versionIds)) {
                itemMap.computeIfAbsent(item.getVersionId(), k -> new ArrayList<>()).add(item);
            }
        }
        List<RecordEvent> events = eventRepository.findByOrderIdOrderByIdAsc(orderId);

        RawRecordVersion draft = null;
        RawRecordVersion current = null;
        List<Views.RecordVersionView> versionViews = new ArrayList<>();
        for (RawRecordVersion v : versions) {
            // 同一委托至多一条 DRAFT；列表已按版本号倒序，取第一条即可
            if (draft == null && ST_DRAFT.equals(v.getStatus())) {
                draft = v;
            }
            if (v.getId().equals(order.getCurrentRecordVersionId())) {
                current = v;
            }
            versionViews.add(toVersionView(v, itemMap.getOrDefault(v.getId(), List.of()).size(), order));
        }

        String legacyText = blankToNull(order.getMeasuredData());
        String status = order.getStatus();
        boolean voided = MetroDict.STATUS_VOIDED.equals(status);
        boolean issued = "ISSUED".equals(status);

        String stage;
        if (draft != null) {
            stage = "DRAFTING";
        } else if (versions.stream().anyMatch(v -> ST_SUBMITTED.equals(v.getStatus()))) {
            stage = "IN_REVIEW";
        } else if (current != null) {
            stage = "CONFIRMED";
        } else if (legacyText != null) {
            stage = "LEGACY";
        } else {
            stage = "NONE";
        }

        // 服务端最终判定的操作权限
        boolean canEdit = !voided && !issued
                && ACTIVE_EDIT_STATUSES.contains(status) && draft != null;
        boolean canSubmit = canEdit;
        boolean canConfirm = !voided && "VERIFYING".equals(status)
                && versions.stream().anyMatch(v -> ST_SUBMITTED.equals(v.getStatus()));
        boolean canReject = canConfirm;
        // 撤回仅限：核验环节、待核验版本尚未被核验员处理，由检定员本人发起（身份在 service 动作里再判）
        boolean canWithdraw = !voided && !issued && "VERIFYING".equals(status)
                && versions.stream().anyMatch(v -> ST_SUBMITTED.equals(v.getStatus()));
        boolean legacyConvertible = !voided && !issued && versions.isEmpty()
                && legacyText != null && ACTIVE_EDIT_STATUSES.contains(status);
        boolean canReassign = !voided && !issued
                && ("RECEIVED".equals(status) || "CALIBRATING".equals(status) || "VERIFYING".equals(status));
        boolean canEditCert = !voided && "CERTIFYING".equals(status);

        List<Views.RecordEventView> eventViews = events.stream().map(this::toEventView).toList();

        Views.RecordVersionView draftView = draft == null ? null
                : toVersionView(draft, itemMap.getOrDefault(draft.getId(), List.of()).size(), order);
        List<Views.RecordItemView> draftItems = draft == null ? List.of()
                : itemMap.getOrDefault(draft.getId(), List.of()).stream().map(this::toItemView).toList();
        Views.RecordVersionView currentView = current == null ? null
                : toVersionView(current, itemMap.getOrDefault(current.getId(), List.of()).size(), order);

        return new Views.RecordPanelView(
                stage, stageName(stage),
                legacyText,
                legacyConvertible,
                draftView, draftItems,
                currentView, versionViews, eventViews,
                certData(order, itemMap),
                order.getCurrentRecordVersionId(), order.getCertRecordVersionId(),
                canEdit, canSubmit, canConfirm, canReject, canWithdraw,
                legacyConvertible, canReassign, canEditCert);
    }

    // ============================ 草稿：建 / 保存 ============================

    /** 进入检定中时由流转服务调用：若无任何版本且无旧文本则自动建空白草稿（不重复建） */
    @Transactional
    public void ensureDraftOnCalibrating(Long orderId) {
        CalibrationOrder order = lockOrder(orderId);
        List<RawRecordVersion> versions = versionRepository.findByOrderIdOrderByVersionNoDescIdAsc(orderId);
        if (versions.isEmpty() && blankToNull(order.getMeasuredData()) == null) {
            createDraft(order, 1, List.of(), null, null, ORIGIN_MANUAL, "系统",
                    "进入检定环节，自动建立空白原始记录草稿");
        }
    }

    /** 检定员主动新建草稿（收样/检定中、当前无草稿） */
    @Transactional
    public Views.RecordPanelView createDraft(Long orderId, String operatorRaw) {
        String operator = requireOperator(operatorRaw);
        CalibrationOrder order = lockOrder(orderId);
        requireEditableStage(order, "建立原始记录草稿");
        requireVerifier(order, operator);
        List<RawRecordVersion> versions = versionRepository.findByOrderIdOrderByVersionNoDescIdAsc(orderId);
        if (versions.stream().anyMatch(v -> ST_DRAFT.equals(v.getStatus()))) {
            throw new BizException("当前已有草稿版本，请直接在草稿上录入或保存");
        }
        int nextNo = versions.stream().mapToInt(RawRecordVersion::getVersionNo).max().orElse(0) + 1;
        createDraft(order, nextNo, List.of(), null, null, ORIGIN_MANUAL, operator,
                "检定员建立第 " + nextNo + " 版原始记录草稿");
        return buildPanel(order);
    }

    /** 草稿整版保存：批量增删/复制/粘贴后全量提交，仅草稿、仅指派检定员本人 */
    @Transactional
    public Views.RecordPanelView saveDraft(Long orderId, Long versionId, RawRecordDtos.DraftSaveReq req) {
        String operator = requireOperator(req.getOperator());
        CalibrationOrder order = lockOrder(orderId);
        requireEditableStage(order, "录入或修改检定数据");
        requireVerifier(order, operator);
        RawRecordVersion version = requireVersion(order, versionId);
        if (!ST_DRAFT.equals(version.getStatus())) {
            throw new BizException("第 " + version.getVersionNo() + " 版已提交复核并锁定，不能再修改；"
                    + "如被退回请在系统生成的新版草稿上修订");
        }
        if (req.getConclusion() != null && !req.getConclusion().isBlank()
                && !MetroDict.isValidConclusion(req.getConclusion())) {
            throw new BizException("检定结论只能是 PASS / LIMITED / FAIL");
        }
        List<RawRecordItem> items = normalizeItems(req.getItems());
        if (items.size() > 200) {
            throw new BizException("单次最多保存 200 条数据项，请拆分后再保存");
        }
        version.setConclusion(blankToNull(req.getConclusion()));
        version.setRemark(trim(req.getRemark(), 500));
        version.setUpdatedAt(LocalDateTime.now());
        versionRepository.save(version);

        // 草稿允许反复整盘重写：先删后插不影响历史 —— 草稿未提交，明细尚无外部引用，
        // 冻结版本的明细从不走这条路径（上面已按状态拦截）。
        List<RawRecordItem> old = itemRepository.findByVersionIdOrderByLineNoAscIdAsc(versionId);
        itemRepository.deleteAll(old);
        itemRepository.flush();
        for (RawRecordItem item : items) {
            item.setVersionId(version.getId());
            itemRepository.save(item);
        }
        return buildPanel(order);
    }

    // ============================ 提交复核（版本冻结） ============================

    @Transactional
    public Views.RecordPanelView submit(Long orderId, Long versionId, String operatorRaw) {
        String operator = requireOperator(operatorRaw);
        CalibrationOrder order = lockOrder(orderId);
        requireNotTerminal(order);
        requireVerifier(order, operator);
        RawRecordVersion version = requireVersion(order, versionId);
        if (!ST_DRAFT.equals(version.getStatus())) {
            throw new BizException("只有草稿版本可以提交复核，第 " + version.getVersionNo() + " 版当前状态为「"
                    + MetroDict.recordStatusName(version.getStatus()) + "」");
        }
        if (!"CALIBRATING".equals(order.getStatus()) && !"RECEIVED".equals(order.getStatus())) {
            throw new BizException("委托当前处于「" + MetroDict.flowStatusName(order.getStatus())
                    + "」，不能再提交原始记录");
        }
        if (!MetroDict.isAssigned(order.getChecker())) {
            throw new BizException("尚未指派核验员，不能提交复核；请先由具备权限的人员改派核验员后再提交");
        }
        if (Objects.equals(MetroDict.normalizeName(order.getVerifier()),
                MetroDict.normalizeName(order.getChecker()))) {
            throw new BizException("检定员与核验员不能为同一人，请先改派核验员");
        }
        List<RawRecordItem> items = itemRepository.findByVersionIdOrderByLineNoAscIdAsc(versionId);
        if (items.isEmpty()) {
            throw new BizException("原始记录没有任何数据项，数据项缺失不能提交复核，请补录后再提交");
        }
        for (RawRecordItem item : items) {
            if (isBlank(item.getCalPoint()) && isBlank(item.getStandardValue())
                    && isBlank(item.getIndicatedValue()) && isBlank(item.getErrorValue())) {
                throw new BizException("第 " + item.getLineNo() + " 行为空行，请删除或补全后再提交");
            }
        }
        if (!MetroDict.isValidConclusion(version.getConclusion())) {
            throw new BizException("提交复核前必须给出检定结论（合格 / 限用 / 不合格）");
        }

        LocalDateTime now = LocalDateTime.now();
        version.setStatus(ST_SUBMITTED);
        version.setSubmittedBy(operator);
        version.setSubmittedAt(now);
        version.setUpdatedAt(now);
        versionRepository.save(version);
        order.setVerifier(MetroDict.normalizeName(order.getVerifier()));
        order.setUpdatedAt(now);
        orderRepository.save(order);
        addEvent(order.getId(), version, EV_SUBMIT, operator,
                "第 " + version.getVersionNo() + " 版提交复核，共 " + items.size() + " 条数据项");

        // 提交复核同时把委托推进到「核验」（同一事务，过标准器放行关）
        // 流转推进由 CalibrationOrderService 在外部编排，避免与该服务的标准器逻辑重复；
        // 这里只冻结版本。委托状态切换交给控制器调用 advanceToVerifying。
        return buildPanel(order);
    }

    // ============================ 核验：通过 / 退回 / 撤回 ============================

    @Transactional
    public Views.RecordPanelView confirm(Long orderId, Long versionId, String operatorRaw) {
        String operator = requireOperator(operatorRaw);
        CalibrationOrder order = lockOrder(orderId);
        RawRecordVersion version = requireVersion(order, versionId);
        requireChecker(order, operator);
        if (!"VERIFYING".equals(order.getStatus())) {
            throw new BizException("委托当前不在「核验」环节，不能核验原始记录");
        }
        if (!ST_SUBMITTED.equals(version.getStatus())) {
            throw new BizException("第 " + version.getVersionNo() + " 版不是「待核验」状态（当前："
                    + MetroDict.recordStatusName(version.getStatus()) + "），不能确认");
        }
        if (Objects.equals(operator, MetroDict.normalizeName(version.getSubmittedBy()))) {
            throw new BizException("提交人与核验人不能为同一人：本版由 " + version.getSubmittedBy()
                    + " 提交，必须由另一名核验员确认");
        }
        LocalDateTime now = LocalDateTime.now();

        // 旧确认版本降级为 SUPERSEDED（数据全部保留，仍可查可对比）；逐实体更新并逐条补留痕
        List<RawRecordVersion> previous = versionRepository.findByOrderIdAndStatus(orderId, ST_CONFIRMED);
        for (RawRecordVersion old : previous) {
            old.setStatus(ST_SUPERSEDED);
            old.setUpdatedAt(now);
            versionRepository.save(old);
        }
        version.setStatus(ST_CONFIRMED);
        version.setConfirmedBy(operator);
        version.setConfirmedAt(now);
        version.setUpdatedAt(now);
        versionRepository.save(version);

        order.setCurrentRecordVersionId(version.getId());
        order.setConclusion(version.getConclusion());
        order.setUpdatedAt(now);
        orderRepository.save(order);

        addEvent(orderId, version, EV_CONFIRM, operator,
                "核验通过，第 " + version.getVersionNo() + " 版确认为出证依据"
                        + (!previous.isEmpty() ? "；原确认版本标记为「已替代」并保留可查" : ""));
        for (RawRecordVersion old : previous) {
            addEvent(orderId, old, EV_SUPERSEDE, operator,
                    "第 " + version.getVersionNo() + " 版核验通过，本版被替代（只读保留，不再作为出证依据）");
        }
        return buildPanel(order);
    }

    @Transactional
    public Views.RecordPanelView reject(Long orderId, Long versionId,
                                       String operatorRaw, String reasonRaw, String eventType) {
        String operator = requireOperator(operatorRaw);
        String reason = requireReason(reasonRaw);
        CalibrationOrder order = lockOrder(orderId);
        RawRecordVersion version = requireVersion(order, versionId);
        requireChecker(order, operator);
        boolean fromReview = "VERIFYING".equals(order.getStatus()) && ST_SUBMITTED.equals(version.getStatus());
        boolean fromCertify = "CERTIFYING".equals(order.getStatus())
                && ST_CONFIRMED.equals(version.getStatus()) && version.getId().equals(order.getCurrentRecordVersionId());
        String actionName = EV_CERT_REOPEN.equals(eventType) ? "出证打回重核" : "核验退回";
        if (EV_CERT_REOPEN.equals(eventType)) {
            if (!fromCertify) {
                throw new BizException("只有出证环节当前引用的已确认版本可以打回重核");
            }
        } else if (!fromReview) {
            throw new BizException("只有「核验」环节的待核验版本可以退回（当前环节："
                    + MetroDict.flowStatusName(order.getStatus()) + "，版本状态："
                    + MetroDict.recordStatusName(version.getStatus()) + "）");
        }
        if (Objects.equals(operator, MetroDict.normalizeName(version.getSubmittedBy()))) {
            throw new BizException("提交人与核验人不能为同一人，退回也必须由另一名核验员操作");
        }

        LocalDateTime now = LocalDateTime.now();
        version.setStatus(ST_REJECTED);
        version.setRejectedBy(operator);
        version.setRejectedAt(now);
        version.setRejectReason(reason);
        version.setUpdatedAt(now);
        versionRepository.save(version);

        // 退回/打回：当前生效指针悬空（出证打回期间没有确认版本），派生下一版草稿并整版复制数据项
        if (fromCertify) {
            order.setCurrentRecordVersionId(null);
        }
        order.setUpdatedAt(now);
        orderRepository.save(order);

        addEvent(orderId, version, eventType, operator,
                actionName + "：" + reason + "（系统已生成第 " + (version.getVersionNo() + 1) + " 版草稿承接数据）");

        List<RawRecordItem> items = itemRepository.findByVersionIdOrderByLineNoAscIdAsc(versionId);
        RawRecordVersion draft = createDraft(order, version.getVersionNo() + 1,
                items, version.getConclusion(), version.getRemark(), ORIGIN_REJECT, operator,
                actionName + "派生：复制第 " + version.getVersionNo() + " 版全部数据项到新草稿");
        return buildPanel(order);
    }

    /** 误提交恢复：检定员在核验员处理前撤回，原版本留痕为 WITHDRAWN，同号草稿承接（不占新版本号） */
    @Transactional
    public Views.RecordPanelView withdraw(Long orderId, Long versionId, String operatorRaw) {
        String operator = requireOperator(operatorRaw);
        CalibrationOrder order = lockOrder(orderId);
        requireVerifier(order, operator);
        RawRecordVersion version = requireVersion(order, versionId);
        if (!ST_SUBMITTED.equals(version.getStatus())) {
            throw new BizException("只有待核验的版本可以撤回，第 " + version.getVersionNo() + " 版当前为「"
                    + MetroDict.recordStatusName(version.getStatus()) + "」");
        }
        if (!"VERIFYING".equals(order.getStatus())) {
            throw new BizException("当前不在「核验」环节，无需撤回");
        }
        if (version.getConfirmedAt() != null || version.getRejectedAt() != null) {
            throw new BizException("核验员已处理该版本，不能撤回；被退回时请在新版草稿上修订");
        }
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(ST_WITHDRAWN);
        version.setUpdatedAt(now);
        versionRepository.save(version);
        addEvent(orderId, version, EV_WITHDRAW, operator,
                "检定员撤回第 " + version.getVersionNo() + " 版提交（误提交恢复，原提交记录保留可查）");

        // 撤回后委托同步回到检定中（标准器已实际使用，占用不释放），检定员在草稿修订后重新提交
        order.setStatus("CALIBRATING");
        order.setUpdatedAt(now);
        orderRepository.save(order);

        List<RawRecordItem> items = itemRepository.findByVersionIdOrderByLineNoAscIdAsc(versionId);
        createDraft(order, version.getVersionNo(), items, version.getConclusion(), version.getRemark(),
                ORIGIN_WITHDRAW, operator, "撤回承接：第 " + version.getVersionNo() + " 版回到草稿，数据原样保留");
        return buildPanel(order);
    }

    // ============================ 旧文本兼容：只读 + 转第一版 ============================

    @Transactional
    public Views.RecordPanelView convertLegacy(Long orderId, RawRecordDtos.ConvertLegacyReq req) {
        String operator = requireOperator(req.getOperator());
        CalibrationOrder order = lockOrder(orderId);
        String legacy = blankToNull(order.getMeasuredData());
        if (legacy == null) {
            throw new BizException("该委托没有旧文本实测数据，无需转换");
        }
        if (!versionRepository.findByOrderIdOrderByVersionNoDescIdAsc(orderId).isEmpty()) {
            throw new BizException("该委托已存在结构化原始记录，旧文本仅作历史留痕展示，不能重复转换");
        }
        if (!ACTIVE_EDIT_STATUSES.contains(order.getStatus()) || MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("只有尚未进入核验（收样/检定中）的委托可以转为第一版；"
                    + "已核验或已发放的委托只能只读保留旧数据，升级不会改变原结论");
        }
        requireVerifier(order, operator);

        List<RawRecordItem> items = parseLegacyText(legacy);
        LocalDateTime now = LocalDateTime.now();
        RawRecordVersion draft = createDraft(order, 1, items,
                MetroDict.isValidConclusion(order.getConclusion()) ? order.getConclusion() : null,
                null, ORIGIN_LEGACY, operator,
                "旧文本实测数据转为第 1 版结构化草稿（解析出 " + items.size()
                        + " 条；原文本原样保留并标记为历史记录），请核对后再提交复核");
        addEvent(orderId, draft, EV_CONVERTED, operator,
                "升级前以文本保存的实测数据转换为结构化记录第 1 版草稿，原始文本继续只读保留");
        order.setUpdatedAt(now);
        orderRepository.save(order);
        return buildPanel(order);
    }

    /**
     * 解析旧文本：{@code ;} 分条、{@code |} 分列（校准点|标准值|示值|示值误差）。
     * 旧格式没有独立单位/备注列，单位留空由检定员补；解析不合法也不丢数据 —— 原样塞校准点列。
     */
    public static List<RawRecordItem> parseLegacyText(String legacy) {
        List<RawRecordItem> items = new ArrayList<>();
        int lineNo = 0;
        for (String part : legacy.split(";")) {
            String line = part.trim();
            if (line.isEmpty()) {
                continue;
            }
            lineNo++;
            String[] cols = line.split("\\|", -1);
            RawRecordItem item = new RawRecordItem();
            item.setLineNo(lineNo);
            if (cols.length >= 4) {
                item.setCalPoint(trim(cols[0], 80));
                item.setStandardValue(trim(cols[1], 60));
                item.setIndicatedValue(trim(cols[2], 60));
                item.setErrorValue(trim(cols[3], 60));
            } else {
                item.setCalPoint(trim(line, 80));
                item.setRemark("旧文本行格式与「校准点|标准值|示值|误差」不一致，已原样保留请人工核对");
            }
            items.add(item);
        }
        return items;
    }

    /** 任意版本（含已冻结/已退回/已替代/已发放固定版本）的只读明细，供详情页查看旧版数据 */
    @Transactional(readOnly = true)
    public List<Views.RecordItemView> versionItems(Long orderId, Long versionId) {
        requireOrderExists(orderId);
        RawRecordVersion version = versionRepository.findByIdAndOrderId(versionId, orderId)
                .orElseThrow(() -> new BizException("原始记录版本不存在或不属于本委托：id=" + versionId));
        return itemRepository.findByVersionIdOrderByLineNoAscIdAsc(version.getId()).stream()
                .map(this::toItemView).toList();
    }

    private void requireOrderExists(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new BizException("委托单不存在：id=" + orderId);
        }
    }

    // ============================ 版本对比 ============================

    @Transactional(readOnly = true)
    public Views.RecordDiffView diff(Long orderId, Long baseVersionId, Long compareVersionId) {
        CalibrationOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + orderId));
        RawRecordVersion base = versionRepository.findByIdAndOrderId(baseVersionId, orderId)
                .orElseThrow(() -> new BizException("基准版本不存在或不属于本委托"));
        RawRecordVersion compare = versionRepository.findByIdAndOrderId(compareVersionId, orderId)
                .orElseThrow(() -> new BizException("对比版本不存在或不属于本委托"));
        List<RawRecordItem> baseItems = itemRepository.findByVersionIdOrderByLineNoAscIdAsc(baseVersionId);
        List<RawRecordItem> compareItems = itemRepository.findByVersionIdOrderByLineNoAscIdAsc(compareVersionId);
        return buildDiff(base.getVersionNo(), compare.getVersionNo(), baseItems, compareItems);
    }

    /** 以校准点为主键做 LCS 对齐：匹配上的逐字段比 MODIFIED，未匹配的左删右增 */
    Views.RecordDiffView buildDiff(int baseNo, int compareNo,
                                  List<RawRecordItem> baseItems, List<RawRecordItem> compareItems) {
        int n = baseItems.size();
        int m = compareItems.size();
        // LCS on calPoint（空校准点用整行内容兜底，避免空键错误对齐）
        String[] bk = new String[n];
        String[] ck = new String[m];
        for (int i = 0; i < n; i++) {
            bk[i] = rowKey(baseItems.get(i));
        }
        for (int j = 0; j < m; j++) {
            ck[j] = rowKey(compareItems.get(j));
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = Objects.equals(bk[i], ck[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Views.DiffEntryView> entries = new ArrayList<>();
        int i = 0;
        int j = 0;
        int added = 0;
        int removed = 0;
        int modified = 0;
        int same = 0;
        while (i < n || j < m) {
            if (i < n && j < m && Objects.equals(bk[i], ck[j])) {
                RawRecordItem b = baseItems.get(i);
                RawRecordItem c = compareItems.get(j);
                List<String> changed = changedFields(b, c);
                if (changed.isEmpty()) {
                    same++;
                    entries.add(new Views.DiffEntryView("SAME", List.of(), null, toDiffRow(c)));
                } else {
                    modified++;
                    entries.add(new Views.DiffEntryView("MODIFIED", changed, toDiffRow(b), toDiffRow(c)));
                }
                i++;
                j++;
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                added++;
                entries.add(new Views.DiffEntryView("ADDED", List.of(), null, toDiffRow(compareItems.get(j))));
                j++;
            } else {
                removed++;
                entries.add(new Views.DiffEntryView("REMOVED", List.of(), toDiffRow(baseItems.get(i)), null));
                i++;
            }
        }
        return new Views.RecordDiffView(baseNo, compareNo, entries, added, removed, modified, same);
    }

    private static List<String> changedFields(RawRecordItem b, RawRecordItem c) {
        List<String> fields = new ArrayList<>();
        if (!eq(b.getStandardValue(), c.getStandardValue())) {
            fields.add("standardValue");
        }
        if (!eq(b.getIndicatedValue(), c.getIndicatedValue())) {
            fields.add("indicatedValue");
        }
        if (!eq(b.getErrorValue(), c.getErrorValue())) {
            fields.add("errorValue");
        }
        if (!eq(b.getUnit(), c.getUnit())) {
            fields.add("unit");
        }
        if (!eq(b.getRemark(), c.getRemark())) {
            fields.add("remark");
        }
        return fields;
    }

    private static boolean eq(String a, String b) {
        return Objects.equals(a == null ? "" : a.trim(), b == null ? "" : b.trim());
    }

    private String rowKey(RawRecordItem item) {
        String key = item.getCalPoint();
        if (key == null || key.isBlank()) {
            key = String.join("|", nz(item.getStandardValue()), nz(item.getIndicatedValue()), nz(item.getErrorValue()));
        }
        return key.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ============================ 人员改派 / 证书信息 / 发放固定 ============================

    @Transactional
    public Views.RecordPanelView reassign(Long orderId, RawRecordDtos.ReassignReq req) {
        String operator = requireOperator(req.getOperator());
        CalibrationOrder order = lockOrder(orderId);
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus()) || "ISSUED".equals(order.getStatus())) {
            throw new BizException("已作废或已发放的委托不能改派人员；如确需更正请另开委托单并在备注中引用原单号");
        }
        String status = order.getStatus();
        String newVerifier = req.getVerifier() == null || req.getVerifier().isBlank()
                ? null : MetroDict.normalizeName(req.getVerifier());
        String newChecker = req.getChecker() == null || req.getChecker().isBlank()
                ? null : MetroDict.normalizeName(req.getChecker());
        // 已进入核验后检定员（提交人）不再允许改派，防止「换人顶包」改写提交责任
        if (!"RECEIVED".equals(status) && !"CALIBRATING".equals(status)
                && !sameNullable(newVerifier, MetroDict.normalizeName(order.getVerifier()))) {
            throw new BizException("已进入核验环节，提交责任已固定，不能再改派检定员；如确属人员调整请由所务另案记录");
        }
        if (newVerifier != null && newVerifier.equals(newChecker)) {
            throw new BizException("检定员与核验员不能指派为同一人");
        }
        List<String> changes = new ArrayList<>();
        if (!sameNullable(newVerifier, MetroDict.normalizeName(order.getVerifier()))) {
            changes.add("检定员：" + labelName(order.getVerifier()) + " → " + labelName(newVerifier));
            order.setVerifier(newVerifier);
        }
        if (!sameNullable(newChecker, MetroDict.normalizeName(order.getChecker()))) {
            changes.add("核验员：" + labelName(order.getChecker()) + " → " + labelName(newChecker));
            order.setChecker(newChecker);
        }
        if (changes.isEmpty()) {
            throw new BizException("指派人员没有变化，无需改派");
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        addEvent(orderId, null, EV_ASSIGN, operator, "人员改派 —— " + String.join("；", changes));
        return buildPanel(order);
    }

    /** 出证阶段保存证书编号/有效期（结论与数据已由确认版本固定，这里只动证书字段） */
    @Transactional
    public CalibrationOrder saveCertInfo(Long orderId, RawRecordDtos.CertInfoReq req,
                                         java.time.LocalDate validUntil) {
        String operator = requireOperator(req.getOperator());
        CalibrationOrder order = lockOrder(orderId);
        if (!"CERTIFYING".equals(order.getStatus())) {
            throw new BizException("只有「出证」环节可以保存证书编号与有效期");
        }
        order.setCertNo(blankToNull(req.getCertNo()));
        if (validUntil != null) {
            order.setCertValidUntil(validUntil);
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return order;
    }

    /**
     * 发放瞬间由流转服务调用：把当前确认版本固定为证书引用版本。
     * 已固定后再次调用必须指向同一版本（发放不可重复，状态机本身已挡，双保险）。
     */
    @Transactional
    public void pinCertVersionOnIssue(Long orderId) {
        CalibrationOrder order = lockOrder(orderId);
        Long pinned = order.getCertRecordVersionId();
        Long current = order.getCurrentRecordVersionId();
        boolean hasStructured = current != null;
        if (!hasStructured) {
            // 旧文本委托走原流程：没有版本可固定，证书继续读旧文本（只读）
            return;
        }
        if (pinned != null && !pinned.equals(current)) {
            throw new BizException("证书引用版本与当前确认版本不一致，发放被拒绝（数据一致性保护）");
        }
        if (pinned == null) {
            order.setCertRecordVersionId(current);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);
            RawRecordVersion v = versionRepository.findByIdAndOrderId(current, orderId).orElse(null);
            addEvent(orderId, v, EV_CERT_FIXED, "系统",
                    "证书发放，固定引用第 " + (v == null ? "?" : v.getVersionNo())
                            + " 版原始记录；此后任何修订都不改变本证书数据");
        }
    }

    /** 证书预览/发放读取：优先固定引用版本，其次当前确认版本，最后旧文本 */
    @Transactional(readOnly = true)
    public Views.RecordCertDataView certData(CalibrationOrder order, Map<Long, List<RawRecordItem>> itemMapOrNull) {
        Long pinnedId = order.getCertRecordVersionId();
        Long effectiveId = pinnedId != null ? pinnedId : order.getCurrentRecordVersionId();
        if (effectiveId != null) {
            RawRecordVersion v = versionRepository.findByIdAndOrderId(effectiveId, order.getId()).orElse(null);
            if (v != null) {
                List<RawRecordItem> items = itemMapOrNull == null
                        ? itemRepository.findByVersionIdOrderByLineNoAscIdAsc(v.getId())
                        : itemMapOrNull.getOrDefault(v.getId(), List.of());
                String sourceName = pinnedId != null ? "已固定引用第 " + v.getVersionNo() + " 版（发放后不可变）"
                        : "当前确认第 " + v.getVersionNo() + " 版";
                return new Views.RecordCertDataView("STRUCTURED", sourceName, v.getId(), v.getVersionNo(),
                        MetroDict.recordStatusName(v.getStatus()), v.getSubmittedBy(), v.getConfirmedBy(),
                        v.getConfirmedAt(), items.stream().map(this::toItemView).toList(),
                        null, false);
            }
        }
        String legacy = blankToNull(order.getMeasuredData());
        if (legacy != null) {
            // 已核验/已发放的旧文本必须只读；核验前可转第一版（由 panel 的 canConvertLegacy 控制按钮）
            boolean readonly = !ACTIVE_EDIT_STATUSES.contains(order.getStatus())
                    || MetroDict.STATUS_VOIDED.equals(order.getStatus());
            return new Views.RecordCertDataView("LEGACY_TEXT",
                    readonly ? "升级前历史文本记录（只读保留）" : "升级前历史文本记录（可转第一版结构化记录）",
                    null, null, null, null, null, null, List.of(), legacy, readonly);
        }
        return new Views.RecordCertDataView("NONE", "尚无检定数据", null, null, null,
                null, null, null, List.of(), null, true);
    }

    // ============================ 内部工具 ============================

    /** 加锁加载委托（同一委托的版本操作串行化） */
    private CalibrationOrder lockOrder(Long orderId) {
        return orderRepository.lockById(orderId)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + orderId));
    }

    private RawRecordVersion requireVersion(CalibrationOrder order, Long versionId) {
        if (versionId == null) {
            throw new BizException("缺少版本 id");
        }
        RawRecordVersion version = versionRepository.findByIdAndOrderId(versionId, order.getId())
                .orElseThrow(() -> new BizException("原始记录版本不存在或不属于本委托：id=" + versionId));
        return version;
    }

    private void requireNotTerminal(CalibrationOrder order) {
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("委托已作废，原始记录只读保留，不能操作");
        }
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("委托已发放，证书已固定引用版本，不能再修改原始记录；如需更正请另开委托单");
        }
    }

    private void requireEditableStage(CalibrationOrder order, String action) {
        requireNotTerminal(order);
        if (!ACTIVE_EDIT_STATUSES.contains(order.getStatus())) {
            throw new BizException("委托已进入「" + MetroDict.flowStatusName(order.getStatus())
                    + "」，" + action + "只能在收样/检定中（草稿阶段）进行");
        }
    }

    private void requireVerifier(CalibrationOrder order, String operator) {
        String assigned = MetroDict.normalizeName(order.getVerifier());
        if (assigned == null) {
            throw new BizException("本委托尚未指派检定员，请先由具备权限的人员改派检定员（会留痕）后再操作");
        }
        if (!assigned.equals(operator)) {
            throw new BizException("只有本委托指派的检定员「" + assigned + "」可以操作，当前操作人「"
                    + operator + "」未被指派，不能代操作");
        }
    }

    private void requireChecker(CalibrationOrder order, String operator) {
        String assigned = MetroDict.normalizeName(order.getChecker());
        if (assigned == null) {
            throw new BizException("本委托尚未指派核验员，不能核验；请先改派核验员（会留痕）");
        }
        if (!assigned.equals(operator)) {
            throw new BizException("只有本委托指派的核验员「" + assigned + "」可以核验，当前操作人「"
                    + operator + "」未被指派，不能代操作");
        }
    }

    private String requireOperator(String operator) {
        String name = MetroDict.normalizeName(operator);
        if (name == null) {
            throw new BizException("操作人不能为空，且不能以「待分配」身份代操作");
        }
        return name;
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("退回原因必须填写，检定员需要据此修订");
        }
        return reason.trim();
    }

    private RawRecordVersion createDraft(CalibrationOrder order, int versionNo,
                                         List<RawRecordItem> sourceItems,
                                         String conclusion, String remark, String origin,
                                         String actor, String eventDetail) {
        LocalDateTime now = LocalDateTime.now();
        RawRecordVersion draft = new RawRecordVersion();
        draft.setOrderId(order.getId());
        draft.setVersionNo(versionNo);
        draft.setStatus(ST_DRAFT);
        draft.setConclusion(conclusion);
        draft.setOrigin(origin);
        draft.setRemark(trim(remark, 500));
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        versionRepository.save(draft);

        int lineNo = 0;
        for (RawRecordItem source : sourceItems) {
            lineNo++;
            RawRecordItem item = new RawRecordItem();
            item.setVersionId(draft.getId());
            item.setLineNo(lineNo);
            item.setCalPoint(source.getCalPoint());
            item.setStandardValue(source.getStandardValue());
            item.setIndicatedValue(source.getIndicatedValue());
            item.setErrorValue(source.getErrorValue());
            item.setUnit(source.getUnit());
            item.setRemark(source.getRemark());
            itemRepository.save(item);
        }
        addEvent(order.getId(), draft, EV_DRAFT_CREATE, actor, eventDetail);
        return draft;
    }

    private void addEvent(Long orderId, RawRecordVersion version, String type, String actor, String detail) {
        RecordEvent event = new RecordEvent();
        event.setOrderId(orderId);
        event.setVersionId(version == null ? null : version.getId());
        event.setVersionNo(version == null ? null : version.getVersionNo());
        event.setEventType(type);
        event.setActor(actor);
        event.setDetail(trim(detail, 500));
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    private List<RawRecordItem> normalizeItems(List<RawRecordDtos.ItemReq> raw) {
        List<RawRecordItem> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        int lineNo = 0;
        for (RawRecordDtos.ItemReq r : raw) {
            if (r == null) {
                continue;
            }
            boolean empty = isBlank(r.getCalPoint()) && isBlank(r.getStandardValue())
                    && isBlank(r.getIndicatedValue()) && isBlank(r.getErrorValue())
                    && isBlank(r.getUnit()) && isBlank(r.getRemark());
            if (empty) {
                continue; // 整列空行直接丢弃，行号连续重排（复制/粘贴产生的空行不入库）
            }
            lineNo++;
            RawRecordItem item = new RawRecordItem();
            item.setLineNo(lineNo);
            item.setCalPoint(trim(r.getCalPoint(), 80));
            item.setStandardValue(trim(r.getStandardValue(), 60));
            item.setIndicatedValue(trim(r.getIndicatedValue(), 60));
            item.setErrorValue(trim(r.getErrorValue(), 60));
            item.setUnit(trim(r.getUnit(), 20));
            item.setRemark(trim(r.getRemark(), 200));
            items.add(item);
        }
        return items;
    }

    private Views.RecordItemView toItemView(RawRecordItem item) {
        return new Views.RecordItemView(item.getId(), item.getLineNo(), item.getCalPoint(),
                item.getStandardValue(), item.getIndicatedValue(), item.getErrorValue(),
                item.getUnit(), item.getRemark());
    }

    private Views.DiffRowView toDiffRow(RawRecordItem item) {
        return new Views.DiffRowView(item.getLineNo(), item.getCalPoint(), item.getStandardValue(),
                item.getIndicatedValue(), item.getErrorValue(), item.getUnit(), item.getRemark());
    }

    private Views.RecordEventView toEventView(RecordEvent e) {
        return new Views.RecordEventView(e.getId(), e.getVersionId(), e.getVersionNo(),
                e.getEventType(), MetroDict.recordEventName(e.getEventType()),
                e.getActor(), e.getDetail(), e.getCreatedAt());
    }

    private Views.RecordVersionView toVersionView(RawRecordVersion v, int itemCount, CalibrationOrder order) {
        return new Views.RecordVersionView(
                v.getId(), v.getVersionNo(), v.getStatus(), MetroDict.recordStatusName(v.getStatus()),
                v.getConclusion(), MetroDict.conclusionName(v.getConclusion()),
                v.getSubmittedBy(), v.getSubmittedAt(),
                v.getConfirmedBy(), v.getConfirmedAt(),
                v.getRejectedBy(), v.getRejectedAt(), v.getRejectReason(),
                v.getOrigin(), v.getRemark(), itemCount,
                v.getId().equals(order.getCurrentRecordVersionId()),
                v.getId().equals(order.getCertRecordVersionId()),
                v.getCreatedAt());
    }

    private static String stageName(String stage) {
        return switch (stage) {
            case "NONE" -> "尚未建立原始记录";
            case "LEGACY" -> "仅有升级前历史文本";
            case "DRAFTING" -> "草稿录入中";
            case "IN_REVIEW" -> "待核验（版本已锁定）";
            case "CONFIRMED" -> "已有核验通过版本";
            default -> stage;
        };
    }

    private static String labelName(String name) {
        return MetroDict.isAssigned(name) ? name.trim() : "待分配";
    }

    private static boolean sameNullable(String a, String b) {
        return Objects.equals(a, b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }


    /** 列表展示用：从委托指针直接取版本号（避免 join），在 service 层组装时调用 */
    @Transactional(readOnly = true)
    public Optional<Integer> versionNo(Long versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        return versionRepository.findById(versionId).map(RawRecordVersion::getVersionNo);
    }
}
