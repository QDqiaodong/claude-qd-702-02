package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.dto.ProcedureDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.ProcedureService;

import jakarta.validation.Valid;

/** 模块三：检定规程。 */
@RestController
@RequestMapping("/api/procedures")
public class ProcedureController {

    private final ProcedureService procedureService;

    public ProcedureController(ProcedureService procedureService) {
        this.procedureService = procedureService;
    }

    @GetMapping
    public ApiResponse<List<Views.ProcedureView>> list() {
        return ApiResponse.success(procedureService.list());
    }

    @PostMapping
    public ApiResponse<Views.ProcedureView> create(@RequestBody @Valid ProcedureDtos.ProcedureSaveReq req) {
        return ApiResponse.success("规程已入库", procedureService.create(req));
    }
}
