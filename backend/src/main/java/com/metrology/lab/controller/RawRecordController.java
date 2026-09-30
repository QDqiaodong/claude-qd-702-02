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
import com.metrology.lab.dto.RawRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.RawRecordService;

import jakarta.validation.Valid;

/**
 * 原始检定记录与版本留痕接口（不直接切委托状态的记录侧操作）。
 *
 * <p>草稿批量增删/复制/粘贴：{@code PUT drafts/{versionId}} 全量收单，服务端只认最终清单，
 * 便利操作可以放前端，但版本生成、角色分离、状态校验全部在服务端。
 */
@RestController
@RequestMapping("/api/calibration-orders/{orderId}/records")
public class RawRecordController {

    private final RawRecordService recordService;

    public RawRecordController(RawRecordService recordService) {
        this.recordService = recordService;
    }

    /** 任意版本只读明细（旧版本/已退回/证书固定版本的数据查看） */
    @GetMapping("/{versionId}/items")
    public ApiResponse<List<Views.RecordItemView>> versionItems(@PathVariable Long orderId,
                                                                @PathVariable Long versionId) {
        return ApiResponse.success(recordService.versionItems(orderId, versionId));
    }

    /** 面板聚合：草稿/版本清单/留痕事件/证书引用数据/操作权限，一次取齐 */
    @GetMapping
    public ApiResponse<Views.RecordPanelView> panel(@PathVariable Long orderId) {
        return ApiResponse.success(recordService.panel(orderId));
    }

    /** 检定员新建草稿（收样/检定中、尚无草稿时）；请求体只承载操作人，服务端按委托指派核验身份 */
    @PostMapping("/drafts")
    public ApiResponse<Views.RecordPanelView> createDraft(@PathVariable Long orderId,
                                                          @RequestBody @Valid RawRecordDtos.SubmitReq req) {
        return ApiResponse.success("已建立空白原始记录草稿", recordService.createDraft(orderId, req.getOperator()));
    }

    /** 草稿整版保存（批量增删/复制行/粘贴均在前端拼成全量清单后调本接口） */
    @PutMapping("/drafts/{versionId}")
    public ApiResponse<Views.RecordPanelView> saveDraft(@PathVariable Long orderId,
                                                        @PathVariable Long versionId,
                                                        @RequestBody @Valid RawRecordDtos.DraftSaveReq req) {
        return ApiResponse.success("草稿已保存（提交复核前可继续修改）",
                recordService.saveDraft(orderId, versionId, req));
    }

    /** 误提交撤回（核验员尚未处理，检定员本人，原提交留痕、同号草稿承接） */
    @PostMapping("/{versionId}/withdraw")
    public ApiResponse<Views.RecordPanelView> withdraw(@PathVariable Long orderId,
                                                       @PathVariable Long versionId,
                                                       @RequestBody @Valid RawRecordDtos.WithdrawReq req) {
        return ApiResponse.success("已撤回提交，原记录作为草稿打开（提交痕迹保留可查）",
                recordService.withdraw(orderId, versionId, req.getOperator()));
    }

    /** 升级前旧文本实测数据 → 第一版结构化草稿（仅未进入核验的委托，原文本继续只读保留） */
    @PostMapping("/convert-legacy")
    public ApiResponse<Views.RecordPanelView> convertLegacy(@PathVariable Long orderId,
                                                            @RequestBody @Valid RawRecordDtos.ConvertLegacyReq req) {
        return ApiResponse.success("旧文本已转成第 1 版结构化草稿，请核对补充单位后再提交复核",
                recordService.convertLegacy(orderId, req));
    }

    /**
     * 版本差异对比：行级 新增/删除/修改/未变 + 修改字段点名。
     * 详情页据此展示「各版差异」，基准与对比版本都必须属于本委托。
     */
    @GetMapping("/diff")
    public ApiResponse<Views.RecordDiffView> diff(@PathVariable Long orderId,
                                                  @RequestParam Long baseVersionId,
                                                  @RequestParam Long compareVersionId) {
        return ApiResponse.success(recordService.diff(orderId, baseVersionId, compareVersionId));
    }
}
