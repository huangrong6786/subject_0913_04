# EVOPS 基础工作区 — 地热井回灌试验与井口监测

在原基础工作区（统一返回、异常处理、MyBatis-Plus、H2、Shiro）之上实现了地热井回灌试验与井口监测基础闭环。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus 3.5（乐观锁）、H2 本地文件数据库、Shiro（HTTP Basic）、Thymeleaf。

## 业务模型

井组 `t_well_group` → 试验段 `t_test_section` → 监测点 `t_monitor_point` / 回灌班次 `t_reinjection_shift`
→ 监测批次 `t_monitor_batch` → 井口读数 `t_wellhead_reading`；所有跨表写入同事务落审计 `t_biz_write_audit`。

- **复合键**：井组（group_code）+ 试验段（section_code）+ 回灌班次（业务日期 + 班次序号）构成复合键，
  班次编码 `井组-试验段-日期-S序号`；批次在此基础上追加监测点编码。DB 有唯一索引（含 `(test_section_id, shift_date, shift_index)`）。
- **原子落库**：压力、温度、流量、回灌量四要素随一次请求在同一事务内落库，每条读数保存井口快照（JSON，含复合键上下文与四要素）。
- **状态流转**：批次 `DRAFT → RECORDED → ACCEPTED（冻结 accepted_version 版本快照）→ ACCOUNTED（落账）`；班次 `OPEN → CLOSED`。
- **删除保护**：已验收（ACCEPTED）或已落账（ACCOUNTED）批次不能直接删除。
- **唯一业务键**：井组编码、试验段编码、监测点编码、班次编码/复合键、批次号/复合键均唯一（应用预检 + DB 唯一索引双保险）。
- **审计四要素**：每个写请求在同一事务内落一条 `t_biz_write_audit`：请求号 `request_no`（唯一，兼作并发幂等闸门）、
  操作者（id/name）、业务时区、版本快照（JSON）。请求头：`X-Request-No`、`X-Operator-Id`、`X-Operator-Name`、`X-Biz-Timezone`。

## REST 接口（统一 ApiResponse；业务接口需 HTTP Basic：bootstrap/bootstrap）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/well-groups` / `/test-sections` / `/monitor-points` / `/shifts` | 基础对象建档 |
| GET  | 对应集合（支持 groupId/sectionId/shiftDate 过滤） | 列表 |
| DELETE | 对应集合 `/{id}` | 删除（存在下级时拒绝） |
| POST | `/api/shifts/{id}/close` | 班次 OPEN→CLOSED |
| POST | `/api/batches` | 批次建立（传 shiftId+monitorPointId，或 groupCode/sectionCode/bizDate/shiftIndex/pointCode 复合键，班次缺失时同事务隐式建班） |
| POST | `/api/batches/{id}/readings` | 压力/温度/流量/回灌量同批次原子落库，DRAFT→RECORDED |
| POST | `/api/batches/{id}/accept` | RECORDED→ACCEPTED，冻结版本快照 |
| POST | `/api/batches/{id}/account` | ACCEPTED→ACCOUNTED（body：`{"accountingId":...}`） |
| GET  | `/api/batches/by-key` | 复合键关联查询（groupCode&sectionCode&shiftDate&shiftIndex&pointCode&bizDate） |
| GET/DELETE | `/api/batches`、`/api/batches/{id}` | 列表/删除（已验收/已落账拒绝） |
| GET  | `/api/audits?requestNo=&objectType=` | 写入审计查询 |

## 运营检索（分页 / 多租户数据权限 / 秒级遥测）

在基础闭环之上补充「地热井回灌试验与井口监测运营检索」。所有接口需 HTTP Basic，
且**每次查询都强制施加租户隔离 + 角色对象授权**（谓词在 SQL 内下推，无法被入参绕过）。

账号（HTTP Basic）：

