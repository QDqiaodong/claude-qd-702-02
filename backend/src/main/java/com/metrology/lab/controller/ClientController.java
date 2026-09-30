package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.dto.ClientDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.ClientService;

import jakarta.validation.Valid;

/** 模块一：送检客户。 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @GetMapping
    public ApiResponse<List<Views.ClientView>> list() {
        return ApiResponse.success(clientService.list());
    }

    /** 客户抽屉：该客户的全部历史委托单 */
    @GetMapping("/{id}/orders")
    public ApiResponse<List<Views.ClientOrderView>> orders(@PathVariable Long id) {
        return ApiResponse.success(clientService.orders(id));
    }

    @PostMapping
    public ApiResponse<Views.ClientView> create(@RequestBody @Valid ClientDtos.ClientSaveReq req) {
        return ApiResponse.success("客户档案已建立", clientService.create(req));
    }
}
