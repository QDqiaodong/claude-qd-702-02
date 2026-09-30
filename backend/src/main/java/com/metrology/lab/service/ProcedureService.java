package com.metrology.lab.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.dto.ProcedureDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ProcedureRepository;

/** 模块三：检定规程。 */
@Service
public class ProcedureService {

    private final ProcedureRepository procedureRepository;
    private final CalibrationOrderRepository orderRepository;

    public ProcedureService(ProcedureRepository procedureRepository,
                            CalibrationOrderRepository orderRepository) {
        this.procedureRepository = procedureRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.ProcedureView> list() {
        List<Views.ProcedureView> list = new ArrayList<>();
        for (Procedure procedure : procedureRepository.findAllByOrderByProcNoAsc()) {
            list.add(toView(procedure));
        }
        return list;
    }

    @Transactional
    public Views.ProcedureView create(ProcedureDtos.ProcedureSaveReq req) {
        if (procedureRepository.existsByProcNo(req.getProcNo())) {
            throw new BizException("规程号已存在：" + req.getProcNo());
        }
        BigDecimal fee = req.getUnitFee() == null ? BigDecimal.ZERO : req.getUnitFee();
        if (fee.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("单项收费必须大于 0");
        }
        Procedure procedure = new Procedure();
        procedure.setProcNo(req.getProcNo());
        procedure.setName(req.getName());
        procedure.setCategory(req.getCategory());
        procedure.setStandardNo(req.getStandardNo());
        procedure.setDeviceReq(req.getDeviceReq());
        procedure.setEnvCondition(req.getEnvCondition());
        procedure.setUnitFee(fee);
        procedure.setRemark(req.getRemark());
        procedureRepository.save(procedure);
        return toView(procedure);
    }

    private Views.ProcedureView toView(Procedure procedure) {
        return new Views.ProcedureView(
                procedure.getId(),
                procedure.getProcNo(),
                procedure.getName(),
                procedure.getCategory(),
                procedure.getStandardNo(),
                procedure.getDeviceReq(),
                procedure.getEnvCondition(),
                procedure.getUnitFee(),
                procedure.getRemark(),
                orderRepository.countByProcedureId(procedure.getId()));
    }
}
