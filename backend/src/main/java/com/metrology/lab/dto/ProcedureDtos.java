package com.metrology.lab.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块三（检定规程）入参。 */
public final class ProcedureDtos {

    private ProcedureDtos() {
    }

    @Data
    public static class ProcedureSaveReq {

        @NotBlank(message = "规程号不能为空")
        private String procNo;

        @NotBlank(message = "规程名称不能为空")
        private String name;

        @NotBlank(message = "适用器具类别不能为空")
        private String category;

        private String standardNo;

        private String deviceReq;

        private String envCondition;

        @NotNull(message = "单项收费不能为空")
        private BigDecimal unitFee;

        private String remark;
    }
}
