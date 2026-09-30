package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.OrderDevice;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.OrderDeviceRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

/**
 * 标准器挂接、占用与放行校验（模块四与模块五之间的闭环核心）。
 *
 * <p>三条不可绕过的底线：
 * <ol>
 *   <li><b>占用互斥在数据库</b>：{@code lab_order_device.active_flag} 唯一键保证同一标准器
 *       全局至多一条占用中行，并发提交只有一笔成功，败方事务整体回滚、委托数据原样保留；</li>
 *   <li><b>放行校验在服务端且每次按当前时间重算</b>：推进任意流转环节都重新查标准器
 *       最新状态与证书有效期，停用 / 送检 / 过期 / 记录异常逐件点名，刷新与重复点击无效；</li>
 *   <li><b>挂接清单只在收样态可改</b>：进入检定中后只能整体释放（退回收样 / 作废 / 发放），
 *       不存在静默替换已使用标准器的路径。</li>
 * </ol>
 */
@Service
public class OrderDeviceService {

    private final OrderDeviceRepository linkRepository;
    private final StandardDeviceRepository deviceRepository;
    private final CalibrationOrderRepository orderRepository;

    public OrderDeviceService(OrderDeviceRepository linkRepository,
                              StandardDeviceRepository deviceRepository,
                              CalibrationOrderRepository orderRepository) {
        this.linkRepository = linkRepository;
        this.deviceRepository = deviceRepository;
        this.orderRepository = orderRepository;
    }

    // ============================ 放行校验 ============================

    /**
     * 按<b>当前时间</b>校验委托占用中的标准器是否全部可放行。
     *
     * @param enforce true = 至少挂一件才放行（检定开始后的推进必须有标准器）；
     *                false = 一件都没挂也允许（兼容不带标准器的历史委托）
     */
    public GateCheck checkGate(Long orderId, boolean enforce) {
        List<OrderDevice> active = linkRepository.findByOrderIdAndReleasedAtIsNull(orderId);
        if (active.isEmpty()) {
            if (enforce) {
                return new GateCheck(false, true, null,
                        List.of("尚未挂接任何标准器，不能开始检定或流转；请先在收样环节选择参与检定的标准器"));
            }
            return GateCheck.pass();
        }
        LocalDate today = LocalDate.now();
        List<Long> ids = active.stream().map(OrderDevice::getDeviceId).toList();
        Map<Long, StandardDevice> deviceMap = loadDevices(ids);

        List<String> reasons = new ArrayList<>();
        LocalDate earliest = null;
        for (OrderDevice link : active) {
            StandardDevice device = deviceMap.get(link.getDeviceId());
            if (device == null) {
                reasons.add("挂接记录异常：标准器记录已不存在（挂接 id=" + link.getId() + "），请解除该挂接后重新选择");
                continue;
            }
            earliest = pickEarlier(earliest, device.getCertValidUntil());
            reasons.addAll(deviceIssues(device, today));
        }
        // 占用被别的委托抢走（正常唯一键下不可能；脏数据/手工改库时仍要拦住）
        if (linkRepository.isDeviceBusyAny(ids, orderId)) {
            reasons.add("存在标准器已被其他未完成委托占用的异常记录，请解除后重新挂接");
        }
        return new GateCheck(reasons.isEmpty(), false, earliest, reasons);
    }

