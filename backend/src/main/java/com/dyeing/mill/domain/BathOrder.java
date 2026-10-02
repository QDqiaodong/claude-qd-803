package com.dyeing.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 开缸执行单：把「已排入染缸的布车 + 已发布染色配方 + 匹配工艺曲线」串成一次可确认、可下达的开缸依据。
 *
 * 生命周期：
 *   PENDING 待确认 —— 只引用来源 id 与版本号，页面始终展示最新资料；
 *                     来源（配方内容/曲线节点/布车排缸结果）一旦改变即标 INVALID，禁止下达。
 *   ISSUED  已下达 —— 冻结完整工艺快照（bath_snapshot_item / bath_snapshot_node），
 *                     后续来源再编辑也不改写已执行内容。
 *   INVALID 已失效 —— 仅存在于待确认阶段，记录具体失效原因。
 *
 * 并发：pending_cart_key / issued_cart_key 是数据库生成列上的唯一键，
 *       同一布车同一排缸批次并发下达时，只有一张能插入成功。
 */
@Data
@Entity
@Table(name = "bath_order")
public class BathOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 执行单号 BO-yyMMdd-序号 */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    // ---- 布车与染缸（排缸结果快照，防止排缸变动后单据指向不明） ----

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "cart_code", nullable = false, length = 32)
    private String cartCode;

    @Column(name = "vat_id", nullable = false)
    private Long vatId;

    @Column(name = "vat_code", nullable = false, length = 32)
    private String vatCode;

    @Column(name = "vat_machine_type", nullable = false, length = 20)
    private String vatMachineType;

    @Column(name = "vat_capacity_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal vatCapacityKg;

    @Column(name = "fabric_type", nullable = false, length = 40)
    private String fabricType;

    @Column(name = "color_code", nullable = false, length = 32)
    private String colorCode;

    @Column(name = "color_name", nullable = false, length = 64)
    private String colorName;

    @Column(name = "meters", nullable = false, precision = 10, scale = 1)
    private BigDecimal meters;

    /** 折算布重 kg（0.18 kg/m），容量核对用 */
    @Column(name = "fabric_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal fabricWeightKg;

    // ---- 来源引用（id + 引用时版本号） ----

    @Column(name = "recipe_id", nullable = false)
    private Long recipeId;

    @Column(name = "recipe_code", nullable = false, length = 32)
    private String recipeCode;

    @Column(name = "recipe_version_no", nullable = false)
    private Integer recipeVersionNo;

    @Column(name = "recipe_revision", nullable = false)
    private Integer recipeRevision;

    @Column(name = "curve_id", nullable = false)
    private Long curveId;

    @Column(name = "curve_code", nullable = false, length = 32)
    private String curveCode;

    @Column(name = "curve_node_revision", nullable = false)
    private Integer curveNodeRevision;

    @Column(name = "assign_token", length = 64)
    private String assignToken;

    // ---- 关键工艺结论（创建时核算，页面展示） ----

    /** 关键温度：曲线最高节点温度，下达时推给染缸 */
    @Column(name = "target_temp", nullable = false, precision = 5, scale = 1)
    private BigDecimal targetTemp;

    /** 保温时长：最高温保温节点合计，下达时推给染缸 */
    @Column(name = "hold_minutes", nullable = false)
    private Integer holdMinutes;

    /** 预计总时长：曲线各节点时长合计 */
    @Column(name = "total_minutes", nullable = false)
    private Integer totalMinutes;

    /** PENDING 待确认 / ISSUED 已下达 / INVALID 已失效 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 失效具体原因（哪一项来源、变成了什么） */
    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    @Column(name = "issued_by", length = 32)
    private String issuedBy;

    @Column(name = "issued_time")
    private LocalDateTime issuedTime;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 下达后冻结的配方快照；待确认阶段为空集合（页面直接读最新配方） */
    @OneToMany(mappedBy = "bathOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("seqNo ASC")
    private List<BathSnapshotItem> snapshotItems = new ArrayList<>();

    /** 下达后冻结的曲线节点快照；待确认阶段为空集合（页面直接读最新曲线） */
    @OneToMany(mappedBy = "bathOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("seqNo ASC")
    private List<BathSnapshotNode> snapshotNodes = new ArrayList<>();
}
