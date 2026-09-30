package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.RawRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.CalibrationOrderService;

import jakarta.validation.Valid;

/**
 * 模块四：检定委托与流转单 —— 全站主业务接口。
 *
 * <p>{@code POST /api/calibration-orders} 用<b>器具编号</b>入参，服务端通过 {@code @NaturalId}
 * 自然主键加载器具后建单。原始记录的草稿/提交/核验/版本对比在 {@link RawRecordController}；
 * 与流转状态联动的编排（提交即进核验、确认即进出证、出证信息、人员改派、打回重核）在本控制器。
 */
@RestController
@RequestMapping("/api/calibration-orders")
public class CalibrationOrderController {

    private final CalibrationOrderService orderService;

    public CalibrationOrderController(CalibrationOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ApiResponse<List<Views.OrderView>> list(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long clientId) {
        return ApiResponse.success(orderService.list(status, clientId));
    }

    /** 左侧常驻「待办队列」：按流转状态分组 */
    @GetMapping("/queue")
    public ApiResponse<List<Views.QueueGroupView>> queue() {
        return ApiResponse.success(orderService.queue());
    }

    /** 顶部概览计数器 */
    @GetMapping("/overview")
    public ApiResponse<Views.OverviewView> overview() {
        return ApiResponse.success(orderService.overview());
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.OrderView> detail(@PathVariable Long id) {
        return ApiResponse.success(orderService.detail(id));
    }

    /** 【@NaturalId】按器具编号建委托单 */
    @PostMapping
    public ApiResponse<Views.OrderView> create(@RequestBody @Valid CalibrationOrderDtos.OrderSaveReq req) {
        return ApiResponse.success("委托单已受理，流转状态：收样", orderService.create(req));
    }

    /** 提交复核：版本锁定 + 委托进入核验（服务端角色分离与放行校验） */
    @PostMapping("/{id}/records/{versionId}/submit")
    public ApiResponse<Views.OrderView> submitRecord(@PathVariable Long id,
                                                     @PathVariable Long versionId,
                                                     @RequestBody @Valid RawRecordDtos.SubmitReq req) {
        return ApiResponse.success("原始记录已提交复核，本版本已锁定",
                orderService.submitRecord(id, versionId, req));
    }

    /** 核验通过：确认版本成为出证依据 + 委托进入出证 */
    @PostMapping("/{id}/records/{versionId}/confirm")
    public ApiResponse<Views.OrderView> confirmRecord(@PathVariable Long id,
                                                      @PathVariable Long versionId,
                                                      @RequestBody @Valid RawRecordDtos.ConfirmReq req) {
        return ApiResponse.success("核验通过，该版本确认为出证依据，委托进入出证环节",
                orderService.confirmRecord(id, versionId, req));
    }

    /** 核验退回：必须写原因；旧版留痕、自动派生下一版草稿、委托回到检定中 */
    @PostMapping("/{id}/records/{versionId}/reject")
    public ApiResponse<Views.OrderView> rejectRecord(@PathVariable Long id,
                                                     @PathVariable Long versionId,
                                                     @RequestBody @Valid RawRecordDtos.RejectReq req) {
        return ApiResponse.success("已退回并生成下一版草稿，退回意见已留痕",
                orderService.rejectRecord(id, versionId, req));
    }

    /** 出证环节打回重核（同样生成新版本，原确认版本只读保留） */
    @PostMapping("/{id}/records/{versionId}/reopen")
    public ApiResponse<Views.OrderView> reopen(@PathVariable Long id,
                                               @PathVariable Long versionId,
                                               @RequestBody @Valid RawRecordDtos.RejectReq req) {
        return ApiResponse.success("已打回重核，原确认版本保留，系统生成下一版草稿",
                orderService.reopenFromCertifying(id, versionId, req));
    }

    /** 出证信息：证书编号与有效期（数据来自已确认版本，此处不能改数据） */
    @PutMapping("/{id}/cert-info")
    public ApiResponse<Views.OrderView> certInfo(@PathVariable Long id,
                                                 @RequestBody @Valid RawRecordDtos.CertInfoReq req) {
        return ApiResponse.success("证书信息已保存（引用版本不变）", orderService.saveCertInfo(id, req));
    }

    /** 人员改派（误指派/人员调整的可恢复路径，全程留痕） */
    @PutMapping("/{id}/assignees")
    public ApiResponse<Views.OrderView> reassign(@PathVariable Long id,
                                                 @RequestBody @Valid RawRecordDtos.ReassignReq req) {
        return ApiResponse.success("人员改派已完成并留痕", orderService.reassign(id, req));
    }

    @PostMapping("/{id}/advance")
    public ApiResponse<Views.OrderView> advance(@PathVariable Long id) {
        return ApiResponse.success("流转已推进一格", orderService.advance(id));
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<Views.OrderView> rollback(@PathVariable Long id) {
        return ApiResponse.success("流转已回退一格", orderService.rollback(id));
    }

    /** 作废委托（未发放均可），已挂标准器随之释放 */
    @PostMapping("/{id}/void")
    public ApiResponse<Views.OrderView> voidOrder(@PathVariable Long id) {
        return ApiResponse.success("委托已作废，标准器占用已释放", orderService.voidOrder(id));
    }

    /** 挂接选择器：全部标准器 + 实时占用冲突 + 本笔已选 */
    @GetMapping("/{id}/device-options")
    public ApiResponse<List<Views.DeviceOptionView>> deviceOptions(@PathVariable Long id) {
        return ApiResponse.success(orderService.deviceOptions(id));
    }

    /**
     * 保存挂接清单（挂接 / 替换 / 解除同一入口，收样态可用）。
     * 最终是否允许由服务端判定：占用冲突、标准器失效都在这一关拦住。
     */
    @PutMapping("/{id}/devices")
    public ApiResponse<Views.OrderView> saveDevices(@PathVariable Long id,
                                                    @RequestBody CalibrationOrderDtos.DeviceLinkReq req) {
        return ApiResponse.success("标准器挂接清单已保存", orderService.saveDevices(id, req));
    }
}
