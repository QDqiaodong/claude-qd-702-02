package com.metrology.lab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 模块一（送检客户）入参。 */
public final class ClientDtos {

    private ClientDtos() {
    }

    @Data
    public static class ClientSaveReq {

        @NotBlank(message = "客户编号不能为空")
        private String code;

        @NotBlank(message = "单位名称不能为空")
        private String name;

        @NotBlank(message = "统一社会信用代码不能为空")
        @Size(max = 32, message = "统一社会信用代码不得超过 32 位")
        private String creditCode;

        private String contactName;

        private String phone;

        private String address;

        /** CASH 现结 / MONTHLY 月结 */
        @NotBlank(message = "结算方式不能为空")
        private String settleType;

        /** 账期天数，可空：现结默认 0 */
        private Integer settleDays;

        private String remark;

        /** 建档日期，可空：默认今天 */
        private String createdAt;
    }
}
