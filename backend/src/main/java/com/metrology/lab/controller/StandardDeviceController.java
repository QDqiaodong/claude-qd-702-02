package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.dto.StandardDeviceDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.StandardDeviceService;

import jakarta.validation.Valid;

/** 模块五：标准器。 */
@RestController
@RequestMapping("/api/standard-devices")
public class StandardDeviceController {

    private final StandardDeviceService deviceService;

    public StandardDeviceController(StandardDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public ApiResponse<List<Views.DeviceView>> list() {
        return ApiResponse.success(deviceService.list());
    }

    @PostMapping
    public ApiResponse<Views.DeviceView> create(@RequestBody @Valid StandardDeviceDtos.DeviceSaveReq req) {
        return ApiResponse.success("标准器已登记", deviceService.create(req));
    }

    /** 送检（TRACING）/ 停用（STOPPED）/ 回用在役（IN_USE） */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.DeviceView> setStatus(@PathVariable Long id,
                                                   @RequestBody @Valid StandardDeviceDtos.StatusReq req) {
        return ApiResponse.success("标准器状态已更新", deviceService.setStatus(id, req.getStatus()));
    }

    /** 修订溯源证书（有效期可改早；进行中委托的下次放行按新日期实时校验） */
    @PostMapping("/{id}/trace")
    public ApiResponse<Views.DeviceView> reviseTrace(@PathVariable Long id,
                                                     @RequestBody StandardDeviceDtos.TraceReviseReq req) {
        return ApiResponse.success("溯源证书信息已修订", deviceService.reviseTrace(id, req));
    }
}
