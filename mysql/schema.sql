-- ============================================================================
-- 计量检定所业务系统 · 建表 + 种子数据
-- 库名 metrology_lab，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 六个业务模块：
--   1. lab_client             送检客户（结算方式、账期天数）
--   2. lab_instrument         计量器具台账（器具编号 = 业务自然主键）
--   3. lab_procedure          检定规程（单项收费）
--   4. lab_calibration_order  检定委托与流转单（收样→检定中→核验→出证→已发放，另可作废 VOIDED）
--   5. lab_standard_device    标准器（溯源单位、证书有效期倒计时）
--   6. lab_order_device       委托-标准器挂接与占用关系（released_at 为空 = 占用中，生成列唯一键防并发抢占）
--
-- 演示数据对照：
--   [器具] 到期预警    → 下次到期日距今天不足 30 天：JLB-0004/0009/0015/0021
--   [器具] 已超期      → JLB-0007（2026-09-12）、JLB-0018（2026-09-20）
--   [器具] 状态齐活    → IN_USE / STOPPED / IN_CALIBRATION / SCRAPPED 各若干
--   [委托] 五态齐活    → RECEIVED 3 / CALIBRATING 4 / VERIFYING 3 / CERTIFYING 3 / ISSUED 4，另含 1 笔 VOIDED
--   [结论] 三种        → PASS 合格 / LIMITED 限用 / FAIL 不合格
--   [实测] 数据项      → measured_data 用 ";" 分隔多条，每条 "|" 分隔：校准点|标准值|示值|示值误差
--   [标准器] 倒计时    → STD-0005 已过期、STD-0002/0009 不足 60 天
--   [挂接] 占用/异常   → 11 号委托挂 STD-0005（过期）推进必拦；12 号挂 STD-0013（送检中）；
--                        8 号占用 STD-0002；16 号收样想挂 STD-0002 会冲突；19 号作废已释放；
--                        14 号无挂接（历史兼容，可正常推进）；13 号带一条「已替换」留痕行。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS lab_record_event;
DROP TABLE IF EXISTS lab_raw_record_item;
DROP TABLE IF EXISTS lab_raw_record_version;
DROP TABLE IF EXISTS lab_order_device;
DROP TABLE IF EXISTS lab_calibration_order;
DROP TABLE IF EXISTS lab_instrument;
DROP TABLE IF EXISTS lab_procedure;
DROP TABLE IF EXISTS lab_standard_device;
DROP TABLE IF EXISTS lab_client;

