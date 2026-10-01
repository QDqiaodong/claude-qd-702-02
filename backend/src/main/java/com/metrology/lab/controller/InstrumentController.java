package com.metrology.lab.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.common.BizException;
import com.metrology.lab.dto.InstrumentDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.InstrumentService;

import jakarta.validation.Valid;

/** 模块二：计量器具台账（器具编号 = 自然主键）。 */
@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping
    public ApiResponse<List<Views.InstrumentView>> list() {
        return ApiResponse.success(instrumentService.list());
    }

    /**
     * 【@NaturalId】按器具编号查器具 —— 走 {@code Session.bySimpleNaturalId(Instrument.class).load()}。
     */
    @GetMapping("/{instNo}")
    public ApiResponse<Views.InstrumentView> findByInstNo(@PathVariable String instNo) {
        return ApiResponse.success(instrumentService.findByInstNo(instNo));
    }

    /**
     * 【兼容旧调用方 · 已废弃】【@NaturalId 批量加载】逗号分隔的器具编号。
     *
     * <p>返回结构保持旧版：裸 {@code List<InstrumentView>}，不会静默换成 v2 的逐行包装；
     * 结果按输入中首次出现的顺序稳定返回（修复了旧版顺序依赖数据库、缺失项导致错位的问题）。
     * 新调用方应使用 POST {@code /api/instruments/batch-lookup}，它逐行返回
     * FOUND / NOT_FOUND / EMPTY / INVALID / DUPLICATE 状态。
     */
    @Deprecated
    @GetMapping("/batch")
    public ResponseEntity<ApiResponse<List<Views.InstrumentView>>> batch(@RequestParam String instNos) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Deprecation", "true");
        headers.add("Sunset", "Sun, 31 Dec 2028 00:00:00 GMT");
        headers.add("Warning", "299 - \"GET /instruments/batch 已废弃，请改用 POST /instruments/batch-lookup\"");
        return ResponseEntity.ok()
                .headers(headers)
                .body(ApiResponse.success(instrumentService.batchByInstNos(instNos)));
    }

    /**
     * 【批量核对 v2】POST body 承载整段粘贴文本，规避长清单撑爆 GET 地址参数。
     *
     * <p>按用户输入原始顺序逐行返回：每行携带原始输入、规范化编号与
     * FOUND / NOT_FOUND / EMPTY / INVALID / DUPLICATE 状态；同一编号同请求内只查库一次，
     * 结果回填到每个位置。任何一行无效都不影响其他行，也不会压缩列表。
     */
    @PostMapping("/batch-lookup")
    public ApiResponse<Views.InstrumentBatchView> batchLookup(
            @RequestBody(required = false) InstrumentDtos.BatchLookupReq req) {
        if (req == null || (req.getText() == null && req.getInstNos() == null)) {
            throw new BizException("请提供要核对的器具编号（text 粘贴文本或 instNos 数组）");
        }
        if (req.getText() != null && req.getText().length() > InstrumentService.BATCH_MAX_TEXT_LENGTH) {
            throw new BizException("批量核对内容不能超过 " + InstrumentService.BATCH_MAX_TEXT_LENGTH
                    + " 个字符，请拆分为多个批次");
        }
        List<String> rawInputs = req.getText() != null
                ? InstrumentService.splitBatchInput(req.getText())
                : req.getInstNos();
        return ApiResponse.success(instrumentService.batchLookup(rawInputs));
    }

    /** 派生查询：N 天内到期（含已超期）清单 */
    @GetMapping("/due")
    public ApiResponse<List<Views.InstrumentView>> due(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(instrumentService.dueSoon(days));
    }

    @PostMapping
    public ApiResponse<Views.InstrumentView> create(@RequestBody @Valid InstrumentDtos.InstrumentSaveReq req) {
        return ApiResponse.success("器具已登记入台账", instrumentService.create(req));
    }

    /** 台账「送检」：按器具编号定位器具并生成收样状态的委托单 */
    @PostMapping("/{instNo}/submit")
    public ApiResponse<Views.InstrumentView> submit(@PathVariable String instNo,
                                                    @RequestBody @Valid InstrumentDtos.SubmitReq req) {
        return ApiResponse.success("已收样，委托单进入流转", instrumentService.submit(instNo, req));
    }
}
