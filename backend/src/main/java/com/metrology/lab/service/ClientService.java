package com.metrology.lab.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.dto.ClientDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;

/** 模块一：送检客户。 */
@Service
public class ClientService {

    private final ClientRepository clientRepository;
    private final InstrumentRepository instrumentRepository;
    private final CalibrationOrderRepository orderRepository;
    private final ProcedureRepository procedureRepository;

    public ClientService(ClientRepository clientRepository,
                         InstrumentRepository instrumentRepository,
                         CalibrationOrderRepository orderRepository,
                         ProcedureRepository procedureRepository) {
        this.clientRepository = clientRepository;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
        this.procedureRepository = procedureRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.ClientView> list() {
        // 一次性取全量器具再按客户分组计数，避免逐客户查询
        Map<Long, Integer> instrumentCount = new HashMap<>();
        for (var instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            if (instrument.getClientId() != null) {
                instrumentCount.merge(instrument.getClientId(), 1, Integer::sum);
            }
        }
        List<Views.ClientView> list = new ArrayList<>();
        for (Client client : clientRepository.findAllByOrderByCodeAsc()) {
            list.add(toView(client, instrumentCount.getOrDefault(client.getId(), 0)));
        }
        return list;
    }

    /** 客户抽屉：该客户的全部历史委托单 */
    @Transactional(readOnly = true)
    public List<Views.ClientOrderView> orders(Long clientId) {
        clientRepository.findById(clientId).orElseThrow(() -> new BizException("客户不存在：id=" + clientId));
        Map<Long, String> instrumentName = new HashMap<>();
        for (var instrument : instrumentRepository.findAllByOrderByInstNoAsc()) {
            instrumentName.put(instrument.getId(), instrument.getName());
        }
        Map<Long, String> procMap = new HashMap<>();
        for (var procedure : procedureRepository.findAllByOrderByProcNoAsc()) {
            procMap.put(procedure.getId(), procedure.getProcNo());
        }
        List<Views.ClientOrderView> list = new ArrayList<>();
        for (var order : orderRepository.findAllByClientIdOrderByReceiveDateDesc(clientId)) {
            list.add(new Views.ClientOrderView(
                    order.getId(),
                    order.getOrderNo(),
                    order.getInstNo(),
                    instrumentName.getOrDefault(order.getInstrumentId(), order.getInstNo()),
                    procMap.getOrDefault(order.getProcedureId(), ""),
                    order.getReceiveDate(),
                    order.getStatus(),
                    MetroDict.flowStatusName(order.getStatus()),
                    order.getConclusion(),
                    MetroDict.conclusionName(order.getConclusion()),
                    order.getCertNo(),
                    order.getFee()));
        }
        return list;
    }

    @Transactional
    public Views.ClientView create(ClientDtos.ClientSaveReq req) {
        if (clientRepository.existsByCode(req.getCode())) {
            throw new BizException("客户编号已存在：" + req.getCode());
        }
        if (clientRepository.existsByCreditCode(req.getCreditCode())) {
            throw new BizException("统一社会信用代码重复，请核对客户档案");
        }
        if (!MetroDict.isValidSettleType(req.getSettleType())) {
            throw new BizException("结算方式只能是 CASH（现结）或 MONTHLY（月结）");
        }
        int days = req.getSettleDays() == null ? 0 : req.getSettleDays();
        if (!"CASH".equals(req.getSettleType()) && days <= 0) {
            throw new BizException("月结客户必须填写大于 0 的账期天数");
        }
        Client client = new Client();
        client.setCode(req.getCode());
        client.setName(req.getName());
        client.setCreditCode(req.getCreditCode());
        client.setContactName(req.getContactName());
        client.setPhone(req.getPhone());
        client.setAddress(req.getAddress());
        client.setSettleType(req.getSettleType());
        client.setSettleDays(days);
        client.setRemark(req.getRemark());
        client.setCreatedAt(LocalDate.now());
        clientRepository.save(client);
        return toView(client, 0);
    }

    private Views.ClientView toView(Client client, Integer instrumentCount) {
        return new Views.ClientView(
                client.getId(),
                client.getCode(),
                client.getName(),
                client.getCreditCode(),
                client.getContactName(),
                client.getPhone(),
                client.getAddress(),
                client.getSettleType(),
                MetroDict.settleTypeName(client.getSettleType()),
                client.getSettleDays(),
                client.getRemark(),
                client.getCreatedAt(),
                instrumentCount,
                orderRepository.countByClientId(client.getId()));
    }
}
