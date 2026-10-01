package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
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
     * 【@NaturalId 批量加载 · 旧版兼容】逗号分隔的器具编号一次性加载。
     *
     * @deprecated 请改用 {@code POST /api/instruments/batch-query}：旧接口会丢弃未命中行、
     * 不保证顺序；为不静默改变旧调用方契约，这里仍返回命中器具的纯数组。
     */
    @Deprecated
    @GetMapping("/batch")
    public ApiResponse<List<Views.InstrumentView>> batch(@RequestParam String instNos) {
        return ApiResponse.success(instrumentService.batchByInstNos(instNos));
    }

    /**
     * 批量核对：POST 正文携带原始编号清单（可多行、中英文逗号混排），
     * 按用户输入的原始顺序逐行返回 FOUND / NOT_FOUND / BLANK / INVALID / DUPLICATE，
     * 重复编号只查库一次但每个输入位置都回填；任何一行无效都不影响其他行。
     * 用 POST + 请求体是为了避免长清单拼成一条无限长的 GET 地址参数。
     */
    @PostMapping("/batch-query")
    public ApiResponse<Views.InstrumentBatchResultView> batchQuery(
            @RequestBody(required = false) InstrumentDtos.BatchQueryReq req) {
        List<String> rawLines = req == null ? null : req.getInstNos();
        return ApiResponse.success(instrumentService.batchQuery(rawLines));
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
