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
 * 开缸执行单曲线节点快照行：下达瞬间从工艺曲线节点原样冻结。
 */
@Data
@Entity
@Table(name = "bath_snapshot_node")
public class BathSnapshotNode {

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

    /** RAMP 升温 / HOLD 保温 / COOL 降温 */
    @Column(name = "stage", nullable = false, length = 10)
    private String stage;

    @Column(name = "target_temp", nullable = false, precision = 5, scale = 1)
    private BigDecimal targetTemp;

    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    @Column(name = "ramp_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rampRate;

    @Column(name = "memo", length = 100)
    private String memo;
}
