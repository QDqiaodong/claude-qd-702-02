package com.metrology.lab.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：只在 service 里构造、直接序列化给前端，不需要 setter。
 * 派生字段（到期天数、流转序号、证书剩余天数）一律在 service 里算好，前端只负责着色展示。
 */
public final class Views {

    private Views() {
    }

    /** 模块一：送检客户 */
    public record ClientView(
            Long id,
            String code,
            String name,
            String creditCode,
            String contactName,
            String phone,
            String address,
            String settleType,
            String settleTypeName,
            Integer settleDays,
            String remark,
            LocalDate createdAt,
            Integer instrumentCount,
            Long orderCount) {
    }

    /** 客户抽屉里的历史委托单 */
    public record ClientOrderView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String procNo,
            LocalDate receiveDate,
            String status,
            String statusName,
            String conclusion,
            String conclusionName,
            String certNo,
            BigDecimal fee) {
    }

    /** 模块二：计量器具台账 */
    public record InstrumentView(
            Long id,
            String instNo,
            String name,
            String modelSpec,
            String measureRange,
            String accuracyGrade,
            String manufacturer,
            String factoryNo,
            Integer cycleMonths,
            LocalDate lastCalDate,
            LocalDate nextDueDate,
            String status,
            String statusName,
            Long clientId,
            String clientName,
            /** 距下次到期日的天数，null 表示未排期 */
            Integer dueDays,
            /** true = 已超期（dueDays < 0），需立即停用补检 */
            boolean overdue,
            /** true = 30 天内到期，台账表格标红 */
            boolean dueWarn,
            String remark,
            LocalDateTime updatedAt) {
    }

    /**
     * 模块二：批量核对结果。
     *
     * <p>{@code rows} 与用户输入逐行一一对应：不会因为某行无效而缺失，也不会压缩。
     * 重复编号在本次请求里只查库一次，但每个输入位置都拿到完整结果回填。
     */
    public record InstrumentBatchView(
            String apiVersion,
            /** 总行数（含空行），恒等于 rows.size()，方便旧调用方快速核对 */
            int total,
            int foundCount,
            int notFoundCount,
            int emptyCount,
            int invalidCount,
            int duplicateCount,
            List<InstrumentBatchRowView> rows) {
    }

    /**
     * 批量核对中的一行结果。
     *
     * @param index      0 基的原始输入位置，稳定排序键
     * @param rawInput   用户原始输入（未 trim），重复行各自保留
     * @param normalized 规范化后的编号（仅去首尾空白；空行为 null；格式错误保留 trim 后文本）
     * @param status     FOUND 命中 / NOT_FOUND 台账无此编号 / EMPTY 空行 /
     *                   INVALID 格式错误 / DUPLICATE 与本次请求中更早的一行规范化编号相同
     * @param statusName 状态中文名
     * @param message    未命中/空/格式错误/重复时的一句话说明，命中为空串
     * @param duplicateOfIndex 重复行首次出现的位置（index），非重复行为 null
     * @param instrument 命中时的完整台账信息，其余状态为 null
     */
    public record InstrumentBatchRowView(
            int index,
            String rawInput,
            String normalized,
            String status,
            String statusName,
            String message,
            Integer duplicateOfIndex,
            InstrumentView instrument) {
    }

    /** 模块三：检定规程 */
    public record ProcedureView(
            Long id,
            String procNo,
            String name,
            String category,
            String standardNo,
            String deviceReq,
            String envCondition,
            BigDecimal unitFee,
            String remark,
            Long orderCount) {
    }

    /** 模块四：检定委托与流转单 */
    public record OrderView(
            Long id,
            String orderNo,
            Long clientId,
            String clientName,
            Long instrumentId,
            String instNo,
            String instrumentName,
            String modelSpec,
            String measureRange,
            String accuracyGrade,
            String manufacturer,
            String factoryNo,
            Long procedureId,
            String procNo,
            String procedureName,
            BigDecimal unitFee,
            LocalDate receiveDate,
            String verifier,
            String checker,
            String measuredData,
            String conclusion,
            String conclusionName,
            String certNo,
            String status,
            String statusName,
            /** 流转序号：0 收样 … 4 已发放；作废返回 -1 */
            Integer stepIndex,
            LocalDate certValidUntil,
            BigDecimal fee,
            String remark,
            LocalDateTime updatedAt,
            /** 当前挂接（占用中）的标准器数量 */
            Integer deviceCount,
            /** 当前挂接标准器中最早的证书到期日（列表徽标用） */
            LocalDate earliestDeviceValidUntil,
            /** 按当前时间实时判定：存在不可放行的标准器（停用/送检/过期/记录异常） */
            boolean deviceBlocked,
            /** 不可放行的逐条原因（点名到具体标准器） */
            List<String> blockReasons,
            /** 是否允许调整挂接清单：仅收样态可改 */
            boolean devicesEditable,
            /** 当前生效的原始记录版本号（列表徽标用，无确认版本/旧文本为 null） */
            Integer currentRecordVersionNo,
            /** 证书固定引用的版本号（已发放后永不变；未发放为 null） */
            Integer certRecordVersionNo,
            /** 仅有升级前旧文本实测数据（无结构化版本）：列表打「历史记录」标 */
            boolean legacyRecordOnly,
            /** 详情：当前挂接标准器的实时状态（列表接口为空，仅详情返回） */
            List<OrderDeviceView> devices,
            /** 详情：已释放的挂接历史留痕（解除/替换/回退/作废/发放） */
            List<DeviceLinkHistoryView> deviceHistory,
            /** 详情：原始记录与版本留痕面板（列表接口为空） */
            RecordPanelView recordPanel) {
    }

    /** 委托上当前挂接的标准器（状态/有效期按查询当下实时计算） */
    public record OrderDeviceView(
            Long linkId,
            Long deviceId,
            String deviceNo,
            String name,
            String model,
            /** 标准器当前证书有效期至（实时，不是快照） */
            LocalDate certValidUntil,
            /** 挂接当时的证书有效期至快照 */
            LocalDate certValidSnapshot,
            String status,
            String statusName,
            /** 证书剩余天数，负数表示已过期 */
            Integer remainDays,
            boolean expired,
            boolean expiring,
            /** 在用且证书未过期，参与本次检定放行无问题 */
            boolean usable,
            /** 不可放行原因（点名），usable=true 时为空 */
            List<String> issues) {
    }

    /** 已释放的挂接历史 */
    public record DeviceLinkHistoryView(
            Long deviceId,
            String deviceNo,
            String name,
            LocalDateTime linkedAt,
            LocalDate certValidSnapshot,
            LocalDateTime releasedAt,
            String releasedReason,
            String releasedReasonName) {
    }

    /** 挂接选择器中的候选标准器（带实时占用冲突与可用判定） */
    public record DeviceOptionView(
            Long id,
            String deviceNo,
            String name,
            String model,
            String measureRange,
            LocalDate certValidUntil,
            String status,
            String statusName,
            Integer remainDays,
            boolean expired,
            boolean expiring,
            /** 在用且证书有效（仍允许挂接，但挂后会被放行校验拦住的设备不置灰） */
            boolean usable,
            /** 已被本笔委托选中 */
            boolean selected,
            /** 被其他未完成委托占用（不可抢占，选择器置灰） */
            boolean busy,
            Long busyOrderId,
            String busyOrderNo,
            /** 不可用/占用的一句话说明 */
            String issue) {
    }

    /** 左侧待办队列里的单条 */
    public record QueueItemView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String clientName,
            LocalDate receiveDate,
            Integer daysWaiting,
            /** 标准器挂接数量 */
            Integer deviceCount,
            /** 最早证书到期日 */
            LocalDate earliestDeviceValidUntil,
            /** 当前存在不可放行的标准器 */
            boolean deviceBlocked) {
    }

    /** 左侧待办队列：按流转状态分组 */
    public record QueueGroupView(
            String status,
            String statusName,
            Integer count,
            List<QueueItemView> items) {
    }

    /** 顶部概览：全站计数器 */
    public record OverviewView(
            Long instrumentTotal,
            Long instrumentDueSoon,
            Long instrumentOverdue,
            Long orderTotal,
            Long orderOpen,
            Long deviceExpiring,
            BigDecimal monthFee) {
    }

    /** 模块五：标准器 */
    public record DeviceView(
            Long id,
            String deviceNo,
            String name,
            String model,
            String measureRange,
            String traceOrg,
            LocalDate lastTraceDate,
            LocalDate certValidUntil,
            String status,
            String statusName,
            /** 证书剩余天数，负数表示已过期 */
            Integer remainDays,
            boolean expired,
            boolean expiring,
            /** 当前是否被未完成委托占用 */
            boolean busy,
            /** 占用方委托单号（空 = 未占用） */
            String busyOrderNo,
            /** 证书可修订（录入新的有效期），停用/溯源回来后可重新放行 */
            String remark) {
    }

    // ============================ 原始检定记录与版本留痕 ============================

    /** 版本内单条数据项 */
    public record RecordItemView(
            Long id,
            Integer lineNo,
            String calPoint,
            String standardValue,
            String indicatedValue,
            String errorValue,
            String unit,
            String remark) {
    }

    /** 留痕事件（退回意见、提交人、时间、改派、证书固定等） */
    public record RecordEventView(
            Long id,
            Long versionId,
            Integer versionNo,
            String eventType,
            String eventTypeName,
            String actor,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 版本列表中的版本摘要（含退回意见、提交/确认人与时间、是否当前采用/证书引用） */
    public record RecordVersionView(
            Long id,
            Integer versionNo,
            String status,
            String statusName,
            String conclusion,
            String conclusionName,
            String submittedBy,
            LocalDateTime submittedAt,
            String confirmedBy,
            LocalDateTime confirmedAt,
            String rejectedBy,
            LocalDateTime rejectedAt,
            String rejectReason,
            String origin,
            String remark,
            Integer itemCount,
            boolean currentAdopted,
            boolean certPinned,
            LocalDateTime createdAt) {
    }

    /** 版本差异中的一侧数据行 */
    public record DiffRowView(
            Integer lineNo,
            String calPoint,
            String standardValue,
            String indicatedValue,
            String errorValue,
            String unit,
            String remark) {
    }

    /** 行级差异结果：SAME 未变 / MODIFIED 字段变化（changedFields 点名） / ADDED 新增 / REMOVED 删除 */
    public record DiffEntryView(
            String changeType,
            List<String> changedFields,
            DiffRowView base,
            DiffRowView compare) {
    }

    /** 版本对比结果 */
    public record RecordDiffView(
            Integer baseVersionNo,
            Integer compareVersionNo,
            List<DiffEntryView> entries,
            int addedCount,
            int removedCount,
            int modifiedCount,
            int sameCount) {
    }

    /** 证书/预览固定引用的数据视图：结构化版本或旧文本，二者互斥标识来源 */
    public record RecordCertDataView(
            /** STRUCTURED 结构化版本 / LEGACY_TEXT 升级前历史文本 / NONE 尚无数据 */
            String source,
            String sourceName,
            Long versionId,
            Integer versionNo,
            String versionStatusName,
            String submittedBy,
            String confirmedBy,
            LocalDateTime confirmedAt,
            List<RecordItemView> items,
            /** source=LEGACY_TEXT 时的原始文本（前端按旧规则拆行只读展示） */
            String legacyText,
            boolean legacyReadonly) {
    }

    /**
     * 委托详情里的原始记录面板聚合视图：
     * 数据阶段、当前草稿（可编辑时）、版本清单、留痕事件、证书引用数据、服务端算好的操作权限。
     */
    public record RecordPanelView(
            /** NONE 从未建立 / LEGACY 仅有旧文本 / DRAFTING 草稿中 / IN_REVIEW 待核验 / CONFIRMED 已有确认版本 */
            String stage,
            String stageName,
            /** 旧文本（有则展示并标「历史记录」）；转换后仍原样保留 */
            String legacyText,
            boolean legacyConvertible,
            /** 当前可编辑的草稿版本（无草稿为 null，明细也为空） */
            RecordVersionView draft,
            List<RecordItemView> draftItems,
            /** 当前生效（最近确认）版本 */
            RecordVersionView currentVersion,
            List<RecordVersionView> versions,
            List<RecordEventView> events,
            /** 证书预览/发放固定引用的数据（已发放永远不变；未发放取当前确认版本） */
            RecordCertDataView certData,
            Long currentVersionId,
            Long certPinnedVersionId,
            // ---- 以下全部服务端最终判定，前端只按此置灰 ----
            boolean canEditDraft,
            boolean canSubmit,
            boolean canConfirm,
            boolean canReject,
            boolean canWithdraw,
            boolean canConvertLegacy,
            boolean canReassign,
            boolean canEditCert) {
    }
}
