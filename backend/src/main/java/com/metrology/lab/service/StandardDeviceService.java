package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.OrderDevice;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.StandardDeviceDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.OrderDeviceRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

/** 模块五：标准器（溯源与证书有效期倒计时、占用状态）。 */
@Service
public class StandardDeviceService {

    private final StandardDeviceRepository deviceRepository;
    private final OrderDeviceRepository linkRepository;
    private final CalibrationOrderRepository orderRepository;

    public StandardDeviceService(StandardDeviceRepository deviceRepository,
                                 OrderDeviceRepository linkRepository,
                                 CalibrationOrderRepository orderRepository) {
        this.deviceRepository = deviceRepository;
        this.linkRepository = linkRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.DeviceView> list() {
        List<StandardDevice> devices = deviceRepository.findAllByOrderByCertValidUntilAsc();
        Map<Long, String> busyOrderNoMap = busyOrderNoMap(
                devices.stream().map(StandardDevice::getId).toList());
        List<Views.DeviceView> list = new ArrayList<>();
        for (StandardDevice device : devices) {
            list.add(toView(device, busyOrderNoMap.get(device.getId())));
        }
        return list;
    }

    /** deviceId → 占用方委托单号（仅未完成委托的占用） */
    private Map<Long, String> busyOrderNoMap(List<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return new HashMap<>();
        }
        List<OrderDevice> active = linkRepository.findActiveByDeviceIds(deviceIds);
        Map<Long, Long> deviceToOrder = new HashMap<>();
        active.forEach(od -> deviceToOrder.put(od.getDeviceId(), od.getOrderId()));
        Map<Long, String> orderNoById = new HashMap<>();
        orderRepository.findAllById(deviceToOrder.values())
                .forEach(o -> orderNoById.put(o.getId(), o.getOrderNo()));
        Map<Long, String> byDevice = new HashMap<>();
        deviceToOrder.forEach((deviceId, orderId) ->
                byDevice.put(deviceId, orderNoById.getOrDefault(orderId, "#" + orderId)));
        return byDevice;
    }

    @Transactional
    public Views.DeviceView create(StandardDeviceDtos.DeviceSaveReq req) {
        if (deviceRepository.existsByDeviceNo(req.getDeviceNo())) {
            throw new BizException("标准器编号已存在：" + req.getDeviceNo());
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "IN_USE" : req.getStatus().trim();
        if (!MetroDict.isValidDeviceStatus(status)) {
            throw new BizException("标准器状态不合法，应为 在用 / 停用 / 送检");
        }
        StandardDevice device = new StandardDevice();
        device.setDeviceNo(req.getDeviceNo());
        device.setName(req.getName());
        device.setModel(req.getModel());
        device.setMeasureRange(req.getMeasureRange());
        device.setTraceOrg(req.getTraceOrg());
        device.setLastTraceDate(parseDate(req.getLastTraceDate()));
        device.setCertValidUntil(parseDate(req.getCertValidUntil()));
        device.setStatus(status);
        device.setRemark(req.getRemark());
        deviceRepository.save(device);
        return toView(device, null);
    }

    /** 送上级机构溯源 / 停用 / 回用在役（状态即影响进行中委托的放行） */
    @Transactional
    public Views.DeviceView setStatus(Long id, String status) {
        StandardDevice device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("标准器不存在：id=" + id));
        if (!MetroDict.isValidDeviceStatus(status)) {
            throw new BizException("标准器状态不合法，应为 在用 / 停用 / 送检");
        }
        device.setStatus(status);
        deviceRepository.save(device);
        // 不释放已有占用：进行中委托下次推进时由放行校验拦截并点名本标准器
        return toView(device, busyOrderNoMap(List.of(id)).get(id));
    }

    /**
     * 修订溯源证书（有效期可改成更早）。
     * 已挂接到进行中委托的标准器被改期后，委托详情立即显示新到期日与不可放行原因，
     * 占用关系不动，必须由检定员解除/回退后重选合格标准器才能继续流转。
     */
    @Transactional
    public Views.DeviceView reviseTrace(Long id, StandardDeviceDtos.TraceReviseReq req) {
        StandardDevice device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("标准器不存在：id=" + id));
        if (req.getLastTraceDate() != null) {
            device.setLastTraceDate(parseDate(req.getLastTraceDate()));
        }
        if (req.getCertValidUntil() != null) {
            // 空串视为未登记证书（放行时按记录异常拦截）
            device.setCertValidUntil(req.getCertValidUntil().isBlank() ? null : parseDate(req.getCertValidUntil()));
        }
        if (req.getTraceOrg() != null) {
            device.setTraceOrg(req.getTraceOrg().isBlank() ? null : req.getTraceOrg().trim());
        }
        if (req.getRemark() != null) {
            device.setRemark(req.getRemark().isBlank() ? null : req.getRemark().trim());
        }
        deviceRepository.save(device);
        return toView(device, busyOrderNoMap(List.of(id)).get(id));
    }

    private Views.DeviceView toView(StandardDevice device, String busyOrderNo) {
        LocalDate validUntil = device.getCertValidUntil();
        Integer remainDays = validUntil == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), validUntil);
        boolean expired = remainDays != null && remainDays < 0;
        boolean expiring = remainDays != null && remainDays >= 0
                && remainDays <= MetroDict.DEVICE_VALID_WARN_DAYS;
        return new Views.DeviceView(
                device.getId(),
                device.getDeviceNo(),
                device.getName(),
                device.getModel(),
                device.getMeasureRange(),
                device.getTraceOrg(),
                device.getLastTraceDate(),
                validUntil,
                device.getStatus(),
                MetroDict.deviceStatusName(device.getStatus()),
                remainDays,
                expired,
                expiring,
                busyOrderNo != null,
                busyOrderNo,
                device.getRemark());
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDate.parse(text.trim());
    }
}
