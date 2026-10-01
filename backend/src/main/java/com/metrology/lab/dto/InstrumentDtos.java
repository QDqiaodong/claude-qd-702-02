package com.metrology.lab.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块二（计量器具台账）入参。 */
public final class InstrumentDtos {

    private InstrumentDtos() {
    }

    /** 批量核对请求：POST 正文携带原始编号清单（支持多行 / 中英文逗号混用），避免超长 GET 参数。 */
    @Data
    public static class BatchQueryReq {

        /** 用户逐行粘贴的原始编号，可为 null（按空清单处理） */
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
