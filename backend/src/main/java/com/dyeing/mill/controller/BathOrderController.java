package com.dyeing.mill.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dyeing.mill.common.ApiResponse;
import com.dyeing.mill.domain.BathOrder;
import com.dyeing.mill.dto.BathIssueRequest;
import com.dyeing.mill.dto.BathOrderCreateRequest;
import com.dyeing.mill.dto.BathOrderVO;
import com.dyeing.mill.dto.BathPreflightDTO;
import com.dyeing.mill.service.BathOrderService;

/**
 * 开缸执行单：创建前核对、待确认/失效/已下达全生命周期、下达推机台。
 */
@RestController
@RequestMapping("/api/bath-orders")
public class BathOrderController {

    private final BathOrderService bathOrderService;

    public BathOrderController(BathOrderService bathOrderService) {
        this.bathOrderService = bathOrderService;
    }

    /** 列表（含每张单的最新来源核对结论） */
    @GetMapping
    public ApiResponse<List<BathOrderVO>> list() {
        return ApiResponse.success(bathOrderService.listVO());
    }

    /** 详情（待确认单展示最新资料；已下单展示冻结快照） */
    @GetMapping("/{id}")
    public ApiResponse<BathOrderVO> detail(@PathVariable Long id) {
        return ApiResponse.success(bathOrderService.detail(id));
    }

    /** 创建前核对：只算不存，把布种色号/容量/温度上限差异一次性返回 */
    @GetMapping("/preflight")
    public ApiResponse<BathPreflightDTO> preflight(@RequestParam Long cartId,
                                                   @RequestParam Long recipeId,
                                                   @RequestParam Long curveId) {
        return ApiResponse.success(bathOrderService.preflight(cartId, recipeId, curveId));
    }

    /** 创建待确认执行单（核对不通过只报差异，不落单） */
    @PostMapping
    public ApiResponse<BathOrder> create(@Valid @RequestBody BathOrderCreateRequest request) {
        return ApiResponse.success("开缸执行单已创建，待确认", bathOrderService.create(request));
    }

    /** 下达：冻结快照 + 推进染缸目标温度与保温时长，整笔事务同生共死 */
    @PostMapping("/{id}/issue")
    public ApiResponse<BathOrder> issue(@PathVariable Long id, @RequestBody(required = false) BathIssueRequest request) {
        return ApiResponse.success("开缸执行单已下达，控温参数已推进染缸",
                bathOrderService.issue(id, request == null ? new BathIssueRequest() : request));
    }

    /** 作废待确认单（已下达单不可作废） */
    @PostMapping("/{id}/cancel")
    public ApiResponse<BathOrder> cancel(@PathVariable Long id) {
        return ApiResponse.success("执行单已作废", bathOrderService.cancel(id));
    }

    /** 失效单数量（导航角标用） */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        List<BathOrderVO> all = bathOrderService.listVO();
        long pending = all.stream().filter(o -> "PENDING".equals(o.getStatus())).count();
        long invalid = all.stream().filter(o -> "INVALID".equals(o.getStatus())).count();
        long issued = all.stream().filter(o -> "ISSUED".equals(o.getStatus())).count();
        return ApiResponse.success(Map.of("total", all.size(), "pending", pending,
                "invalid", invalid, "issued", issued));
    }
}
