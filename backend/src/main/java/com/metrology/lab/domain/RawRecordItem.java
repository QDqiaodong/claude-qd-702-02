package com.metrology.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 原始记录版本内的单条检定数据项（校准点 / 标准值 / 示值 / 误差 / 单位 / 备注）。
 *
 * <p>每个版本各自持有一份完整明细 —— 这是版本间可逐项比对、且旧版本永不被后续修订污染的前提。
 * 行只随版本创建时整版写入；版本一旦离开 DRAFT，本表明细即只读（服务端不再提供 UPDATE/DELETE
 * 到已冻结版本明细的路径，批量增删复制只作用于草稿版本）。
 */
@Data
@Entity
@Table(name = "lab_raw_record_item")
public class RawRecordItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属版本 */
    @Column(name = "version_id", nullable = false)
    private Long versionId;

    /** 版内序号，小在前（复制/批量粘贴都按提交顺序落库） */
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    /** 校准点（检定点位描述，如 10mm / DC 100V） */
    @Column(name = "cal_point", length = 80)
    private String calPoint;

    /** 标准值（文本保留原始书写，含正负号/量纲前缀） */
    @Column(name = "standard_value", length = 60)
    private String standardValue;

    /** 被检器具示值 */
    @Column(name = "indicated_value", length = 60)
    private String indicatedValue;

    /** 示值误差 */
    @Column(name = "error_value", length = 60)
    private String errorValue;

    /** 计量单位（mm / MPa / V …） */
    @Column(name = "unit", length = 20)
    private String unit;

    /** 本行必要备注（异常、复测、修正方式等） */
    @Column(name = "remark", length = 200)
    private String remark;
}
