package com.metrology.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块五（标准器）入参。 */
public final class StandardDeviceDtos {

    private StandardDeviceDtos() {
    }

    @Data
    public static class DeviceSaveReq {

        @NotBlank(message = "标准器编号不能为空")
        private String deviceNo;

        @NotBlank(message = "标准器名称不能为空")
        private String name;

        private String model;

        private String measureRange;

        private String traceOrg;

        /** 上次溯源日期 yyyy-MM-dd，可空 */
        private String lastTraceDate;

        /** 证书有效期至 yyyy-MM-dd */
        private String certValidUntil;

        /** IN_USE / STOPPED / TRACING，可空默认 IN_USE */
        private String status;

        private String remark;
    }

    @Data
    public static class StatusReq {

        @NotBlank(message = "状态不能为空")
        private String status;
    }

    /** 修订溯源信息（证书到期日可被改成更早 —— 放行端按最新值实时校验） */
    @Data
    public static class TraceReviseReq {

        /** 上次溯源日期 yyyy-MM-dd，可空 */
        private String lastTraceDate;

        /** 证书有效期至 yyyy-MM-dd，可空（视为未登记证书，放行时按记录异常拦截） */
        private String certValidUntil;

        /** 溯源机构，可空：不传不改 */
        private String traceOrg;

        private String remark;
    }
}
