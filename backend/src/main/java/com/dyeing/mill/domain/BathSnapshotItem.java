package com.dyeing.mill.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 开缸执行单配方快照行：下达瞬间从配方明细原样冻结。
 * 快照表只增不改不软删 —— 已下达单据必须可追溯，来源配方事后编辑不得影响它。
 */
@Data
@Entity
@Table(name = "bath_snapshot_item")
public class BathSnapshotItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bath_order_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BathOrder bathOrder;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** DYE 染料 / AUX 助剂 */
    @Column(name = "item_type", nullable = false, length = 10)
    private String itemType;

    @Column(name = "dye_name", nullable = false, length = 64)
    private String dyeName;

    @Column(name = "owf_percent", nullable = false, precision = 8, scale = 3)
    private BigDecimal owfPercent;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "color_hex", length = 9)
    private String colorHex;
}