    /** 单件标准器按当前时间的不可放行原因（列表，可能多条） */
    private List<String> deviceIssues(StandardDevice device, LocalDate today) {
        List<String> issues = new ArrayList<>();
        if ("STOPPED".equals(device.getStatus())) {
            issues.add("标准器 " + device.getDeviceNo() + "（" + device.getName() + "）已停用，不能用于本次放行");
        } else if ("TRACING".equals(device.getStatus())) {
            issues.add("标准器 " + device.getDeviceNo() + "（" + device.getName() + "）正在送检溯源，不在在用状态");
        } else if (!"IN_USE".equals(device.getStatus())) {
            issues.add("标准器 " + device.getDeviceNo() + "（" + device.getName() + "）状态异常："
                    + MetroDict.deviceStatusName(device.getStatus()) + "，非在用状态");
        }
        if (device.getCertValidUntil() == null) {
            issues.add("标准器 " + device.getDeviceNo() + "（" + device.getName() + "）未登记溯源证书有效期，记录异常");
        } else if (device.getCertValidUntil().isBefore(today)) {
            issues.add("标准器 " + device.getDeviceNo() + "（" + device.getName() + "）的溯源证书已于 "
                    + device.getCertValidUntil() + " 过期，不能放行");
        }
        return issues;
    }

    // ============================ 挂接 / 替换 / 解除 ============================

