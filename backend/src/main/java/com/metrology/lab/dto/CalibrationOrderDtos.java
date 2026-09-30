package com.metrology.lab.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块四（检定委托与流转单）入参。 */
public final class CalibrationOrderDtos {

    private CalibrationOrderDtos() {
    }

    @Data
    public static class OrderSaveReq {

        @NotBlank(message = "委托单号不能为空")
        private String orderNo;

        @NotBlank(message = "器具编号不能为空")
        private String instNo;

        /** 规程号 */
        @NotBlank(message = "检定规程不能为空")
        private String procNo;

        /** 收样日期 yyyy-MM-dd，可空默认今天 */
        private String receiveDate;

        /** 检定员 */
        private String verifier;

        /** 核验员 */
        private String checker;

        /** 实测数据项：多条 ; 分隔，单条 校准点|标准值|示值|示值误差 */
        private String measuredData;

        /** PASS / LIMITED / FAIL */
        private String conclusion;

        /** 证书编号 */
        private String certNo;

        private String remark;
    }

    /** 出证时确认证书有效期与收费 */
    @Data
    public static class CertifyReq {

        /** 证书有效期至 yyyy-MM-dd，可空：按器具检定周期自动推算 */
        private String certValidUntil;
    }

    /**
     * 挂接清单调整（整单提交，服务端最终判定）。
     * 收样态允许：服务端对最终清单做 diff —— 新增的抢占占用、移除的释放，
     * 已挂且仍在清单中的保持不变（不会重复占用）。
     */
    @Data
    public static class DeviceLinkReq {

        /** 本次检定使用的标准器 id 全量清单；空列表 = 全部解除 */
        private List<Long> deviceIds;
    }
}
