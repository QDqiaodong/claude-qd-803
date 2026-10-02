package com.dyeing.mill.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 开缸执行单创建请求：只需三件来源 id（布车必须已排缸，配方与曲线由后端核对匹配）。
 */
@Data
public class BathOrderCreateRequest {

    @NotNull(message = "布车不能为空")
    private Long cartId;

    @NotNull(message = "染色配方不能为空")
    private Long recipeId;

    @NotNull(message = "工艺曲线不能为空")
    private Long curveId;

    private String operator;
}