| 账号 | 密码 | 租户 | 角色 / 数据权限 |
|---|---|---|---|
| `bootstrap` | `bootstrap` | 1 | TENANT_ADMIN（向后兼容） |
| `t1admin` | `t1admin` | 1 | 管理员，可见租户 1 全部对象 |
| `t1view` | `t1view` | 1 | 只读账号，仅见 `t_object_grant` 授权井组 |
| `t2admin` | `t2admin` | 2 | 管理员，可见租户 2 全部对象 |

### 回灌批次组合检索

`GET /api/operations/batches`，支持 ≥4 个条件的 AND/范围组合：

- `groupId`（井组）、`sectionId`（试验段）、`status`（DRAFT/RECORDED/ACCEPTED/ACCOUNTED）；
- `bizDateFrom` / `bizDateTo`（业务日期闭区间）；
- `pressureMin` / `pressureMax`（井口压力 MPa 闭区间，命中该批任一读数即返回）。

分页：`pageSize` 仅允许 **1-100**；`page`（1 起始）或 `cursor`（上一页 `nextCursor`）二选一。
返回 `records / total / pageSize / page / nextCursor / hasMore`。

关键实现约束（见 `mapper/GeothermalQueryMapper.xml`）：

- **确定性稳定排序** `(biz_date DESC, id ASC)`，游标锚点为 `(bizDate,id)` 的 Base64，100k 量级翻页不重不漏；
- **关联不放大主表**：多对一维度（井组/试验段/班次/监测点）走 JOIN；一对多井口压力走
  `EXISTS` 半连接（过滤）+ 关联标量子查询（展示 min/max），绝不因读数多条而重复批次；
- 主表冗余 `well_group_id/test_section_id/tenant_id` 标量列并建组合索引，单表即可裁剪；
- 租户谓词恒带；`TENANT_VIEWER` 再叠加 `t_object_grant` 授权井组集合收敛。

### 秒级井口遥测聚合（分区裁剪、禁止 N+1）

独立高频事件表 `t_wellhead_telemetry`（约 150000 条秒级数据），`partition_date` 为
`reading_time` 派生的日期分区裁剪键（DB 生成列），索引 `(tenant_id, partition_date, well_group_id, reading_time)`。

- `GET /api/operations/telemetry/aggregate?groupId=&dateFrom=&dateTo=&granularity=`
  时间桶聚合（MINUTE/FIVE_MIN/TEN_MIN/HOUR/DAY）。**日期范围必填并封顶 7 天**，
  服务层强制把 `partition_date >= ? AND partition_date <= ?` 下推到 SQL，物理上无法退化为全表扫描；
  结果回传实际分区范围与命中样本数。
- `GET /api/operations/telemetry/well-group-pressure?dateFrom=&dateTo=&groupIds=`
  **40 井组压测：单条 `GROUP BY well_group_id` 集合 SQL 一次返回全部授权井组**压力
  min/max/avg/最新值，应用层不逐井发起查询（禁止 N+1）；窗口封顶 31 天，同样强制分区裁剪与租户/授权谓词。

## 观测数据 CSV 批量导入（分片 / 幂等 / 断点续传 / 坏传感器隔离）

井口网关观测数据（对象编号、观测时间、井口压力、温度、流量、回灌量、来源设备、序列号）
通过 CSV 批量导入，落库到 `t_wellhead_observation`；导入任务/分片/逐行明细分别落
`t_import_file` / `t_import_shard` / `t_import_row`，坏传感器名单落 `t_device_quarantine`。

### CSV 格式（首行表头，支持中英文别名）

```
objectCode,observedAt,pressureMpa,temperatureC,flowM3h,injectionVolumeM3,sourceDevice,serialNo
PT-IMP-001,2026-09-10 08:00:00,1.250,65.200,80.500,120.000,DEV-01,SN-000001
```

- 表头别名：`对象编号/观测时间/井口压力/温度/流量/回灌量/来源设备/序列号` 与下划线风格均可；必需列缺失时文件级拒绝。
- 观测时间支持 `yyyy-MM-dd HH:mm:ss` / ISO8601；**跨天补报**按观测日派生 `biz_date`，未来数据（>当前+1天）拒绝。
- 单位校验（物理量纲范围，超界判疑似单位错误/坏传感器）：压力 0-60 MPa、温度 -20~150 ℃、流量 0-500 m³/h、回灌量 0-100000 m³。
- 序列校验：序列号 4-64 位、字母数字开头（可含 `_-:.`）。

