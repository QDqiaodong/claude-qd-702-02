-- ============================================================================
-- 增量迁移：原始检定记录与版本留痕
--
-- 适用场景：数据库已由旧版 schema.sql / V2 初始化过（镜像没重建、不想 down -v）。
-- 全新环境无需执行本脚本——最新的 schema.sql 已包含全部建表与种子数据。
--
-- 用法：
--   docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--       metrology_lab < migration/V3__raw_record_versioning.sql
--
-- 幂等：可重复执行（表/列存在则跳过；种子版本按 id 去重）。
-- 说明：旧委托的 measured_data 文本列原样保留（历史记录只读）；已核验/已发放委托不补结构化版本。
-- ============================================================================

SET NAMES utf8mb4;

-- 1. 委托表新增版本指针列
SET @db := DATABASE();
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
       WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'lab_calibration_order'
         AND COLUMN_NAME = 'current_record_version_id') = 0,
    'ALTER TABLE lab_calibration_order ADD COLUMN current_record_version_id BIGINT COMMENT ''当前核验通过的原始记录版本 id'' AFTER measured_data',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
       WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'lab_calibration_order'
         AND COLUMN_NAME = 'cert_record_version_id') = 0,
    'ALTER TABLE lab_calibration_order ADD COLUMN cert_record_version_id BIGINT COMMENT ''证书固定引用的原始记录版本 id（发放快照，不可变）'' AFTER current_record_version_id',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 原始记录版本表
