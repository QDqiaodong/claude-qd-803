package com.dyeing.mill.dto;

import lombok.Data;

/**
 * 开缸执行单下达请求。
 *
 * failStep 仅用于验收「中途异常整体回滚」：
 *   vat    —— 在更新染缸参数之前主动失败（单据与布车都应保持原状）
 *   redis  —— 在向染缸推送目标温度到 Redis 之前主动失败（DB 全回滚，染缸不升温）
 * 正常下达不传该字段。
 */
@Data
public class BathIssueRequest {

    private String operator;

    private String failStep;
}