### REST 接口（均需 HTTP Basic）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/imports/observations` | multipart 上传（字段名 `file`）。同文件重复上传命中校验和幂等；有未完成分片时断点续传 |
| GET  | `/api/imports/observations/{id}` | 任务汇总：total/success/updated/failed + 分片进度 |
| GET  | `/api/imports/observations/{id}/rows?result=FAILED&page=&pageSize=` | 逐行明细（pageSize 1-100），失败行含**原始行号、失败字段、字段原值、失败原因** |
| POST | `/api/imports/observations/{id}/retry` | 失败分片重试（FAILED/PENDING/PROCESSING 续跑，幂等） |
| POST | `/api/imports/devices/quarantine` / `/devices/release` | 坏传感器隔离 / 解除（body：`{"deviceCode":...,"reason":...}`） |
| GET  | `/api/imports/devices/quarantine` | 当前生效隔离名单 |

### 困难级约束实现

- **分片处理 ≥50,000 行**：按 `evops.import.shard-size`（默认 1000）切分，每分片独立
  `REQUIRES_NEW` 事务，分片间互不回滚；合法/重复/缺列/坏数值混合行**逐行隔离**——
  行级失败只落 `t_import_row` 明细，同分片其他行正常落库，整批不因单行失败回滚。
- **双重幂等**：文件级 `(tenant_id, checksum)`（SHA-256）唯一，重复上传返回同一任务；
  业务键 `(tenant_id, monitor_point_id, serial_no)` 与 `(tenant_id, monitor_point_id, observed_at)`
  唯一——网关重发同序列号/跨文件重复导入记 **UPDATED** 幂等更新，同时刻异序列号记 FAILED（业务键冲突）。
- **断点续传 / 失败重试**：原始文件内容入库；分片状态机 `PENDING/PROCESSING/SUCCESS/FAILED`，
  崩溃残留（PROCESSING）与失败分片在重复上传或 retry 时续跑；行明细按 `(file,rowNo)` 唯一先清后写。
- **锁定/验收保护**：观测日所属批次 `ACCEPTED`（已验收）/`ACCOUNTED`（已落账）或班次
  `CLOSED`（已闭班锁定）时，该观测行 FAILED，**禁止覆盖**。
- **坏传感器隔离**：检疫名单内设备的行逐行拦截（FAILED，不落观测表）；量纲校验同时拦截故障坏数值。
- **审计**：每次导入执行在定稿时落一条 `IMPORT_FILE` 审计（请求号/操作者/业务时区/计数快照）。

### 压测演示数据

`DemoDataSeeder`（幂等，已存在 `SEED-WG-*` 时跳过）：40 井组（两租户各 20）、
400 班次/批次、**100000** 条井口读数事件、**150000** 条秒级遥测（3 个日期分区）、
以及 `t1view` 对租户 1 前 10 井组的授权。运维入口（默认关闭，需配置 `evops.demo-seed.token`）：

```
POST /api/admin/demo-seed        头：X-Seed-Token: <token>
```

## 运行

1. `mvn -q -DskipTests compile`
2. `mvn spring-boot:run`（自动执行 `src/main/resources/schema.sql`，H2 文件在 `data/evops`）
3. `mvn test`：**60 个测试全绿**（9 个闭环集成 + 4 个 REST 冒烟 + 13 个运营检索/遥测大规模 + 7 个运营 REST 冒烟 + 9 个 CSV 导入集成 + 3 个导入 REST 冒烟 + 第四期 6 个时序规则时间线单测 + 6 个规则计算集成 + 3 个时序 REST 冒烟）。

集成测试（`src/test/java/com/evops/geothermal/`）覆盖困难级约束：