    /**
     * 整单替换挂接清单（仅收样态）。服务端对最终清单做判定与 diff：
     * 已挂且仍选中的保留；新增的尝试占用（冲突点名失败，整单不动）；未选中的解除释放。
     */
    @Transactional
    public void replaceLinks(Long orderId, List<Long> requestedIds) {
        var order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + orderId));
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("委托已作废，不能调整标准器挂接");
        }
        if (!MetroDict.canEditDevices(order.getStatus())) {
            throw new BizException("委托已进入检定中，已使用的标准器不允许静默修改；"
                    + "如确需更换，请先回退到收样（占用随之释放）再重新选择");
        }

        List<Long> targetIds = requestedIds == null ? List.of() : requestedIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();

        // 顺序锁定委托行，串行化同一委托的并发提交（双标签页同时保存）
        orderRepository.lockById(orderId);

        List<OrderDevice> current = linkRepository.findByOrderIdAndReleasedAtIsNull(orderId);
        Set<Long> currentIds = current.stream().map(OrderDevice::getDeviceId).collect(Collectors.toSet());
        Set<Long> targetSet = new HashSet<>(targetIds);

        // 候选标准器必须都存在
        Map<Long, StandardDevice> targetDevices = loadDevices(targetIds);
        for (Long id : targetIds) {
            if (!targetDevices.containsKey(id)) {
                throw new BizException("标准器不存在或已删除：id=" + id + "，挂接未保存，请刷新后重选");
            }
        }

        List<Long> toAdd = targetIds.stream().filter(id -> !currentIds.contains(id)).toList();
        List<OrderDevice> toRemove = current.stream().filter(l -> !targetSet.contains(l.getDeviceId())).toList();

        // 先判新增冲突（对占用行加锁预检，给出友好的点名提示）
        List<String> conflicts = new ArrayList<>();
        Map<Long, String> busyOrderNos = new HashMap<>();
        if (!toAdd.isEmpty()) {
            List<OrderDevice> busy = linkRepository.lockActiveByDevices(toAdd);
            Map<Long, Long> busyOrderMap = busy.stream()
                    .collect(Collectors.toMap(OrderDevice::getDeviceId, OrderDevice::getOrderId, (a, b) -> a));
            var orderNos = orderRepository.findAllById(
                    new ArrayList<>(new HashSet<>(busyOrderMap.values()))).stream()
                    .collect(Collectors.toMap(o -> o.getId(), o -> o.getOrderNo()));
            for (Long deviceId : toAdd) {
                Long busyOrderId = busyOrderMap.get(deviceId);
                if (busyOrderId != null && !busyOrderId.equals(orderId)) {
                    StandardDevice d = targetDevices.get(deviceId);
                    String no = d == null ? String.valueOf(deviceId) : d.getDeviceNo();
                    conflicts.add("标准器 " + no + " 已被未完成委托 "
                            + orderNos.getOrDefault(busyOrderId, "#" + busyOrderId) + " 占用，本委托不能抢占");
                    busyOrderNos.put(deviceId, orderNos.getOrDefault(busyOrderId, "#" + busyOrderId));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new DeviceConflictException(conflicts, busyOrderNos);
        }

        LocalDateTime now = LocalDateTime.now();
        // 释放移除项（收样态手动解除）
        for (OrderDevice link : toRemove) {
            linkRepository.releaseOne(orderId, link.getDeviceId(), now, MetroDict.RELEASE_DETACHED);
        }
        // 插入新增占用；唯一键是并发抢占的最终防线
        for (Long deviceId : toAdd) {
            StandardDevice device = targetDevices.get(deviceId);
            OrderDevice link = new OrderDevice();
            link.setOrderId(orderId);
            link.setDeviceId(deviceId);
            link.setLinkedAt(now);
            link.setCertValidSnapshot(device.getCertValidUntil());
            try {
                linkRepository.saveAndFlush(link);
            } catch (RuntimeException e) {
                // 两名检定员并发抢同一标准器：败方整个事务回滚，委托与原清单原样保留。
                // 唯一键冲突可能以 DataIntegrityViolationException 或被 Hibernate 包装的形式抛出。
                Throwable cur = e;
                boolean integrity = false;
                while (cur != null) {
                    if (cur instanceof DataIntegrityViolationException
                            || cur instanceof java.sql.SQLException
                            || (cur.getMessage() != null
                                    && (cur.getMessage().contains("Duplicate entry")
                                        || cur.getMessage().contains("unique")))) {
                        integrity = true;
                        break;
                    }
                    cur = cur.getCause();
                }
                if (integrity) {
                    throw new DeviceConflictException(
                            List.of("标准器 " + device.getDeviceNo() + " 刚被另一笔委托抢先占用，请重新选择可用标准器后再提交"),
                            Map.of());
                }
                throw e;
            }
        }
        order.setUpdatedAt(now);
        orderRepository.save(order);
    }

    /** 退回收样（CALIBRATING → RECEIVED）：标准器尚未真正使用，整体释放，允许重新选择 */
    @Transactional
    public void releaseForRollback(Long orderId) {
        linkRepository.releaseByOrder(orderId, LocalDateTime.now(), MetroDict.RELEASE_ROLLBACK);
    }

    /** 委托作废：释放占用，记录可查 */
    @Transactional
    public void releaseForVoid(Long orderId) {
        linkRepository.releaseByOrder(orderId, LocalDateTime.now(), MetroDict.RELEASE_VOIDED);
    }

    /** 最终发放：占用释放，挂接事实作为历史留痕保留 */
    @Transactional
    public void releaseForIssue(Long orderId) {
        linkRepository.releaseByOrder(orderId, LocalDateTime.now(), MetroDict.RELEASE_ISSUED);
    }

    // ============================ 视图组装 ============================

    /** 委托当前挂接标准器的实时视图（状态/到期日按当下计算） */
    @Transactional(readOnly = true)
    public List<Views.OrderDeviceView> activeDeviceViews(Long orderId) {
        List<OrderDevice> active = linkRepository.findByOrderIdAndReleasedAtIsNull(orderId);
        Map<Long, StandardDevice> map = loadDevices(active.stream().map(OrderDevice::getDeviceId).toList());
        LocalDate today = LocalDate.now();
        List<Views.OrderDeviceView> views = new ArrayList<>();
        for (OrderDevice link : active) {
            StandardDevice d = map.get(link.getDeviceId());
            if (d == null) {
                views.add(new Views.OrderDeviceView(link.getId(), link.getDeviceId(),
                        "已删除的标准器", null, null, null, link.getCertValidSnapshot(),
                        null, "", null, false, false, false,
                        List.of("挂接记录异常：标准器记录已不存在，请解除后重新选择")));
                continue;
            }
            List<String> issues = deviceIssues(d, today);
            Integer remainDays = d.getCertValidUntil() == null ? null
                    : (int) ChronoUnit.DAYS.between(today, d.getCertValidUntil());
            boolean expired = remainDays != null && remainDays < 0;
            boolean expiring = remainDays != null && remainDays >= 0
                    && remainDays <= MetroDict.DEVICE_VALID_WARN_DAYS;
            views.add(new Views.OrderDeviceView(
                    link.getId(), d.getId(), d.getDeviceNo(), d.getName(), d.getModel(),
                    d.getCertValidUntil(), link.getCertValidSnapshot(),
                    d.getStatus(), MetroDict.deviceStatusName(d.getStatus()),
                    remainDays, expired, expiring, issues.isEmpty(), issues));
        }
        return views;
    }

    /** 已释放挂接的历史留痕（最新在前） */
    @Transactional(readOnly = true)
    public List<Views.DeviceLinkHistoryView> historyViews(Long orderId) {
        Map<Long, StandardDevice> map = new HashMap<>();
        List<Views.DeviceLinkHistoryView> views = new ArrayList<>();
        for (OrderDevice link : linkRepository.findByOrderIdOrderByLinkedAtDesc(orderId)) {
            if (link.getReleasedAt() == null) {
                continue;
            }
            StandardDevice d = map.computeIfAbsent(link.getDeviceId(),
                    id -> deviceRepository.findById(id).orElse(null));
            views.add(new Views.DeviceLinkHistoryView(
                    link.getDeviceId(),
                    d == null ? "已删除 #" + link.getDeviceId() : d.getDeviceNo(),
                    d == null ? "" : d.getName(),
                    link.getLinkedAt(), link.getCertValidSnapshot(),
                    link.getReleasedAt(), link.getReleasedReason(),
                    MetroDict.releaseReasonName(link.getReleasedReason())));
        }
        return views;
    }

    /** 挂接选择器候选项（全部标准器 + 实时占用方 + 本笔已选） */
    @Transactional(readOnly = true)
    public List<Views.DeviceOptionView> options(Long orderId) {
        List<StandardDevice> devices = deviceRepository.findAllByOrderByCertValidUntilAsc();
        Set<Long> selectedIds = new HashSet<>(linkRepository
                .findByOrderIdAndReleasedAtIsNull(orderId).stream().map(OrderDevice::getDeviceId).toList());

        List<Long> allIds = devices.stream().map(StandardDevice::getId).toList();
        Map<Long, OrderDevice> busyMap = new HashMap<>();
        if (!allIds.isEmpty()) {
            linkRepository.findActiveByDeviceIds(allIds).forEach(od -> busyMap.put(od.getDeviceId(), od));
        }
        Map<Long, String> orderNoMap = new HashMap<>();
        if (!busyMap.isEmpty()) {
            var ids = busyMap.values().stream().map(OrderDevice::getOrderId).distinct().toList();
            orderRepository.findAllById(ids).forEach(o -> orderNoMap.put(o.getId(), o.getOrderNo()));
        }

        LocalDate today = LocalDate.now();
        List<Views.DeviceOptionView> list = new ArrayList<>();
        for (StandardDevice d : devices) {
            OrderDevice busy = busyMap.get(d.getId());
            boolean busyByOther = busy != null && !busy.getOrderId().equals(orderId);
            Integer remainDays = d.getCertValidUntil() == null ? null
                    : (int) ChronoUnit.DAYS.between(today, d.getCertValidUntil());
            boolean expired = remainDays != null && remainDays < 0;
            boolean expiring = remainDays != null && remainDays >= 0
                    && remainDays <= MetroDict.DEVICE_VALID_WARN_DAYS;
            boolean usable = "IN_USE".equals(d.getStatus()) && !expired && d.getCertValidUntil() != null;
            boolean selected = selectedIds.contains(d.getId());
            String issue = busyByOther
                    ? "被委托 " + orderNoMap.getOrDefault(busy.getOrderId(), "#" + busy.getOrderId()) + " 占用"
                    : String.join("；", deviceIssues(d, today));
            list.add(new Views.DeviceOptionView(
                    d.getId(), d.getDeviceNo(), d.getName(), d.getModel(), d.getMeasureRange(),
                    d.getCertValidUntil(), d.getStatus(), MetroDict.deviceStatusName(d.getStatus()),
                    remainDays, expired, expiring, usable, selected, busyByOther,
                    busyByOther ? busy.getOrderId() : null,
                    busyByOther ? orderNoMap.get(busy.getOrderId()) : null, issue));
        }
        return list;
    }

    /** 列表批量汇总：委托 id → 挂接数量与最早到期日（供队列/列表徽标） */
    @Transactional(readOnly = true)
    public Map<Long, LinkSummary> activeSummary(Set<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, LinkSummary> result = new HashMap<>();
        for (Long id : orderIds) {
            result.put(id, new LinkSummary(0, null));
        }
        List<OrderDevice> all = linkRepository.findAllActive();
        Map<Long, List<OrderDevice>> byOrder = all.stream()
                .collect(Collectors.groupingBy(OrderDevice::getOrderId));
        Set<Long> deviceIds = all.stream().map(OrderDevice::getDeviceId).collect(Collectors.toSet());
        Map<Long, StandardDevice> deviceMap = loadDevices(deviceIds);
        for (Map.Entry<Long, List<OrderDevice>> e : byOrder.entrySet()) {
            if (!result.containsKey(e.getKey())) {
                continue;
            }
            LocalDate earliest = null;
            for (OrderDevice link : e.getValue()) {
                StandardDevice d = deviceMap.get(link.getDeviceId());
                if (d != null) {
                    earliest = pickEarlier(earliest, d.getCertValidUntil());
                }
            }
            result.put(e.getKey(), new LinkSummary(e.getValue().size(), earliest));
        }
        return result;
    }

    /** 批量判定：当前挂接标准器已失效（停用/送检/过期/无证书/记录异常）的委托 id 集合 */
    @Transactional(readOnly = true)
    public Set<Long> blockedOrderIds() {
        LocalDate today = LocalDate.now();
        List<OrderDevice> all = linkRepository.findAllActive();
        Map<Long, StandardDevice> deviceMap = loadDevices(
                all.stream().map(OrderDevice::getDeviceId).collect(Collectors.toSet()));
        Set<Long> blocked = new HashSet<>();
        for (OrderDevice link : all) {
            StandardDevice d = deviceMap.get(link.getDeviceId());
            if (d == null || !deviceIssues(d, today).isEmpty()) {
                blocked.add(link.getOrderId());
            }
        }
        return blocked;
    }

    private Map<Long, StandardDevice> loadDevices(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, StandardDevice> map = new LinkedHashMap<>();
        deviceRepository.findAllById(ids).forEach(d -> map.put(d.getId(), d));
        return map;
    }

    private static LocalDate pickEarlier(LocalDate current, LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    /** 放行判定结果 */
    public record GateCheck(boolean passed, boolean noDevice, LocalDate earliestValidUntil, List<String> reasons) {
        public static GateCheck pass() {
            return new GateCheck(true, false, null, List.of());
        }
    }

    /** 列表徽标汇总 */
    public record LinkSummary(int count, LocalDate earliestValidUntil) {
    }

    /** 占用冲突业务异常：携带逐条冲突，供前端在选择器中原地标红、保留用户已选数据 */
    public static class DeviceConflictException extends BizException {
        private final List<String> conflicts;
        private final Map<Long, String> busyOrderNos;

        public DeviceConflictException(List<String> conflicts, Map<Long, String> busyOrderNos) {
            super(String.join("；", conflicts));
            this.conflicts = conflicts;
            this.busyOrderNos = busyOrderNos;
        }

        public List<String> getConflicts() {
            return conflicts;
        }

        public Map<Long, String> getBusyOrderNos() {
            return busyOrderNos;
        }
    }
}
