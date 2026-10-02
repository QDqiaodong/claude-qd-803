# 印染厂 · 染缸与染色配方管理

以「染缸 + 染色配方 + 工艺曲线」为核心的印染车间管理系统。

## 启动

```bash
./start.sh          # 秒起（镜像已存在）
./start.sh --build  # 首次或改代码后重建
./stop.sh / ./resume.sh / ./clean.sh / ./nuke.sh
```

| 服务 | 端口 |
| --- | --- |
| 前端（Vue 3 + Element Plus + Vite / nginx） | 8283 |
| 后端（Spring Boot 3.3.0 + Java 17） | 8383 |
| MySQL 8（库名 `dyeing_mill`） | 3583 |
| Redis 7 | 6583 |

表结构与种子数据由 `mysql/schema.sql` 在构建期导入（`ddl-auto: none`，应用不建表）。

## 六个模块

1. **染缸台账** — 缸号、机台类型（溢喷/气流/卷染）、容量、浴比、六态流转；缸位温度环阵列 + 控温面板。
2. **染色配方** — 色号、客户、布种、LAB；染料阶梯表行内编辑 owf，合计必须 100%；助剂单列，自动核算元/kg 成本。
3. **工艺曲线** — 升温/保温/降温节点序列，SVG 上拖动节点改温度与时长，实时算各段 ℃/min。
4. **布车流转** — 三列看板（待排产/在染/已完成），HTML5 原生拖拽，落入「在染」时选染缸与时段。
5. **染料与助剂库存** — 库存 / 安全库存 / 批次 / 效期，领用登记与库存预警。
6. **开缸执行单** — 把已排缸布车、已发布配方、匹配工艺曲线串成一次可确认、可下达的开缸依据。

## 开缸执行单（bath_order）

业务链路：**创建前核对 → 待确认（引用最新资料）→ 下达（冻结完整工艺快照并推进染缸）**。

- **创建必须核对**：布种与色号匹配、配方已发布、曲线与配方/布种/色号匹配、折算布重 ≤ 染缸容量、
  曲线关键温度 ≤ 机台类型温度上限。任一项不满足，页面逐项列出具体差值（含超出多少 kg/℃），不落单。
- **待确认失效**：配方 `revision`、曲线 `nodeRevision`、布车排缸令牌 `assign_token` 任一变化，
  引用旧资料的待确认单由事务提交后钩子在独立事务中标 INVALID 并写明原因，禁止下达；
  行锁内复核是第二道防线。
- **下达即冻结**：下达时复制配方明细与曲线节点为不可变快照（`bath_snapshot_item` /
  `bath_snapshot_node`），之后再编辑来源不改写已执行内容。
- **机台联动**：同一事务内把曲线最高节点温度（目标温度）与最高温保温时长写入染缸，
  布车与染缸都回写执行单号可追溯；Redis 控温参数在事务提交成功后才推送。
- **并发唯一**：布车行悲观锁 + 生成列唯一键（`issued_cart_key`）双保险，
  同一布车同一排缸批次两人同时下达只有一张成功，落败方刷新后看到已生效那张。
- **失败全回滚**：染缸更新前、Redis 推送前均有故障注入点（`failStep=vat|redis`），
  任一步失败，执行单、布车、染缸保持原状，不会出现半单。

## 后端技术标识

- Hibernate 6 `@SQLRestriction` 做全局数据过滤：软删除（`deleted = 0`）、报废布车（`status <> 'SCRAPPED'`）、布种档案（`enabled = 1`）。
- `@OneToMany + @OrderBy` 维护有序明细：配方明细按 `seqNo`、曲线节点按 `seqNo`、领用记录按 `issueTime DESC`、开缸快照按 `seqNo`。
- 手写 JPQL：按色号统计缸次、按客户汇总米数、染料库存预警、染缸时段冲突、机台类型分布、
  开缸下达的 `PESSIMISTIC_WRITE` 行锁。
- 业务规则放 service 并抛 `BizException`：染缸时段冲突、owf 合计 100%（报错给差值）、领用不得超过库存、
  升温速率超布种上限、开缸创建/下达核对。
- 来源变更失效用 `TransactionSynchronization#afterCommit` + `REQUIRES_NEW` 独立事务，
  不污染来源保存事务，也不依赖事件监听在特定数据库下的可见性。

## 验收测试

```bash
cd backend
mvn test -Pacceptance     # H2 内存库，9 个场景：
                           # 创建前差异核对 / 正常下达 / 配方·曲线·排缸变化失效 /
                           # 快照不可变 / 并发重复下达 / vat·redis 故障注入回滚 / 防重复待确认
```

页面右下角「验收」悬浮按钮内置并发双请求与故障注入入口，便于人工验收。

## 前端导航

九宫格启动台：默认全屏无侧栏，右上角菜单按钮弹出导航格（6 个模块 + 快捷入口），
选定后模块名显示在顶部标题条，内容区全屏铺开。主色 `#4a148c`。

## 主要接口

```
GET    /api/vats                  染缸列表
PATCH  /api/vats/{id}/control     控温（目标温度 + 保温时长）
POST   /api/vats/{id}/tick        推进一拍
GET    /api/recipes               配方（含明细）
PUT    /api/recipes/{id}          保存配方（校验 owf 合计 100%，revision+1）
GET    /api/curves                工艺曲线（含节点）
PUT    /api/curves/{id}/nodes     保存节点（校验升温速率，nodeRevision+1）
GET    /api/carts/board           布车看板三列
POST   /api/carts/{id}/assign     排缸（校验染缸时段冲突，换新 assign_token）
GET    /api/inventory/alerts      库存预警
POST   /api/inventory/issue       领用（校验库存与效期）

GET    /api/bath-orders                       开缸执行单列表（含来源核对结论）
GET    /api/bath-orders/{id}                 详情（待确认=最新资料，已下达=冻结快照）
GET    /api/bath-orders/preflight            创建前核对（布种色号/容量/温度差异）
POST   /api/bath-orders                      创建待确认单（核对不过只报差异）
POST   /api/bath-orders/{id}/issue           下达（冻结快照+推进染缸；failStep 注入故障）
POST   /api/bath-orders/{id}/cancel          作废待确认单
```