- **≥2 个业务对象**：井组、试验段、监测点、班次、批次、读数、审计（7 个）；
- **3 个业务日期**：2026-09-10 / 09-11 / 09-12；
- **5 路并发写入**：同批次乐观锁竞争（只 1 路成功，失败事务读数回滚）与同请求号并发（只 1 路落账、审计仅 1 条）；
- 同事务 + 审计四要素、业务键唯一、原子回滚、删除保护均有断言；
- **CSV 批量导入**：合法/重复/缺列/坏数值混合行逐行隔离、文件校验和 + 业务键双重幂等、
  已验收/已锁定禁覆盖、失败分片重试与断点续传、坏传感器隔离、跨天补报、**50000 行 50 分片**导入；
- **运营检索**：40 井组/400 批次/100000 读数/150000 遥测上验证 ≥4 条件 AND/范围组合、
  游标与确定性主键分页序列一致、一对多关联不放大、pageSize 1-100、租户隔离与只读账号授权收敛、
  遥测分区裁剪精确计数、40 井组单集合 GROUP BY（无 N+1）。

题包根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

题面和质检卷在上一级 `packets/` 目录；模型工作区不得复制 `answers.md`、验收测试或参考修正。

## 井口监测时序规则（峰/平/谷）版本化与试验窗口规则计算（第四期）

运营人员为监测对象（监测点 / 井组）维护**峰 PEAK、平 FLAT、谷 VALLEY** 三类日周期业务区间，
系统按对象（井场）时区把试验窗口内的观测归入唯一区间，逐观测生成计算明细，并按观测时刻选用
当时生效的压力阈值版本。

### 版本化规则模型（规则启用后不能原地修改）

- `t_tou_rule_chain` 规则链（一个对象唯一一条，`(tenant_id,target_type,target_id)` 唯一）
  → `t_tou_rule_set` 规则版本（链上 version_no 递增，携带生效窗口、压力阈值、冻结快照）
  → `t_tou_interval` 峰平谷区间（一天 1440 分钟圆环上的左闭右开区间，支持跨午夜）。
- 版本生命周期 **DRAFT → ENABLED → SUPERSEDED**：只有草稿可增删改区间；
  **启用即冻结**——区间行与 `frozen_snapshot` 永不再改，原地修改/删除/重复启用一律 `FROZEN_RULE` 拒绝；
  业务变更只能基于历史版本 `clone` 出新草稿（区间复制后调整），再启用为下一版本。
- 新版本启用时，上一版本生效窗口在新版本起点处左闭右开闭合 `[from, newFrom)` 并置 SUPERSEDED；
  历史计算明细永久保留 `rule_set_id/version_no` 与当时系数/阈值，**历史结果读取当时快照**，
  规则后续迭代不影响历史结果（详情接口只读取结果头/明细落库行，不回查现行规则表）。
- **规则区间重叠必须拒绝**：启用校验（草稿维护时也即时校验）在 1440 分钟圆环上做 BitSet 交集判定，
  端点相接（如 `[..,06:00)+[06:00,..)`）合法，分钟相交即 `INTERVAL_OVERLAP` 拒绝；
  同时强制完整覆盖 1440 分钟（`INTERVAL_NOT_COVERED`）且峰/平/谷三类齐备（`SEGMENT_INCOMPLETE`）。

### REST 接口（均需 HTTP Basic；写请求带 X-Request-No / X-Operator-Id / X-Biz-Timezone 头）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/tou/chains` | 规则链建档（targetType=MONITOR_POINT/WELL_GROUP + targetId） |
| GET  | `/api/tou/chains`、`/api/tou/chains/{id}` | 规则链列表（只读账号按井组授权收敛，且拒绝写规则）/详情 |
| POST | `/api/tou/chains/{id}/versions` | 新建草稿版本（生效起点、压力阈值、初始峰平谷区间） |
| GET  | `/api/tou/chains/{id}/versions`、`/api/tou/versions/{setId}` | 版本链 / 版本详情（含区间） |
| POST | `/api/tou/versions/{setId}/clone` | 基于历史版本开新草稿（不能原地修改，只能迭代新版本） |
| POST/PUT/DELETE | `/api/tou/versions/{setId}/intervals/{intervalCode}` | 草稿区间新增/修改/删除（仅 DRAFT，重叠即时拒绝） |
| POST | `/api/tou/versions/{setId}/enable` | 草稿启用冻结（重叠/缺口/缺峰平谷一律拒绝） |
| DELETE | `/api/tou/versions/{setId}` | 删除草稿（已启用版本永久保留，拒绝删除） |
| POST | `/api/tou/calculations` | 执行试验窗口规则计算（body：targetType/targetId/windowStart/windowEnd） |
| GET  | `/api/tou/calculations`、`/api/tou/calculations/{id}` | 结果列表 / 结果详情（头+逐观测明细+峰平谷分段合计） |

