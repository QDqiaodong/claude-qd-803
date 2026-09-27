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

## 五个模块

1. **染缸台账** — 缸号、机台类型（溢喷/气流/卷染）、容量、浴比、六态流转；缸位温度环阵列 + 控温面板。
2. **染色配方** — 色号、客户、布种、LAB；染料阶梯表行内编辑 owf，合计必须 100%；助剂单列，自动核算元/kg 成本。
3. **工艺曲线** — 升温/保温/降温节点序列，SVG 上拖动节点改温度与时长，实时算各段 ℃/min。
4. **布车流转** — 三列看板（待排产/在染/已完成），HTML5 原生拖拽，落入「在染」时选染缸与时段。
5. **染料与助剂库存** — 库存 / 安全库存 / 批次 / 效期，领用登记与库存预警。

## 后端技术标识

- Hibernate 6 `@SQLRestriction` 做全局数据过滤：软删除（`deleted = 0`）、报废布车（`status <> 'SCRAPPED'`）、布种档案（`enabled = 1`）。
- `@OneToMany + @OrderBy` 维护有序明细：配方明细按 `seqNo`、曲线节点按 `seqNo`、领用记录按 `issueTime DESC`。
- 手写 JPQL：按色号统计缸次、按客户汇总米数、染料库存预警、染缸时段冲突、机台类型分布等。
- 业务规则放 service 并抛 `BizException`：染缸时段冲突、owf 合计 100%（报错给差值）、领用不得超过库存、升温速率超布种上限。

## 前端导航

九宫格启动台：默认全屏无侧栏，右上角菜单按钮弹出 9 格导航（5 个模块 + 4 个带预设参数的快捷入口），
选定后模块名显示在顶部标题条，内容区全屏铺开。主色 `#4a148c`。

## 主要接口

```
GET    /api/vats                  染缸列表
PATCH  /api/vats/{id}/control     控温（目标温度 + 保温时长）
POST   /api/vats/{id}/tick        推进一拍
GET    /api/recipes               配方（含明细）
PUT    /api/recipes/{id}          保存配方（校验 owf 合计 100%）
GET    /api/curves                工艺曲线（含节点）
PUT    /api/curves/{id}/nodes     保存节点（校验升温速率）
GET    /api/carts/board           布车看板三列
POST   /api/carts/{id}/assign     排缸（校验染缸时段冲突）
GET    /api/inventory/alerts      库存预警
POST   /api/inventory/issue       领用（校验库存与效期）
```
