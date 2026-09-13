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

## 运行

1. `mvn -q -DskipTests compile`
2. `mvn spring-boot:run`（自动执行 `src/main/resources/schema.sql`，H2 文件在 `data/evops`）
3. `mvn test`：13 个测试全绿（9 个闭环集成测试 + 4 个 REST 冒烟）。

集成测试（`src/test/java/com/evops/geothermal/`）覆盖困难级约束：

- **≥2 个业务对象**：井组、试验段、监测点、班次、批次、读数、审计（7 个）；
- **3 个业务日期**：2026-09-10 / 09-11 / 09-12；
- **5 路并发写入**：同批次乐观锁竞争（只 1 路成功，失败事务读数回滚）与同请求号并发（只 1 路落账、审计仅 1 条）；
- 同事务 + 审计四要素、业务键唯一、原子回滚、删除保护均有断言。

题包根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

题面和质检卷在上一级 `packets/` 目录；模型工作区不得复制 `answers.md`、验收测试或参考修正。
