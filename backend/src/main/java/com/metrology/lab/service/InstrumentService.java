package com.metrology.lab.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * 【自然主键批量加载】逗号分隔的多个器具编号一次性加载，编号不存在的位置返回 null 会被剔除。
     */
    @Transactional(readOnly = true)
    public List<Views.InstrumentView> batchByInstNos(String instNos) {
        List<String> nos = new ArrayList<>();
        String raw = instNos == null ? "" : instNos;
        for (String no : raw.split(",")) {
            String trimmed = no.trim();
            if (!trimmed.isEmpty()) {
                nos.add(trimmed);
            }
        }
        // Hibernate 6 里批量自然主键加载走 byMultipleNaturalId：
        // Session.byMultipleIds() 只认 @Id 代理主键，传业务编号会报 Error coercing value
        List<Instrument> loaded = session()
                .byMultipleNaturalId(Instrument.class)
                .enableOrderedReturn(false)
                .multiLoad(nos);
        Map<Long, String> clientName = clientNameMap();
        List<Views.InstrumentView> list = new ArrayList<>();
        for (Instrument instrument : loaded) {
            if (instrument != null) {
                list.add(toView(instrument, clientName));
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