-- ---------------------------- 1. 送检客户 ----------------------------
CREATE TABLE lab_client (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code          VARCHAR(32)  NOT NULL COMMENT '客户编号（台账用）',
    name          VARCHAR(80)  NOT NULL COMMENT '单位名称',
    credit_code   VARCHAR(32)  NOT NULL COMMENT '统一社会信用代码',
    contact_name  VARCHAR(32)           COMMENT '联系人',
    phone         VARCHAR(24)           COMMENT '联系电话',
    address       VARCHAR(120)          COMMENT '单位地址',
    settle_type   VARCHAR(16)  NOT NULL COMMENT 'CASH 现结 / MONTHLY 月结',
    settle_days   INT          NOT NULL DEFAULT 0 COMMENT '账期天数（现结为 0）',
    remark        VARCHAR(200)          COMMENT '备注',
    created_at    DATE                  COMMENT '建档日期',
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_code (code),
    UNIQUE KEY uk_client_credit (credit_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='送检客户';

-- ---------------------------- 2. 计量器具台账 ----------------------------
CREATE TABLE lab_instrument (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    inst_no         VARCHAR(32) NOT NULL COMMENT '器具编号（自然主键）',
    name            VARCHAR(80) NOT NULL COMMENT '器具名称',
    model_spec      VARCHAR(80)          COMMENT '型号规格',
    measure_range   VARCHAR(80)          COMMENT '测量范围',
    accuracy_grade  VARCHAR(32)          COMMENT '准确度等级',
    manufacturer    VARCHAR(80)          COMMENT '制造厂',
    factory_no      VARCHAR(48)          COMMENT '出厂编号',
    cycle_months    INT                  COMMENT '检定周期（月）',
    last_cal_date   DATE                 COMMENT '上次检定日期',
    next_due_date   DATE                 COMMENT '下次到期日',
    status          VARCHAR(20) NOT NULL COMMENT 'IN_USE 在用 / STOPPED 停用 / IN_CALIBRATION 送检中 / SCRAPPED 报废',
    client_id       BIGINT               COMMENT '所属送检客户',
    remark          VARCHAR(200)         COMMENT '备注',
    updated_at      DATETIME             COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_instrument_no (inst_no),
    KEY idx_instrument_status (status),
    KEY idx_instrument_due (next_due_date),
    KEY idx_instrument_client (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计量器具台账';

-- ---------------------------- 3. 检定规程 ----------------------------
CREATE TABLE lab_procedure (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    proc_no       VARCHAR(32)   NOT NULL COMMENT '规程号',
    name          VARCHAR(120)  NOT NULL COMMENT '规程名称',
    category      VARCHAR(32)   NOT NULL COMMENT '适用器具类别',
    standard_no   VARCHAR(64)            COMMENT '依据标准号',
    device_req    VARCHAR(200)           COMMENT '标准器要求',
    env_condition VARCHAR(120)           COMMENT '环境条件',
    unit_fee      DECIMAL(10,2) NOT NULL COMMENT '单项收费（元）',
    remark        VARCHAR(200)           COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_procedure_no (proc_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检定规程';

-- ---------------------------- 4. 检定委托与流转单 ----------------------------
CREATE TABLE lab_calibration_order (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(32)   NOT NULL COMMENT '委托单号',
    client_id        BIGINT                 COMMENT '送检客户',
    instrument_id    BIGINT                 COMMENT '受检器具',
    inst_no          VARCHAR(32)            COMMENT '器具编号快照（自然主键冗余）',
    procedure_id     BIGINT                 COMMENT '检定规程',
    receive_date     DATE                   COMMENT '收样日期',
    verifier         VARCHAR(32)            COMMENT '检定员',
    checker          VARCHAR(32)            COMMENT '核验员',
    measured_data    VARCHAR(500)           COMMENT '实测数据项（历史文本，升级前数据只读保留；; 分隔多条，| 分隔 校准点|标准值|示值|示值误差）',
    current_record_version_id BIGINT                 COMMENT '当前核验通过的原始记录版本 id（新确认版本后切换；NULL=尚无确认版本或纯旧文本委托）',
    cert_record_version_id    BIGINT                 COMMENT '证书固定引用的原始记录版本 id（发放瞬间快照写入，此后永不变更）',
    conclusion       VARCHAR(16)            COMMENT 'PASS 合格 / LIMITED 限用 / FAIL 不合格',
    cert_no          VARCHAR(48)            COMMENT '证书编号',
    status           VARCHAR(20)   NOT NULL COMMENT 'RECEIVED 收样 / CALIBRATING 检定中 / VERIFYING 核验 / CERTIFYING 出证 / ISSUED 已发放',
    cert_valid_until DATE                   COMMENT '证书有效期至',
    fee              DECIMAL(10,2)          COMMENT '本次收费（元）',
    remark           VARCHAR(200)           COMMENT '备注',
    updated_at       DATETIME               COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_status (status),
    KEY idx_order_client (client_id),
    KEY idx_order_instrument (instrument_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检定委托与流转单';

-- ---------------------------- 5. 标准器 ----------------------------
CREATE TABLE lab_standard_device (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    device_no       VARCHAR(32) NOT NULL COMMENT '标准器编号',
    name            VARCHAR(80) NOT NULL COMMENT '标准器名称',
    model           VARCHAR(64)           COMMENT '型号',
    measure_range   VARCHAR(80)           COMMENT '测量范围',
    trace_org       VARCHAR(80)           COMMENT '溯源机构',
    last_trace_date DATE                  COMMENT '上次溯源日期',
    cert_valid_until DATE                 COMMENT '证书有效期至',
    status          VARCHAR(20) NOT NULL  COMMENT 'IN_USE 在用 / STOPPED 停用 / TRACING 送检',
    remark          VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_no (device_no),
    KEY idx_device_valid (cert_valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标准器';

-- ---------------------------- 6. 委托-标准器挂接与占用 ----------------------------
-- 一行 = 某次把标准器挂接到委托的事实；released_at 为空表示该标准器当前仍被本笔委托占用。
-- active_flag 是 MySQL 生成列（占用中=设备id，已释放=NULL），配合唯一键实现
-- 「同一标准器全局至多一条占用中行」——两名检定员并发提交时，数据库只可能放行一笔。
-- released_reason：DETACHED 收样态解除 / REPLACED 收样态替换 / ROLLBACK_RECEIVED 检定中退回收样 /
--                  VOIDED 委托作废 / ISSUED 最终发放（留痕，历史记录不删）。
CREATE TABLE lab_order_device (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    order_id          BIGINT      NOT NULL COMMENT '委托单 id',
    device_id         BIGINT      NOT NULL COMMENT '标准器 id',
    linked_at         DATETIME    NOT NULL COMMENT '挂接时间',
    cert_valid_snapshot DATE               COMMENT '挂接时证书有效期至快照（审计用）',
    released_at       DATETIME             COMMENT '释放时间，NULL = 占用中',
    released_reason   VARCHAR(24)           COMMENT '释放原因',
    active_flag       BIGINT GENERATED ALWAYS AS (CASE WHEN released_at IS NULL THEN device_id ELSE NULL END) VIRTUAL
                          COMMENT '占用中=设备id，已释放=NULL（生成列，仅占用行参与唯一约束）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_device (active_flag),
    UNIQUE KEY uk_active_order_device (order_id, active_flag),
    KEY idx_od_order (order_id, released_at),
    KEY idx_od_device (device_id, released_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='委托-标准器挂接与占用关系';


-- ---------------------------- 7. 原始检定记录版本 ----------------------------
-- 一行 = 一笔委托的一个原始记录版本（草稿/待核验/已确认/已退回/已撤回/已替代），全部保留、不物理删除。
-- version_no 从 1 起：退回重提必须 +1（新版本），误提交撤回沿用同号（草稿承接）。
-- 状态流转由服务端在委托行悲观锁内串行判定：
--   DRAFT → SUBMITTED（冻结）→ CONFIRMED（旧 CONFIRMED 置 SUPERSEDED）
--          SUBMITTED → REJECTED（派生下一版 DRAFT，明细整版复制）
--          SUBMITTED → WITHDRAWN（同号 DRAFT 承接，提交事实留痕）
CREATE TABLE lab_raw_record_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL COMMENT '所属委托 id',
    version_no      INT          NOT NULL COMMENT '版本序号（退回重提 +1；撤回沿用同号）',
    status          VARCHAR(16)  NOT NULL COMMENT 'DRAFT 草稿 / SUBMITTED 待核验 / CONFIRMED 已确认 / REJECTED 已退回 / WITHDRAWN 已撤回 / SUPERSEDED 已替代',
    conclusion      VARCHAR(16)           COMMENT '提交时结论 PASS/LIMITED/FAIL（随版本冻结）',
    submitted_by    VARCHAR(32)           COMMENT '提交人（检定员，提交时写入冻结）',
    submitted_at    DATETIME              COMMENT '提交时间',
    confirmed_by    VARCHAR(32)           COMMENT '核验通过人（核验员）',
    confirmed_at    DATETIME              COMMENT '核验通过时间',
    rejected_by     VARCHAR(32)           COMMENT '退回/打回操作人（核验员）',
    rejected_at     DATETIME              COMMENT '退回时间',
    reject_reason   VARCHAR(500)          COMMENT '退回原因（必填，检定员据此修订）',
    origin          VARCHAR(16)           COMMENT '草稿来源：MANUAL 手建 / REJECT 退回派生 / WITHDRAW 撤回承接 / LEGACY 旧文本转换',
    remark          VARCHAR(500)          COMMENT '整版备注（环境条件、读数说明等）',
    created_at      DATETIME     NOT NULL COMMENT '建立时间',
    updated_at      DATETIME              COMMENT '最后更新时间',
    PRIMARY KEY (id),
    KEY idx_rrv_order (order_id, version_no),
    KEY idx_rrv_status (order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始检定记录版本';

-- ---------------------------- 8. 原始记录数据项明细 ----------------------------
-- 每个版本各自一份完整明细：版本间可逐行逐字段比对；版本离开草稿后明细只读，
-- 服务端不存在改/删已冻结版本明细的路径（退回重提是新版本 + 新明细，旧版不受影响）。
CREATE TABLE lab_raw_record_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    version_id       BIGINT       NOT NULL COMMENT '所属版本 id',
    line_no          INT          NOT NULL COMMENT '版内行序（从 1 起，连续）',
    cal_point        VARCHAR(80)           COMMENT '校准点（检定点位）',
    standard_value   VARCHAR(60)           COMMENT '标准值（保留原始书写）',
    indicated_value  VARCHAR(60)           COMMENT '示值',
    error_value      VARCHAR(60)           COMMENT '示值误差',
    unit             VARCHAR(20)           COMMENT '计量单位（mm/MPa/V…）',
    remark           VARCHAR(200)          COMMENT '本行必要备注（异常/复测/修正）',
    PRIMARY KEY (id),
    KEY idx_rri_version (version_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始记录数据项明细';

-- ---------------------------- 9. 原始记录与人员流转事件留痕（append-only） ----------------------------
-- 只插入、不修改不删除：退回意见、提交/确认人与时间、撤回、人员改派、出证打回、证书固定全部可追溯。
CREATE TABLE lab_record_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL COMMENT '所属委托 id',
    version_id   BIGINT                COMMENT '关联版本 id（人员改派可空）',
    version_no   INT                   COMMENT '关联版本号',
    event_type   VARCHAR(24)  NOT NULL COMMENT 'DRAFT_CREATE/CONVERTED/SUBMIT/CONFIRM/REJECT/WITHDRAW/SUPERSEDE/CERT_REOPEN/ASSIGN/CERT_FIXED',
    actor        VARCHAR(32)           COMMENT '操作人（具名留痕）',
    detail       VARCHAR(500)          COMMENT '事件说明 / 退回原因 / 改派前后值',
    created_at   DATETIME     NOT NULL COMMENT '事件时间',
    PRIMARY KEY (id),
    KEY idx_re_order (order_id, id),
    KEY idx_re_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始记录流转事件留痕';


-- ---------------------------- 送检客户 ----------------------------
INSERT INTO lab_client (id, code, name, credit_code, contact_name, phone, address, settle_type, settle_days, remark, created_at) VALUES
    (1,  'C-1001', '华曦精密机械制造有限公司',   '91310115MA1K3QX21H', '沈亦周', '13801750021', '上海市浦东新区科苑路 118 号',      'MONTHLY', 30,  '年送检量最大的客户，月结 30 天',   '2023-04-12'),
    (2,  'C-1002', '中衡计量检测服务有限公司',   '91110108MA01YT6L4K', '陆文青', '13902281173', '北京市海淀区学院路 30 号',        'MONTHLY', 45,  '同行委托，多为长度类器具',         '2023-06-08'),
    (3,  'C-1003', '蓝海石化仪表厂',             '91370211MA3DXW8P6G', '邱孟津', '13705328910', '山东省青岛市黄岛区江山路 66 号',  'MONTHLY', 60,  '压力表与安全阀批量送检',           '2023-08-21'),
    (4,  'C-1004', '南方电子衡器有限公司',       '91440300MA5FJ2T70X', '温掌珠', '13500293347', '广东省深圳市宝安区新桥街道 9 号', 'CASH',    0,   '现结客户，随送随取',               '2024-01-09'),
    (5,  'C-1005', '康宁医药冷链科技有限公司',   '91330106MA2H1YB39T', '傅雪凝', '15857129906', '浙江省杭州市西湖区文一西路 88 号','MONTHLY', 30,  '温湿度计、温度计为主',             '2024-03-15'),
    (6,  'C-1006', '天工建筑工程检测中心',       '91230103MA1BU7W54E', '崔立坤', '13614507722', '黑龙江省哈尔滨市南岗区学府路 3 号','MONTHLY', 45, '力学类器具（万能试验机）',         '2024-05-20'),
    (7,  'C-1007', '明泉水务集团第三水厂',       '91350102MA32QLP81W', '贺秋白', '13905013328', '福建省福州市鼓楼区白马中路 21 号','CASH',    0,   '水表批量检定',                     '2024-07-02'),
    (8,  'C-1008', '同辉电力设备检修公司',       '91420106MA4KTN335R', '殷之寒', '13871264490', '湖北省武汉市武昌区徐东大街 45 号','MONTHLY', 60,  '电测类标准器送检多',              '2024-09-11'),
    (9,  'C-1009', '瑞康医学检验实验室',         '91110105MA01XY8H2P', '钟晚宜', '13511028877', '北京市朝阳区北苑路 128 号',      'MONTHLY', 30,  '移液器、天平周期性送检',           '2025-01-06'),
    (10, 'C-1010', '恒通物流称重服务站',         '91320594MA1MLKD60Y', '冉旭东', '13776042215', '江苏省苏州市工业园区星湖街 328 号','CASH',   0,   '汽车衡、台秤为主，多为现结',       '2025-03-18'),
    (11, 'C-1011', '广联新能源电池研究院',       '91440605MA54YB12XK', '施南屏', '13928650174', '广东省佛山市南海区狮山镇兴业路 7 号','MONTHLY', 45,'内阻仪、充放电测试仪',            '2025-06-24'),
    (12, 'C-1012', '西岭食品药品检验所',         '91510107MA6C8RQ075', '甄砚秋', '13880231196', '四川省成都市武侯区一环路南四段 12 号','MONTHLY', 30,'政府客户，周期固定',             '2025-09-30');

-- ---------------------------- 计量器具台账 ----------------------------
INSERT INTO lab_instrument (id, inst_no, name, model_spec, measure_range, accuracy_grade, manufacturer, factory_no,
                            cycle_months, last_cal_date, next_due_date, status, client_id, remark, updated_at) VALUES
    (1,  'JLB-0001', '外径千分尺',     '(0-25)mm',      '0-25mm',            '0级',   '成量',       'CL-2021-0331', 12, '2025-10-11', '2026-10-11', 'IN_USE',         1, '机加工车间现场使用',   '2026-09-01 09:12:00'),
    (2,  'JLB-0002', '外径千分尺',     '(25-50)mm',     '25-50mm',           '0级',   '成量',       'CL-2021-0332', 12, '2025-11-24', '2026-11-24', 'IN_USE',         1, '机加工车间现场使用',   '2026-09-01 09:12:00'),
    (3,  'JLB-0003', '数显卡尺',       '0-150mm/0.01mm','0-150mm',           '1级',   '哈量',       'HL-2022-1187', 12, '2025-12-19', '2026-12-19', 'IN_USE',         1, '检验科量具',           '2026-09-02 14:30:00'),
    (4,  'JLB-0004', '量块',           '38块组',        '0.5-100mm',         '2等',   '苏联计量厂', 'SU-2019-0042', 24, '2024-10-15', '2026-10-15', 'IN_USE',         2, '临期，需安排送检',     '2026-09-05 10:02:00'),
    (5,  'JLB-0005', '百分表',         '0-10mm/0.01mm', '0-10mm',            '0级',   '桂量',       'GL-2020-7710', 12, '2025-11-02', '2026-11-02', 'IN_USE',         2, '长度组表类',           '2026-09-05 10:05:00'),
    (6,  'JLB-0006', '精密压力表',     'YB-150A',       '0-1.6MPa',          '0.4级', '西安仪表',   'XA-2021-5580', 6,  '2026-05-16', '2026-11-16', 'IN_USE',         3, '锅炉房压力表',         '2026-09-07 15:40:00'),
    (7,  'JLB-0007', '一般压力表',     'Y-100',         '0-4.0MPa',          '1.6级', '杭州富阳',   'HZ-2018-0903', 6,  '2026-03-12', '2026-09-12', 'IN_USE',         3, '已超期（停用待检）',   '2026-09-12 08:20:00'),
    (8,  'JLB-0008', '电子天平',       'FA2004B',       '0-200g',            'Ⅰ级',  '上海精密',   'SJ-2022-4408', 12, '2025-12-08', '2026-12-08', 'IN_USE',         9, '化验室称量用',         '2026-09-10 11:15:00'),
    (9,  'JLB-0009', '机械天平',       'TG328A',        '0-200g',            'Ⅱ级',  '湘仪',       'XY-2017-2271', 12, '2025-10-05', '2026-10-05', 'IN_USE',         9, '临期，提前送检',       '2026-09-11 09:48:00'),
    (10, 'JLB-0010', '数字温度指示仪', 'XMT-8000',      '-50-300℃',         '0.5级', '重庆川仪',   'CQ-2021-6612', 12, '2025-12-25', '2026-12-25', 'IN_USE',         5, '冷链库房挂表',         '2026-09-12 10:30:00'),
    (11, 'JLB-0011', '双金属温度计',   'WSS-411',       '0-150℃',           '1.5级', '天津仪表',   'TJ-2019-3390', 12, '2026-01-18', '2027-01-18', 'IN_USE',         5, '冷库巡检表',           '2026-09-12 10:33:00'),
    (12, 'JLB-0012', '指针绝缘电阻表', 'ZC-7',          '0-1000MΩ/1000V',   '10级',  '武汉康达',   'WH-2020-1198', 12, '2026-02-20', '2027-02-20', 'IN_USE',         8, '电气检修班组',         '2026-09-14 13:05:00'),
    (13, 'JLB-0013', '数字万用表',     'UT61E+',        'DC 0-1000V',        '0.5级', '优利德',     'UNI-2023-8861',12, '2026-04-09', '2027-04-09', 'IN_USE',         8, '变电站日常用表',       '2026-09-14 13:08:00'),
    (14, 'JLB-0014', '三相电能表检定装置','YD-3A',      '3×220V/5A',        '0.1级', '深圳科陆',   'SZ-2021-2026', 12, '2026-03-26', '2027-03-26', 'IN_USE',         8, '电测室主力装置',       '2026-09-15 09:00:00'),
    (15, 'JLB-0015', '万能材料试验机', 'WDW-100E',      '0-100kN',           '1级',   '济南试金',   'JN-2019-0733', 12, '2025-10-19', '2026-10-19', 'IN_USE',         6, '临期，力学室重点',     '2026-09-16 14:22:00'),
    (16, 'JLB-0016', '布氏硬度计',     'HB-3000B',      '8-650HBW',          '1级',   '莱州华银',   'LZ-2020-5064', 12, '2026-01-11', '2027-01-11', 'IN_USE',         6, '金相实验室共享',       '2026-09-16 14:26:00'),
    (17, 'JLB-0017', '电子秤',         'TCS-150',       '0-150kg',           'Ⅲ级',  '上海寺冈',   'SM-2022-6028', 12, '2026-02-14', '2027-02-14', 'IN_USE',         10, '物流收发货过磅',       '2026-09-18 16:40:00'),
    (18, 'JLB-0018', '静态汽车衡',     'SCS-80',        '0-80t',             'Ⅲ级',  '宁波柯力',   'NB-2017-1520', 6,  '2026-03-20', '2026-09-20', 'IN_USE',         10, '超期，已报停待检',     '2026-09-20 08:15:00'),
    (19, 'JLB-0019', '电池内阻测试仪', 'AT520SE',       '0-3kΩ',            '0.5级', '常州同惠',   'CZ-2023-4412', 12, '2026-04-25', '2027-04-25', 'IN_USE',         11, '电芯分选用',           '2026-09-21 11:28:00'),
    (20, 'JLB-0020', '移液器',         'Research Plus', '10-100μL',         'A级',   '艾本德',     'EP-2022-9007', 12, '2026-05-30', '2027-05-30', 'IN_USE',         9, '医学检验使用',         '2026-09-21 11:31:00'),
    (21, 'JLB-0021', '液相色谱仪',     'LC-2030',       '0-5AU',             'A级',   '岛津',       'SH-2020-7788', 12, '2025-10-02', '2026-10-02', 'IN_CALIBRATION', 12, '临期，送检中',         '2026-09-22 15:02:00'),
    (22, 'JLB-0022', '紫外可见分光光度计','UV-2600',    '190-1100nm',        'A级',   '岛津',       'SH-2019-3301', 12, '2025-11-28', '2026-11-28', 'IN_CALIBRATION', 12, '送检中',               '2026-09-22 15:05:00'),
    (23, 'JLB-0023', '旧式游标卡尺',   '0-300mm',       '0-300mm',           '2级',   '哈量',       'HL-2009-0412', 12, '2023-06-10', '2024-06-10', 'SCRAPPED',       1, '磨损严重，已报废',     '2026-08-11 09:20:00'),
    (24, 'JLB-0024', '停用扭矩扳手',   'NJ-300',        '50-300N·m',        '2级',   '东日',       'TR-2015-6640', 12, NULL,         NULL,         'STOPPED',        1, '长期停用封存，暂不排期','2026-07-30 10:00:00');

-- ---------------------------- 检定规程 ----------------------------
INSERT INTO lab_procedure (id, proc_no, name, category, standard_no, device_req, env_condition, unit_fee, remark) VALUES
    (1,  'JJG 21-2021',  '外径千分尺检定规程',        '长度类',   'GB/T 1216-2018', '2 等量块 + 平面平晶',              '温度 (20±3)℃，湿度 ≤65%RH', 120.00, '生产中位等级高'),
    (2,  'JJG 30-2021',  '通用卡尺检定规程',          '长度类',   'GB/T 21389-2008','3 等量块 + 专用检具',              '温度 (20±5)℃，湿度 ≤70%RH', 100.00, NULL),
    (3,  'JJG 146-2019', '量块检定规程',              '长度类',   'GB/T 6093-2001', '量块比较仪 + 2 等标准量块',         '温度 (20±0.5)℃，恒温 4h',   480.00, '恒温要求最严'),
    (4,  'JJG 34-2022',  '指示表（百分表）检定规程',  '长度类',   'GB/T 1219-2021', '百分表检定仪（标准位移装置）',      '温度 (20±5)℃，湿度 ≤70%RH', 90.00,  NULL),
    (5,  'JJG 52-2020',  '弹性元件式一般压力表检定规程','压力类', 'GB/T 1226-2017', '0.05 级活塞式压力计',               '温度 (20±5)℃，湿度 ≤75%RH', 60.00,  '量大、单价低'),
    (6,  'JJG 49-2019',  '精密压力表检定规程',        '压力类',   'GB/T 1227-2017', '0.02 级数字压力计标准装置',         '温度 (20±2)℃，湿度 ≤70%RH', 180.00, NULL),
    (7,  'JJG 1036-2022','电子天平检定规程',          '质量类',   'GB/T 26497-2011','E2 等级标准砝码组',                '温度 (20±5)℃，无强气流',    260.00, '需提前恒温'),
    (8,  'JJG 98-2019',  '机械天平检定规程',          '质量类',   'GB/T 4167-2011', 'F1 等级标准砝码组',                 '温度 (20±3)℃，湿度 ≤70%RH', 220.00, NULL),
    (9,  'JJG 617-2019', '数字温度指示调节仪检定规程','热工类',   'GB/T 13639-2008','标准铂电阻温度计 + 直流电阻箱',     '温度 (20±2)℃，湿度 ≤70%RH', 200.00, NULL),
    (10, 'JJG 130-2019', '工作用玻璃液体温度计检定规程','热工类', 'GB/T 15812-2016','二等标准水银温度计 + 恒温槽',       '恒温槽波动度 ≤0.02℃',       80.00,  NULL),
    (11, 'JJG 1005-2019','电子式绝缘电阻表检定规程',  '电测类',   'GB/T 18216-2021','高阻标准器 + 标准电压源',           '温度 (20±5)℃，湿度 ≤70%RH', 150.00, NULL),
    (12, 'JJG 124-2019', '电流表电压表功率表检定规程','电测类',   'GB/T 7676-2017', '0.01 级交直流标准源',               '温度 (20±2)℃，湿度 ≤65%RH', 190.00, NULL),
    (13, 'JJG 139-2019', '拉力压力和万能试验机检定规程','力学类', 'GB/T 3159-2020', '0.3 级标准测力仪',                  '温度 (20±5)℃，湿度 ≤70%RH', 760.00, '单项收费最高'),
    (14, 'JJG 150-2020', '金属布氏硬度计检定规程',    '力学类',   'GB/T 231.2-2021','标准硬度块 + 标准显微镜',           '温度 (20±5)℃，湿度 ≤70%RH', 420.00, NULL),
    (15, 'JJG 539-2016', '数字指示秤检定规程',        '衡器类',   'GB/T 7723-2017', 'M1 等级标准砝码',                   '温度 (20±5)℃，无振源',      140.00, NULL),
    (16, 'JJG 1002-2019','静态电子汽车衡检定规程',    '衡器类',   'GB/T 7723-2017', '标准砝码 + 检衡车',                 '室外作业，风力 ≤5 级',      1680.00, '需出车到现场');

-- ---------------------------- 检定委托与流转单 ----------------------------
INSERT INTO lab_calibration_order (id, order_no, client_id, instrument_id, inst_no, procedure_id, receive_date,
                                   verifier, checker, measured_data, conclusion, cert_no, status,
                                   cert_valid_until, fee, remark, updated_at) VALUES
    (1,  'WT2026-0001', 1,  1,  'JLB-0001', 1,  '2026-09-18', '赵砚青', '孙敬之',
     '0mm|+0.0000mm|+0.0002mm|+0.2μm;12.5mm|+12.5000mm|+12.5004mm|+0.4μm;25.0000mm|+25.0000mm|+25.0005mm|+0.5μm',
     'LIMITED', 'CERT-2026-0188', 'ISSUED', '2027-09-18', 120.00, '限定 *.5 以下量程使用', '2026-09-22 16:20:00'),
    (2,  'WT2026-0002', 1,  3,  'JLB-0003', 2,  '2026-09-19', '赵砚青', '孙敬之',
     '0mm|+0.000mm|+0.002mm|+0.002mm;75.000mm|+75.000mm|+75.004mm|+0.004mm;150.000mm|+150.000mm|+150.005mm|+0.005mm',
     'PASS', 'CERT-2026-0191', 'ISSUED', '2027-09-19', 100.00, NULL, '2026-09-23 10:12:00'),
    (3,  'WT2026-0003', 3,  6,  'JLB-0006', 6,  '2026-09-15', '陈听澜', '孙敬之',
     '0.4MPa|+0.4000MPa|+0.4003MPa|+0.075%;0.8MPa|+0.8000MPa|+0.8006MPa|+0.075%;1.2000MPa|+1.2000MPa|+1.2011MPa|+0.092%',
     'PASS', 'CERT-2026-0166', 'ISSUED', '2027-03-15', 180.00, NULL, '2026-09-21 14:45:00'),
    (4,  'WT2026-0004', 9,  8,  'JLB-0008', 7,  '2026-09-10', '林砚秋', '孙敬之',
     '20g|+20.0000g|+20.0003g|+0.3mg;100g|+100.0000g|+100.0008g|+0.8mg;200g|+200.0000g|+200.0015g|+1.5mg',
     'PASS', 'CERT-2026-0152', 'ISSUED', '2027-09-10', 260.00, NULL, '2026-09-16 11:30:00'),
    (5,  'WT2026-0005', 2,  4,  'JLB-0004', 3,  '2026-09-21', '陈听澜', '待分配',
     '10mm|+10.0000mm|+10.0001mm|+0.10μm;50mm|+50.0000mm|+50.0002mm|+0.20μm;100mm|+100.0000mm|+100.0003mm|+0.30μm',
     'LIMITED', 'CERT-2026-0203', 'CERTIFYING', '2028-09-21', 480.00, '证书待发放给客户', '2026-09-23 09:40:00'),
    (6,  'WT2026-0006', 6,  15, 'JLB-0015', 13, '2026-09-20', '周慕言', '待分配',
     '20kN|+20.00kN|+20.03kN|+0.15%;60kN|+60.00kN|+60.05kN|+0.08%;100kN|+100.00kN|+100.09kN|+0.09%',
     'PASS', 'CERT-2026-0201', 'CERTIFYING', '2027-09-20', 760.00, '力学室加急件', '2026-09-23 15:05:00'),
    (7,  'WT2026-0007', 10, 17, 'JLB-0017', 15, '2026-09-19', '周慕言', '待分配',
     '10kg|+10.000kg|+10.002kg|+2g;75kg|+75.000kg|+75.004kg|+4g;150kg|+150.000kg|+150.007kg|+7g',
     'PASS', 'CERT-2026-0198', 'CERTIFYING', '2027-09-19', 140.00, NULL, '2026-09-22 17:20:00'),
    (8,  'WT2026-0008', 12, 21, 'JLB-0021', 10, '2026-09-22', '林砚秋', '孙敬之',
     '0℃|+0.00℃|+0.03℃|+0.03℃;100.00℃|+100.00℃|+100.06℃|+0.06℃;300.00℃|+300.00℃|+300.21℃|+0.21℃',
     NULL, NULL, 'VERIFYING', NULL, 80.00, '核验中发现 300℃ 点偏差偏大', '2026-09-23 10:55:00'),
    (9,  'WT2026-0009', 8,  13, 'JLB-0013', 12, '2026-09-22', '陈听澜', '孙敬之',
     'DC 10V|+10.0000V|+10.0002V|+0.0020V;DC 100V|+100.000V|+100.011V|+0.0110V;DC 1000V|+1000.00V|+1000.24V|+0.2400V',
     'LIMITED', NULL, 'VERIFYING', NULL, 190.00, '疑似限用，待复测确认', '2026-09-23 11:20:00'),
    (10, 'WT2026-0010', 5,  11, 'JLB-0011', 10, '2026-09-23', '周慕言', '待分配',
     '50.0℃|+50.00℃|+50.04℃|+0.04℃;100.0℃|+100.00℃|+100.09℃|+0.09℃;150.0℃|+150.00℃|+150.12℃|+0.12℃',
     'PASS', NULL, 'VERIFYING', NULL, 80.00, NULL, '2026-09-23 14:08:00'),
    (11, 'WT2026-0011', 3,  7,  'JLB-0007', 5,  '2026-09-23', '待分配', '待分配',
     '1.0MPa|+1.000MPa|+1.002MPa|+0.2%;2.0MPa|+2.000MPa|+2.005MPa|+0.25%;4.000MPa|+4.000MPa|+4.011MPa|+0.28%',
     NULL, NULL, 'CALIBRATING', NULL, 60.00, '超期补检，先做外观与示值初检', '2026-09-23 08:35:00'),
    (12, 'WT2026-0012', 12, 22, 'JLB-0022', 16, '2026-09-23', '林砚秋', '待分配',
     '235nm|+235.0nm|+235.2nm|+0.2nm;486nm|+486.0nm|+486.3nm|+0.3nm;656nm|+656.0nm|+656.4nm|+0.4nm',
     NULL, NULL, 'CALIBRATING', NULL, 320.00, '波长示值误差检测中', '2026-09-23 09:15:00'),
    (13, 'WT2026-0013', 11, 19, 'JLB-0019', 12, '2026-09-23', '陈听澜', '待分配',
     '10mΩ|+10.000mΩ|+10.004mΩ|+0.04%;100mΩ|+100.00mΩ|+100.06mΩ|+0.06%;1Ω|+1.0000Ω|+1.0008Ω|+0.08%',
     NULL, NULL, 'CALIBRATING', NULL, 190.00, NULL, '2026-09-23 13:40:00'),
    (14, 'WT2026-0014', 4,  9,  'JLB-0009', 8,  '2026-09-24', '待分配', '待分配', NULL, NULL, NULL,
     'CALIBRATING', NULL, 220.00, '刚从分院收样，待排期', '2026-09-24 08:50:00'),
    (15, 'WT2026-0015', 10, 18, 'JLB-0018', 16, '2026-09-24', '待分配', '待分配', NULL, NULL, NULL,
     'RECEIVED', NULL, 1680.00, '汽车衡超期，需安排出车检定', '2026-09-24 09:05:00'),
    (16, 'WT2026-0016', 7,  2,  'JLB-0002', 1,  '2026-09-24', '待分配', '待分配', NULL, NULL, NULL,
     'RECEIVED', NULL, 120.00, NULL, '2026-09-24 09:12:00'),
    (17, 'WT2026-0017', 6,  16, 'JLB-0016', 14, '2026-09-24', '待分配', '待分配', NULL, NULL, NULL,
     'RECEIVED', NULL, 420.00, '硬度块配套一并送来', '2026-09-24 09:30:00'),
    (18, 'WT2026-0018', 2,  5,  'JLB-0005', 4,  '2026-09-16', '赵砚青', '孙敬之',
     '2mm|+2.000mm|+2.006mm|+0.006mm;6mm|+6.000mm|+6.011mm|+0.011mm;10mm|+10.000mm|+10.019mm|+0.019mm',
     'FAIL', 'CERT-2026-0169', 'ISSUED', '2027-09-16', 90.00, '示值超差，判定不合格并出具结果通知书', '2026-09-20 15:35:00'),
    (19, 'WT2026-0019', 4,  17, 'JLB-0017', 15, '2026-09-24', '周慕言', '待分配', NULL, NULL, NULL,
     'VOIDED', NULL, 140.00, '客户撤单，委托作废；已挂标准器随作废一并释放', '2026-09-25 10:20:00');

-- ---------------------------- 标准器 ----------------------------
INSERT INTO lab_standard_device (id, device_no, name, model, measure_range, trace_org, last_trace_date,
                                 cert_valid_until, status, remark) VALUES
    (1,  'STD-0001', '标准铂电阻温度计', 'WZPB-2',      '0-419.527℃',   '中国计量科学研究院',   '2025-11-06', '2027-11-06', 'IN_USE',  '热工室主标准'),
    (2,  'STD-0002', '标准水银温度计',   '二等',        '-30-300℃',     '华东国家计量测试中心', '2024-11-02', '2026-11-02', 'IN_USE',  '临期，两个月内需复检'),
    (3,  'STD-0003', '标准量块',         '2等·83块组', '0.5-500mm',     '中国计量科学研究院',   '2025-12-18', '2027-12-18', 'IN_USE',  '长度室基准'),
    (4,  'STD-0004', '标准洛氏硬度块',   'HRC 25/45/60','20-70HRC',     '山东省计量科学研究院', '2026-03-15', '2027-03-15', 'IN_USE',  NULL),
    (5,  'STD-0005', '活塞式压力计',     'YS-6',        '0-6MPa',       '北京市计量检测科学研究院','2024-09-02','2026-09-02', 'IN_USE',  '已过证，禁止出具数据'),
    (6,  'STD-0006', 'E2 等级标准砝码',  '1mg-500g',    '1mg-500g',     '中国计量科学研究院',   '2025-10-21', '2027-10-21', 'IN_USE',  '质量基准组'),
    (7,  'STD-0007', '标准测力仪',       'DBP-1000',    '0-1000kN',     '中国计量科学研究院',   '2026-01-24', '2028-01-24', 'IN_USE',  '力学室主标准'),
    (8,  'STD-0008', '三相标准功率源',   'CL3115',      '3×380V/100A',  '国网电力科学研究院',   '2026-02-27', '2027-02-27', 'IN_USE',  NULL),
    (9,  'STD-0009', '数字多用表校准器', '5522A',       'DC 0-1000V',   '华东国家计量测试中心', '2024-10-12', '2026-10-12', 'IN_USE',  '临期，电测室重点关注'),
    (10, 'STD-0010', 'M1 等级标准砝码',  '1kg-1000kg',  '1kg-1000kg',   '江苏省计量科学研究院', '2025-08-09', '2027-08-09', 'IN_USE',  '衡器检定专用'),
    (11, 'STD-0011', '恒温油槽',         'HWC-300',     '90-300℃',      '华中国家计量测试中心', '2026-04-11', '2028-04-11', 'IN_USE',  NULL),
    (12, 'STD-0012', '老式标准压力表',   'YB-150',      '0-25MPa',      '上海市计量测试技术研究院','2023-05-19','2025-05-19', 'STOPPED', '停用封存，待报废'),
    (13, 'STD-0013', '标准位移检定仪',   'SJY-50',      '0-50mm',       '中国计量科学研究院',   '2025-07-30', '2026-07-30', 'TRACING', '已打包送上级机构溯源');

-- ---------------------------- 委托-标准器挂接与占用（种子） ----------------------------
-- 占用中（released_at 为空）的设备互斥；已发行(active_flag=NULL)为历史留痕行，同一设备可多条。
--   11 号：STD-0005 已过期 + STD-0012 已停用 → 推进检定中必被拦截，且两件都会被点名
--   12 号：STD-0013 送检中（TRACING）→ 推进拦截
--   13 号：当前占用 STD-0008，另有一行 STD-0009 收样态被「替换」的留痕
--   16 号：收样无挂接，演示与 8 号竞争 STD-0002 必失败（8 号 VERIFYING 仍占用）
--   14 号：无任何挂接行，演示历史委托无标准器仍可查询与推进
INSERT INTO lab_order_device (id, order_id, device_id, linked_at, cert_valid_snapshot, released_at, released_reason) VALUES
    -- 占用中：进行中的委托
    (1,   5,  3,  '2026-09-21 09:45:00', '2027-12-18', NULL, NULL),
    (2,   6,  7,  '2026-09-20 08:40:00', '2028-01-24', NULL, NULL),
    (3,   7,  10, '2026-09-19 09:05:00', '2027-08-09', NULL, NULL),
    (4,   8,  2,  '2026-09-22 09:10:00', '2026-11-02', NULL, NULL),
    (5,   9,  9,  '2026-09-22 13:50:00', '2026-10-12', NULL, NULL),
    (6,  10,  1,  '2026-09-23 09:20:00', '2027-11-06', NULL, NULL),
    (7,  11,  5,  '2026-09-23 08:40:00', '2026-09-02', NULL, NULL),
    (8,  11,  12, '2026-09-23 08:42:00', '2025-05-19', NULL, NULL),
    (9,  12,  13, '2026-09-23 09:20:00', '2026-07-30', NULL, NULL),
    (10, 13,  8,  '2026-09-23 14:00:00', '2027-02-27', NULL, NULL),
    (11, 15,  6,  '2026-09-24 09:30:00', '2027-10-21', NULL, NULL),
    (12, 17,  4,  '2026-09-24 09:40:00', '2027-03-15', NULL, NULL),
    -- 历史留痕：已发放（最终发放，占用释放、记录保留）
    (13, 1,  3,  '2026-09-18 09:30:00', '2027-12-18', '2026-09-22 16:20:00', 'ISSUED'),
    (14, 4,  6,  '2026-09-10 09:30:00', '2027-10-21', '2026-09-16 11:30:00', 'ISSUED'),
    (15, 18, 4,  '2026-09-16 09:00:00', '2027-03-15', '2026-09-20 15:35:00', 'ISSUED'),
    -- 历史留痕：13 号收样时曾挂 STD-0009，后在收样态被替换为 STD-0008
    (16, 13, 9,  '2026-09-23 13:45:00', '2026-10-12', '2026-09-23 13:58:00', 'REPLACED'),
    -- 历史留痕：作废委托 19 号，挂 STD-0011 随作废释放
    (17, 19, 11, '2026-09-24 11:00:00', '2028-04-11', '2026-09-25 10:20:00', 'VOIDED'),
    -- 历史留痕：15 号曾从检定中退回收样一次，STD-0006 被释放后又重新挂接（见第 11 行）
    (18, 15, 6,  '2026-09-24 10:05:00', '2027-10-21', '2026-09-24 15:30:00', 'ROLLBACK_RECEIVED');


-- ---------------------------- 原始记录版本与明细（结构化记录演示种子） ----------------------------
-- 8 号 WT2026-0008（核验中）：第 1 版已提交锁定，等待核验员「孙敬之」确认或写原因退回。
-- 5 号 WT2026-0005（出证中）：第 1 版已核验通过（当前生效版本），证书尚未发放，cert_record_version_id 仍空；
--    发放后才会固定（新流程演示走实际操作；此处不预置固定值）。
-- 14 号 WT2026-0014（检定中、无旧文本）：第 1 版空白草稿，演示批量录入。
-- 其余已核验/已发放委托（1/2/3/4/18 等）保持旧文本只读，不补结构化版本——升级不改变原结论。
INSERT INTO lab_raw_record_version
    (id, order_id, version_no, status, conclusion, submitted_by, submitted_at, confirmed_by, confirmed_at,
     rejected_by, rejected_at, reject_reason, origin, remark, created_at, updated_at) VALUES
    -- 8 号：已提交待核验（本版锁定，核验员只能确认/退回）
    (1,  8,  1, 'SUBMITTED', 'PASS', '林砚秋', '2026-09-23 10:40:00', NULL, NULL, NULL, NULL, NULL,
     'LEGACY', '由旧文本转第一版后提交核验；300℃ 点偏差偏大待核验判定', '2026-09-23 10:20:00', '2026-09-23 10:40:00'),
    -- 5 号：已确认（出证依据），旧 CONFIRMED 无替代版本
    (2,  5,  1, 'CONFIRMED', 'LIMITED', '陈听澜', '2026-09-22 16:10:00', '孙敬之', '2026-09-23 09:05:00',
     NULL, NULL, NULL, 'MANUAL', '限定量程使用见委托备注', '2026-09-22 14:00:00', '2026-09-23 09:05:00'),
    -- 14 号：检定中空白草稿
    (3,  14, 1, 'DRAFT', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     'MANUAL', NULL, '2026-09-24 09:00:00', '2026-09-24 09:00:00');

INSERT INTO lab_raw_record_item (version_id, line_no, cal_point, standard_value, indicated_value, error_value, unit, remark) VALUES
    -- 8 号第 1 版：3 个温度点（旧文本转结构化，单位按读数补 ℃）
    (1, 1, '0℃',       '+0.00℃',   '+0.03℃',   '+0.03℃', '℃', NULL),
    (1, 2, '100.00℃',   '+100.00℃', '+100.06℃', '+0.06℃', '℃', NULL),
    (1, 3, '300.00℃',   '+300.00℃', '+300.21℃', '+0.21℃', '℃', '偏差偏大，核验重点'),
    -- 5 号第 1 版：3 个量块点（限用）
    (2, 1, '10mm',  '+10.0000mm', '+10.0001mm', '+0.10μm', 'mm', NULL),
    (2, 2, '50mm',  '+50.0000mm', '+50.0002mm', '+0.20μm', 'mm', NULL),
    (2, 3, '100mm', '+100.0000mm', '+100.0003mm', '+0.30μm', 'mm', NULL);

-- 8 号、5 号的事件留痕（与版本时间一致，演示「旧版本/提交人/时间持续可查」）
INSERT INTO lab_record_event (order_id, version_id, version_no, event_type, actor, detail, created_at) VALUES
    (8,  1, 1, 'DRAFT_CREATE', '林砚秋', '旧文本实测数据转为第 1 版结构化草稿（解析出 3 条；原文本原样保留并标记为历史记录），请核对后再提交复核', '2026-09-23 10:20:00'),
    (8,  1, 1, 'SUBMIT',       '林砚秋', '第 1 版提交复核，共 3 条数据项', '2026-09-23 10:40:00'),
    (5,  2, 1, 'DRAFT_CREATE', '陈听澜', '检定员建立第 1 版原始记录草稿', '2026-09-22 14:00:00'),
    (5,  2, 1, 'SUBMIT',       '陈听澜', '第 1 版提交复核，共 3 条数据项', '2026-09-22 16:10:00'),
    (5,  2, 1, 'CONFIRM',      '孙敬之', '核验通过，第 1 版确认为出证依据', '2026-09-23 09:05:00'),
    (14, 3, 1, 'DRAFT_CREATE', '系统',   '进入检定环节，自动建立空白原始记录草稿', '2026-09-24 09:00:00');

-- 委托指针：5 号当前确认版本 = 2（证书固定引用留待发放时写入，这里刻意置空以演示「发放才固定」）
UPDATE lab_calibration_order SET current_record_version_id = 2 WHERE id = 5;

SET FOREIGN_KEY_CHECKS = 1;
