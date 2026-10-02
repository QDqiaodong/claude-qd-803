package com.dyeing.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 开缸执行单展示对象。
 *
 * 待确认阶段：snapshot* 为空，items / nodes 始终来自最新来源资料，并带 sourceChecks 说明来源是否仍匹配；
 * 已下达阶段：items / nodes 来自下达瞬间冻结的快照，sourceChanged 恒为 false（快照不可变）。
 */
@Data
public class BathOrderVO {

    private Long id;
    private String orderNo;
    private String status;
    private String invalidReason;

    // ---- 布车与染缸 ----
    private Long cartId;
    private String cartCode;
    private Long vatId;
    private String vatCode;
    private String vatMachineType;
    private BigDecimal vatCapacityKg;
    private String fabricType;
    private String colorCode;
    private String colorName;
    private BigDecimal meters;
    private BigDecimal fabricWeightKg;
    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;

    // ---- 来源引用与版本 ----
    private Long recipeId;
    private String recipeCode;
    private Integer recipeVersionNo;
    private Integer recipeRevision;
    private String recipeStatus;
    private Long curveId;
    private String curveCode;
    private Integer curveNodeRevision;

    // ---- 工艺结论 ----
    private BigDecimal targetTemp;
    private Integer holdMinutes;
    private Integer totalMinutes;
    private BigDecimal machineMaxTemp;

    // ---- 明细（待确认=最新来源；已下达=冻结快照） ----
    private List<ItemLine> items;
    private List<NodeLine> nodes;
    private boolean snapshot;

    /** 待确认阶段逐项来源核对结果；全部 pass 才允许下达 */
    private List<SourceCheck> sourceChecks;
    /** 综合是否仍可下达（status=PENDING 且所有核对项 pass） */
    private boolean issueable;

    private String createdBy;
    private LocalDateTime createTime;
    private String issuedBy;
    private LocalDateTime issuedTime;

    @Data
    public static class ItemLine {
        private Integer seqNo;
        private String itemType;
        private String dyeName;
        private BigDecimal owfPercent;
        private BigDecimal unitPrice;
        private String colorHex;
    }

    @Data
    public static class NodeLine {
        private Integer seqNo;
        private String stage;
        private BigDecimal targetTemp;
        private Integer durationMin;
        private BigDecimal rampRate;
        private String memo;
    }

    /** 一项来源核对：通过或不通过都要说清具体差异 */
    @Data
    public static class SourceCheck {
        private String key;
        private String label;
        private boolean pass;
        private String detail;

        public SourceCheck() {
        }

        public SourceCheck(String key, String label, boolean pass, String detail) {
            this.key = key;
            this.label = label;
            this.pass = pass;
            this.detail = detail;
        }
    }
}
