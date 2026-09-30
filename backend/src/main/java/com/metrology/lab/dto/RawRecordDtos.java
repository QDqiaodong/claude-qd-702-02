package com.metrology.lab.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 原始检定记录与版本留痕模块入参。
 *
 * <p>所有写操作都带操作人姓名 —— 本系统没有登录态，服务端以委托上「检定员 / 核验员」
 * 指派字段为最终判定依据，不信任前端传入的角色。
 */
public final class RawRecordDtos {

    private RawRecordDtos() {
    }

    /** 单条数据项（批量粘贴、复制行都在前端拼成列表，服务端全量收单） */
    @Data
    public static class ItemReq {
        private String calPoint;
        private String standardValue;
        private String indicatedValue;
        private String errorValue;
        private String unit;
        private String remark;
    }

    /** 整版保存草稿（检定员专属；空列表会被提交复核拦下，草稿期允许空以便先建单） */
    @Data
    public static class DraftSaveReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        private String conclusion;

        private String remark;

        /** 全量明细：本次保存即最终清单（顺序即版内行序） */
        private List<ItemReq> items;
    }

    /** 提交复核（冻结版本） */
    @Data
    public static class SubmitReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    /** 核验通过 */
    @Data
    public static class ConfirmReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    /** 核验退回（必须写明原因） */
    @Data
    public static class RejectReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        @NotBlank(message = "退回原因必须填写，检定员需要据此修订")
        private String reason;
    }

    /** 误提交撤回 */
    @Data
    public static class WithdrawReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    /** 旧文本实测数据转第一版结构化记录 */
    @Data
    public static class ConvertLegacyReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    /** 人员改派（误指派 / 人员调整的可恢复路径，留痕；不能靠删历史修问题） */
    @Data
    public static class ReassignReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        private String verifier;

        private String checker;
    }

    /** 出证信息（证书编号、有效期），出证阶段保存 */
    @Data
    public static class CertInfoReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        private String certNo;

        /** yyyy-MM-dd，空 = 按器具周期自动推算 */
        private String certValidUntil;
    }
}
