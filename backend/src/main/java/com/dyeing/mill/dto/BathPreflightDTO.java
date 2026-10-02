package com.dyeing.mill.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * 创建前核对（只算不存）：页面选完布车/配方/曲线后调一次，
 * 把布种色号匹配、染缸容量、机台温度上限的具体差异一次性列给用户看。
 * 任一项 pass=false 都不允许创建——不能生成一张看似可用的执行单。
 */
@Data
public class BathPreflightDTO {

    private Long cartId;
    private String cartCode;
    private String fabricType;
    private String colorCode;
    private String colorName;
    private BigDecimal meters;
    private BigDecimal fabricWeightKg;
    private String vatCode;
    private String vatMachineType;
    private BigDecimal vatCapacityKg;
    private String recipeCode;
    private Integer recipeVersionNo;
    private String curveCode;
    private BigDecimal targetTemp;
    private Integer holdMinutes;
    private Integer totalMinutes;
    private BigDecimal machineMaxTemp;

    private List<BathOrderVO.SourceCheck> checks;
    private boolean allPass;
}
