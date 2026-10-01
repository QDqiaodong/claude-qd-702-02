package com.metrology.lab.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块二（计量器具台账）入参。 */
public final class InstrumentDtos {

    private InstrumentDtos() {
    }

    /**
     * 批量核对请求：整段文本（多行 / 逗号分隔）走 POST body，避免长清单撑爆 GET 地址参数。
     *
     * <p>服务端统一负责拆分与规范化，调用方只把用户原始粘贴内容原样传上来。
     */
    @Data
    public static class BatchLookupReq {

        /** 用户原始输入：允许换行、半角逗号、全角逗号、中文顿号混合分隔。 */
        private String text;

        /** 已拆分好的逐行输入：text 与 instNos 二选一，同时给以 text 为准。 */
        private List<String> instNos;
    }

    @Data
    public static class InstrumentSaveReq {

        /** 器具编号：业务自然主键 */
        @NotBlank(message = "器具编号不能为空")
        private String instNo;

        @NotBlank(message = "器具名称不能为空")
        private String name;

        private String modelSpec;

        private String measureRange;

        private String accuracyGrade;

        private String manufacturer;

        private String factoryNo;

        /** 检定周期（月），可空：默认 12 */
        private Integer cycleMonths;

        /** 上次检定日期 yyyy-MM-dd，可空 */
        private String lastCalDate;

        /** 下次到期日 yyyy-MM-dd，可空：不给则按周期推算 */
        private String nextDueDate;

        /** IN_USE / STOPPED / IN_CALIBRATION / SCRAPPED，可空默认 IN_USE */
        private String status;

        private Long clientId;

        private String remark;
    }

    /** 台账「送检」：按规程号就地生成一张收样状态的委托单 */
    @Data
    public static class SubmitReq {

        @NotBlank(message = "必须选择检定规程")
        private String procNo;

        /** 检定员，可空 */
        private String verifier;
    }
}