井组建档 `POST /api/well-groups` 新增可选 `timezone`（井场时区，缺省 Asia/Shanghai）。

### 困难级约束实现

- **业务时区跨日 + 左闭右开**：试验窗口 `[windowStart, windowEnd)` 按井场时区（`t_well_group.timezone`，
  监测点沿试验段→井组解析）取数，**窗口终点时刻的观测不计入**；日区间为 `[startMinute,endMinute)`，
  `end<=start` 表示跨午夜（如谷段 22:00-次日 06:00）。凌晨 00:30 的观测归入**前一日 22:00 启动的
  跨午夜区间实例**（明细落 `day_offset=-1`、实际实例 `[segment_start,segment_end)` 与归属业务日期），
  保证跨午夜班次**不重复计量**。
- **至少 4 个版本化规则**：测试在一条链上启用 v1..v4，阈值 1.2→1.4→1.6→1.8 MPa，
  v4 同时改变区间形状（谷段 23:00-07:00），验证同一跨午夜观测在 v3/v4 下归入不同实例边界。
- **压力阈值按生效时间选版本**：逐条观测按 `observed_at` 选取当时 ENABLED/SUPERSEDED 版本
  （生效窗口 `effective_from <= t < effective_to`），明细保留版本号、阈值快照与超阈判定
  （`pressure_mpa > threshold`，严格大于）。
- **BigDecimal 累计、最终统一舍入**：`回灌量 × 时段系数` 全程 BigDecimal 精确值（明细 DECIMAL(20,9)，
  不做行内舍入），仅结果头合计最后一步 `setScale(3, HALF_UP)`；快照另存舍入前精确合计
  `exactTotalBeforeRounding`。专门用例验证 3×(1×0.333333)=0.999999 最终舍入为 1.000（逐行舍入会得 0.999）。
- **不能因并发重算产生两份结果**：结果头对 `(tenant_id,target_type,target_id,window_start,window_end)`
  建唯一索引；计算在独立事务内先对规则链行 `FOR UPDATE` 加锁并复查，同窗口重放幂等返回既有结果；
  并发撞唯一索引时事务回滚、外壳捕获后返回赢家结果。5 路并发重算测试：结果头 1 份、
  每观测 1 条明细、`CALCULATE` 审计仅 1 条。
- **窗口重叠拒绝**：已算窗口与新窗口左闭右开相交（`a < end && start < b`）即 `WINDOW_OVERLAP` 拒绝；
  端点邻接（一窗口终点=另一窗口起点）合法，共享端点时刻的观测只在含该时刻的窗口计量一次。

### 测试

`mvn test`：**60 个测试全绿**（在原 45 个基础上新增 15 个）：

- `tou/DailyTimelineTest`（6）：圆环区间重叠/全覆盖、左闭右开端点归类、跨午夜实例 dayOffset=-1；
- `TouRuleCalcIntegrationTest`（6）：≥4 版本规则、时区跨日、左闭右开边界、阈值按生效时间选版本、
  历史快照不变、BigDecimal 最终统一舍入、重叠拒绝/冻结不可改、窗口幂等/邻接不重复、5 路并发唯一；
- `TouRestApiTest`（3）：Basic 认证强制、规则建档-重叠拒绝-启用-冻结拒绝-计算-幂等-历史读取全链路、只读账号 t1view 按井组授权收敛（未授权不可见、授权后可读，且拒绝写操作）。