CREATE TABLE IF NOT EXISTS lab_raw_record_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL COMMENT '所属委托 id',
    version_no      INT          NOT NULL COMMENT '版本序号（退回重提 +1；撤回沿用同号）',
    status          VARCHAR(16)  NOT NULL COMMENT 'DRAFT/SUBMITTED/CONFIRMED/REJECTED/WITHDRAWN/SUPERSEDED',
    conclusion      VARCHAR(16)           COMMENT '提交时结论 PASS/LIMITED/FAIL',
    submitted_by    VARCHAR(32)           COMMENT '提交人（检定员）',
    submitted_at    DATETIME              COMMENT '提交时间',
    confirmed_by    VARCHAR(32)           COMMENT '核验通过人（核验员）',
    confirmed_at    DATETIME              COMMENT '核验通过时间',
    rejected_by     VARCHAR(32)           COMMENT '退回/打回操作人',
    rejected_at     DATETIME              COMMENT '退回时间',
    reject_reason   VARCHAR(500)          COMMENT '退回原因',
    origin          VARCHAR(16)           COMMENT 'MANUAL/REJECT/WITHDRAW/LEGACY',
    remark          VARCHAR(500)          COMMENT '整版备注',
    created_at      DATETIME     NOT NULL COMMENT '建立时间',
    updated_at      DATETIME              COMMENT '最后更新时间',
    PRIMARY KEY (id),
    KEY idx_rrv_order (order_id, version_no),
    KEY idx_rrv_status (order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始检定记录版本';

-- 3. 数据项明细表
CREATE TABLE IF NOT EXISTS lab_raw_record_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    version_id       BIGINT       NOT NULL COMMENT '所属版本 id',
    line_no          INT          NOT NULL COMMENT '版内行序',
    cal_point        VARCHAR(80)           COMMENT '校准点',
    standard_value   VARCHAR(60)           COMMENT '标准值',
    indicated_value  VARCHAR(60)           COMMENT '示值',
    error_value      VARCHAR(60)           COMMENT '示值误差',
    unit             VARCHAR(20)           COMMENT '计量单位',
    remark           VARCHAR(200)          COMMENT '本行备注',
    PRIMARY KEY (id),
    KEY idx_rri_version (version_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始记录数据项明细';

-- 4. 事件留痕表（append-only）
CREATE TABLE IF NOT EXISTS lab_record_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL COMMENT '所属委托 id',
    version_id   BIGINT                COMMENT '关联版本 id',
    version_no   INT                   COMMENT '关联版本号',
    event_type   VARCHAR(24)  NOT NULL COMMENT 'DRAFT_CREATE/CONVERTED/SUBMIT/CONFIRM/REJECT/WITHDRAW/SUPERSEDE/CERT_REOPEN/ASSIGN/CERT_FIXED',
    actor        VARCHAR(32)           COMMENT '操作人',
    detail       VARCHAR(500)          COMMENT '事件说明/退回原因/改派前后值',
    created_at   DATETIME     NOT NULL COMMENT '事件时间',
    PRIMARY KEY (id),
    KEY idx_re_order (order_id, id),
    KEY idx_re_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始记录流转事件留痕';

-- 5. 演示种子（逐行幂等）：8 号待核验、5 号已确认、14 号空草稿
INSERT INTO lab_raw_record_version
    (id, order_id, version_no, status, conclusion, submitted_by, submitted_at, confirmed_by, confirmed_at,
     rejected_by, rejected_at, reject_reason, origin, remark, created_at, updated_at)
SELECT * FROM (
              SELECT 1 AS id, 8 AS o, 1 AS v, CAST('SUBMITTED' AS CHAR) AS st, CAST('PASS' AS CHAR) AS cc,
                     CAST('林砚秋' AS CHAR) AS sb,
                     CAST('2026-09-23 10:40:00' AS DATETIME) AS sat,
                     CAST(NULL AS CHAR) AS cb, CAST(NULL AS DATETIME) AS cat,
                     CAST(NULL AS CHAR) AS rb, CAST(NULL AS DATETIME) AS rat, CAST(NULL AS CHAR) AS rr,
                     CAST('LEGACY' AS CHAR) AS og,
                     CAST('由旧文本转第一版后提交核验；300℃ 点偏差偏大待核验判定' AS CHAR) AS rmk,
                     CAST('2026-09-23 10:20:00' AS DATETIME) AS ct,
                     CAST('2026-09-23 10:40:00' AS DATETIME) AS ut
    UNION ALL SELECT 2, 5, 1, 'CONFIRMED', 'LIMITED', '陈听澜',
                     '2026-09-22 16:10:00', '孙敬之', '2026-09-23 09:05:00',
                     NULL, NULL, NULL, 'MANUAL', '限定量程使用见委托备注',
                     '2026-09-22 14:00:00', '2026-09-23 09:05:00'
    UNION ALL SELECT 3, 14, 1, 'DRAFT', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                     'MANUAL', NULL, '2026-09-24 09:00:00', '2026-09-24 09:00:00'
) seed
WHERE NOT EXISTS (SELECT 1 FROM lab_raw_record_version v WHERE v.id = seed.id);

INSERT INTO lab_raw_record_item (version_id, line_no, cal_point, standard_value, indicated_value, error_value, unit, remark)
SELECT * FROM (
              SELECT 1 AS vid, 1 AS ln, '0℃' AS a, '+0.00℃' AS b, '+0.03℃' AS c, '+0.03℃' AS d, '℃' AS u, CAST(NULL AS CHAR) AS r
    UNION ALL SELECT 1, 2, '100.00℃',  '+100.00℃', '+100.06℃', '+0.06℃', '℃', NULL
    UNION ALL SELECT 1, 3, '300.00℃',  '+300.00℃', '+300.21℃', '+0.21℃', '℃', '偏差偏大，核验重点'
    UNION ALL SELECT 2, 1, '10mm',  '+10.0000mm', '+10.0001mm', '+0.10μm', 'mm', NULL
    UNION ALL SELECT 2, 2, '50mm',  '+50.0000mm', '+50.0002mm', '+0.20μm', 'mm', NULL
    UNION ALL SELECT 2, 3, '100mm', '+100.0000mm', '+100.0003mm', '+0.30μm', 'mm', NULL
) seed
WHERE NOT EXISTS (SELECT 1 FROM lab_raw_record_item i WHERE i.version_id = seed.vid AND i.line_no = seed.ln);

INSERT INTO lab_record_event (order_id, version_id, version_no, event_type, actor, detail, created_at)
SELECT * FROM (
              SELECT 8 AS o, 1 AS vid, 1 AS vn, 'DRAFT_CREATE' AS et, '林砚秋' AS ac,
                     '旧文本实测数据转为第 1 版结构化草稿（解析出 3 条；原文本原样保留并标记为历史记录），请核对后再提交复核' AS dt,
                     '2026-09-23 10:20:00' AS ct
    UNION ALL SELECT 8, 1, 1, 'SUBMIT', '林砚秋', '第 1 版提交复核，共 3 条数据项', '2026-09-23 10:40:00'
    UNION ALL SELECT 5, 2, 1, 'DRAFT_CREATE', '陈听澜', '检定员建立第 1 版原始记录草稿', '2026-09-22 14:00:00'
    UNION ALL SELECT 5, 2, 1, 'SUBMIT', '陈听澜', '第 1 版提交复核，共 3 条数据项', '2026-09-22 16:10:00'
    UNION ALL SELECT 5, 2, 1, 'CONFIRM', '孙敬之', '核验通过，第 1 版确认为出证依据', '2026-09-23 09:05:00'
    UNION ALL SELECT 14, 3, 1, 'DRAFT_CREATE', '系统', '进入检定环节，自动建立空白原始记录草稿', '2026-09-24 09:00:00'
) seed
WHERE NOT EXISTS (SELECT 1 FROM lab_record_event e
                  WHERE e.order_id = seed.o AND e.event_type = seed.et AND e.created_at = seed.ct);

-- 5 号委托当前确认版本指针（证书固定引用留待实际发放时写入，保持置空以演示「发放才固定」）
UPDATE lab_calibration_order SET current_record_version_id = 2
WHERE id = 5 AND current_record_version_id IS NULL;
