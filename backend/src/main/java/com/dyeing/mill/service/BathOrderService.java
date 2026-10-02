package com.dyeing.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.BathOrder;
import com.dyeing.mill.domain.BathSnapshotItem;
import com.dyeing.mill.domain.BathSnapshotNode;
import com.dyeing.mill.domain.ClothCart;
import com.dyeing.mill.domain.CurveNode;
import com.dyeing.mill.domain.DyeVat;
import com.dyeing.mill.domain.ProcessCurve;
import com.dyeing.mill.domain.Recipe;
import com.dyeing.mill.domain.RecipeItem;
import com.dyeing.mill.dto.BathIssueRequest;
import com.dyeing.mill.dto.BathOrderCreateRequest;
import com.dyeing.mill.dto.BathOrderVO;
import com.dyeing.mill.dto.BathPreflightDTO;
import com.dyeing.mill.repository.BathOrderRepository;
import com.dyeing.mill.repository.ClothCartRepository;
import com.dyeing.mill.repository.DyeVatRepository;
import com.dyeing.mill.repository.ProcessCurveRepository;
import com.dyeing.mill.repository.RecipeRepository;

/**
 * 开缸执行单业务：
 *  1. 创建前逐项核对（布种/色号匹配、染缸容量、机台温度上限），不满足只报具体差异，不落单；
 *  2. 待确认单始终引用最新资料并带来源核对，任一来源变化即由事件监听方标失效、禁止下达；
 *  3. 下达在一个事务内完成「锁布车 → 锁染缸 → 冻结快照 → 推进染缸参数 → 回写追溯号 → Redis 推温」，
 *     任一步失败全部回滚（Redis 写在事务提交后执行，回滚时不推送）；
 *  4. 并发：布车行悲观锁 + issued_cart_key 数据库唯一键双保险，同布车同排缸批次只能下达一张。
 */
@Service
public class BathOrderService {

    /** 米 → kg 折算系数（与排缸容量校验口径一致） */
    static final BigDecimal KG_PER_METER = new BigDecimal("0.18");

    /** 机台类型 → 允许的最高温度（℃），与 VatService 口径一致 */
    private static final Map<String, BigDecimal> MACHINE_MAX_TEMP = Map.of(
            "OVERFLOW", BigDecimal.valueOf(135),
            "AIRFLOW", BigDecimal.valueOf(140),
            "JIG", BigDecimal.valueOf(98));

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final BathOrderRepository bathOrderRepository;
    private final ClothCartRepository cartRepository;
    private final DyeVatRepository vatRepository;
    private final RecipeRepository recipeRepository;
    private final ProcessCurveRepository curveRepository;
    private final StringRedisTemplate redisTemplate;
    private final PlatformTransactionManager transactionManager;

    public BathOrderService(BathOrderRepository bathOrderRepository,
                            ClothCartRepository cartRepository,
                            DyeVatRepository vatRepository,
                            RecipeRepository recipeRepository,
                            ProcessCurveRepository curveRepository,
                            StringRedisTemplate redisTemplate,
                            PlatformTransactionManager transactionManager) {
        this.bathOrderRepository = bathOrderRepository;
        this.cartRepository = cartRepository;
        this.vatRepository = vatRepository;
        this.recipeRepository = recipeRepository;
        this.curveRepository = curveRepository;
        this.redisTemplate = redisTemplate;
        this.transactionManager = transactionManager;
    }

    /**
     * 在一个全新的、独立提交的物理事务里执行失效动作。
     * 必须 REQUIRES_NEW：afterCommit 回调里 Spring 仍保留外层事务的同步状态（空壳），
     * 默认 REQUIRED 会错误地挂上去导致 JPA 报 "no transaction is in progress"。
     */
    public void runInNewTransaction(Runnable action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> action.run());
    }

    // ============================================================
    // 创建前核对（只算不存）
    // ============================================================

    /**
     * 预核对：把三件来源凑齐，逐项算出差值。allPass=false 时前端只展示差异、不允许创建。
     */
    public BathPreflightDTO preflight(Long cartId, Long recipeId, Long curveId) {
        ClothCart cart = getCart(cartId);
        Recipe recipe = getRecipe(recipeId);
        ProcessCurve curve = getCurve(curveId);
        DyeVat vat = cart.getVatId() == null ? null
                : vatRepository.findById(cart.getVatId()).orElse(null);

        BathPreflightDTO dto = new BathPreflightDTO();
        dto.setCartId(cart.getId());
        dto.setCartCode(cart.getCartCode());
        dto.setFabricType(cart.getFabricType());
        dto.setColorCode(cart.getColorCode());
        dto.setColorName(cart.getColorName());
        dto.setMeters(cart.getMeters());
        BigDecimal weight = weightOf(cart);
        dto.setFabricWeightKg(weight);
        if (vat != null) {
            dto.setVatCode(vat.getVatCode());
            dto.setVatMachineType(vat.getMachineType());
            dto.setVatCapacityKg(vat.getCapacityKg());
        }
        dto.setRecipeCode(recipe.getRecipeCode());
        dto.setRecipeVersionNo(recipe.getVersionNo());
        dto.setCurveCode(curve.getCurveCode());

        CurveSummary summary = CurveSummary.of(curve);
        dto.setTargetTemp(summary.targetTemp);
        dto.setHoldMinutes(summary.holdMinutes);
        dto.setTotalMinutes(summary.totalMinutes);
        BigDecimal machineMax = vat == null ? null : MACHINE_MAX_TEMP.get(vat.getMachineType());
        dto.setMachineMaxTemp(machineMax);

        List<BathOrderVO.SourceCheck> checks = buildChecks(cart, vat, recipe, curve, summary);
        dto.setChecks(checks);
        dto.setAllPass(checks.stream().allMatch(BathOrderVO.SourceCheck::isPass));
        return dto;
    }

    /**
     * 创建待确认执行单。所有核对项必须通过——任何一项不满足都只报差异，不生成单据。
     */
    @Transactional
    public BathOrder create(BathOrderCreateRequest request) {
        BathPreflightDTO pre = preflight(request.getCartId(), request.getRecipeId(), request.getCurveId());
        List<BathOrderVO.SourceCheck> failed = pre.getChecks().stream()
                .filter(c -> !c.isPass())
                .toList();
        if (!failed.isEmpty()) {
            // 把每一项差异明明白白列出来，而不是一张看似可用的单子
            StringBuilder sb = new StringBuilder("无法创建开缸执行单，请先处理以下差异：");
            for (BathOrderVO.SourceCheck check : failed) {
                sb.append("\n· ").append(check.getLabel()).append("：").append(check.getDetail());
            }
            throw new BizException(sb.toString());
        }

        ClothCart cart = getCart(request.getCartId());
        // 同一布车同一排缸批次已有待确认单：不重复造单，引导去处理旧单
        for (BathOrder old : bathOrderRepository.findPendingByCart(cart.getId())) {
            if (Objects.equals(old.getAssignToken(), cart.getAssignToken())) {
                throw new BizException("布车 " + cart.getCartCode() + " 已存在待确认开缸执行单 "
                        + old.getOrderNo() + "，请先下达或作废该单，不能重复创建");
            }
        }

        Recipe recipe = getRecipe(request.getRecipeId());
        ProcessCurve curve = getCurve(request.getCurveId());
        DyeVat vat = vatRepository.findById(cart.getVatId()).orElseThrow();
        CurveSummary summary = CurveSummary.of(curve);

        BathOrder order = new BathOrder();
        order.setOrderNo(nextOrderNo());
        order.setStatus("PENDING");
        order.setCartId(cart.getId());
        order.setCartCode(cart.getCartCode());
        order.setVatId(vat.getId());
        order.setVatCode(vat.getVatCode());
        order.setVatMachineType(vat.getMachineType());
        order.setVatCapacityKg(vat.getCapacityKg());
        order.setFabricType(cart.getFabricType());
        order.setColorCode(cart.getColorCode());
        order.setColorName(cart.getColorName());
        order.setMeters(cart.getMeters());
        order.setFabricWeightKg(weightOf(cart));
        order.setRecipeId(recipe.getId());
        order.setRecipeCode(recipe.getRecipeCode());
        order.setRecipeVersionNo(recipe.getVersionNo());
        order.setRecipeRevision(recipe.getRevision() == null ? 1 : recipe.getRevision());
        order.setCurveId(curve.getId());
        order.setCurveCode(curve.getCurveCode());
        order.setCurveNodeRevision(curve.getNodeRevision() == null ? 1 : curve.getNodeRevision());
        order.setAssignToken(cart.getAssignToken());
        order.setTargetTemp(summary.targetTemp);
        order.setHoldMinutes(summary.holdMinutes);
        order.setTotalMinutes(summary.totalMinutes);
        order.setCreatedBy(request.getOperator());
        order.setCreateTime(LocalDateTime.now());

        try {
            return bathOrderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("布车 " + cart.getCartCode() + " 在本排缸批次已有待确认执行单，请刷新页面查看");
        }
    }

    // ============================================================
    // 下达：锁 → 校验 → 快照 → 推机台，一个事务同生共死
    // ============================================================

    /**
     * 下达待确认单。
     * 失败点（failStep）仅供验收中途异常：vat=更新染缸前失败、redis=推送 Redis 前失败。
     */
    @Transactional
    public BathOrder issue(Long id, BathIssueRequest request) {
        // ① 先锁执行单，再锁布车：同一布车的并发下达在布车行锁上排队
        BathOrder order = bathOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("开缸执行单不存在：id=" + id));
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("执行单 " + order.getOrderNo() + " 已下达（下达人："
                    + order.getIssuedBy() + "），请勿重复下达");
        }
        if ("INVALID".equals(order.getStatus())) {
            throw new BizException("执行单 " + order.getOrderNo() + " 已失效，禁止下达。原因："
                    + order.getInvalidReason());
        }

        ClothCart cart = cartRepository.findByIdForUpdate(order.getCartId())
                .orElseThrow(() -> new BizException("布车不存在或已报废，无法下达"));

        // ② 行锁内重新核对来源（防御性：即使失效事件漏处理，这里也拦住，绝不放过一张过期单）
        List<BathOrderVO.SourceCheck> checks = revalidate(order, cart);
        List<BathOrderVO.SourceCheck> failed = checks.stream()
                .filter(c -> !c.isPass())
                .toList();
        if (!failed.isEmpty()) {
            // 防御性兜底（正常情况下来源变化事件已把单据标为 INVALID，会在前面的状态检查被拦）：
            // 本事务只负责拒绝，不改写单据（随后整笔回滚）；刷新详情时会补标失效原因
            throw new BizException("执行单 " + order.getOrderNo()
                    + " 的来源资料已变化，不能下达：" + joinDetails(failed)
                    + "。请刷新页面，该单将显示为失效，需基于最新资料重新创建");
        }

        // ③ 同排缸批次已有别的已下达单 → 唯一键兜底外的业务提示
        List<BathOrder> issued = bathOrderRepository
                .findIssuedByCartAndToken(cart.getId(), cart.getAssignToken());
        if (!issued.isEmpty()) {
            BathOrder winner = issued.get(0);
            throw new BizException("布车 " + cart.getCartCode() + " 已有生效执行单 "
                    + winner.getOrderNo() + "，本单不能重复下达（刷新后可查看已生效的那张）");
        }

        DyeVat vat = vatRepository.findByIdForUpdate(order.getVatId())
                .orElseThrow(() -> new BizException("染缸不存在或已停用，无法下达"));

        // ---- 验收用故障注入①：染缸参数推进之前失败，单据/布车/染缸都必须保持原状 ----
        if ("vat".equals(request.getFailStep())) {
            throw new BizException("【故障注入】模拟染缸参数推进失败，整笔下达回滚");
        }

        Recipe recipe = getRecipe(order.getRecipeId());
        ProcessCurve curve = getCurve(order.getCurveId());

        // ④ 冻结完整工艺快照（此后配方/曲线再怎么改都与本单无关）
        freezeSnapshots(order, recipe, curve);

        // ⑤ 推进染缸到执行单对应状态：目标温度 + 保温时长 + 阶段
        BigDecimal target = order.getTargetTemp();
        int hold = order.getHoldMinutes();
        BigDecimal current = vat.getCurrentTemp() == null ? BigDecimal.valueOf(32) : vat.getCurrentTemp();
        vat.setTargetTemp(target);
        vat.setHoldMinutes(hold);
        if (target.compareTo(current) > 0) {
            vat.setStatus("HEATING");
            vat.setHoldRemainMinutes(hold);
        } else if (target.compareTo(current) < 0) {
            vat.setStatus("COOLING");
            vat.setHoldRemainMinutes(0);
        } else {
            vat.setStatus("HOLDING");
            vat.setHoldRemainMinutes(hold);
        }
        vat.setActiveOrderNo(order.getOrderNo());

        // ⑥ 布车与单据回写
        cart.setActiveOrderNo(order.getOrderNo());
        order.setStatus("ISSUED");
        order.setInvalidReason(null);
        order.setIssuedBy(request.getOperator());
        order.setIssuedTime(LocalDateTime.now());

        // ⑦ 落库：单据（含快照）+ 染缸 + 布车同一事务
        vatRepository.save(vat);
        cartRepository.save(cart);
        try {
            BathOrder saved = bathOrderRepository.saveAndFlush(order);

            // ---- 验收用故障注入②：Redis 推送之前失败，DB 全部回滚，染缸不升温、无半单 ----
            if ("redis".equals(request.getFailStep())) {
                throw new BizException("【故障注入】模拟控温参数推送失败，整笔下达回滚");
            }

            // ⑧ Redis 控温参数只在事务提交后推送；若事务回滚则绝不推送，避免缸已升温却无单可溯
            String vatCode = vat.getVatCode();
            String targetValue = target.toPlainString();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisTemplate.opsForValue().set("mill:vat:" + vatCode + ":target", targetValue);
                }
            });
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 并发极端情况：两个线程都过了业务检查，唯一键让后到者失败，整笔回滚
            throw new BizException("布车 " + cart.getCartCode()
                    + " 的开缸执行单刚被另一人下达成功，本单已取消（刷新后可见已生效那张）");
        }
    }

    // ============================================================
    // 查询：待确认展示最新资料，已下达展示冻结快照
    // ============================================================

    public List<BathOrder> list() {
        return bathOrderRepository.findAllOrderByIdDesc();
    }

    /**
     * 详情：待确认单发现来源已变时会在同一事务内补标失效；已下达单直接返回冻结快照。
     */
    @Transactional
    public BathOrderVO detail(Long id) {
        BathOrder order = bathOrderRepository.findById(id)
                .orElseThrow(() -> new BizException("开缸执行单不存在：id=" + id));
        ClothCart cart = cartRepository.findById(order.getCartId()).orElse(null);
        return toVO(order, cart, false);
    }

    /**
     * 列表：批量组装，不做写操作（正式失效由事件监听方负责，这里只反映不可下达）。
     */
    @Transactional
    public List<BathOrderVO> listVO() {
        List<BathOrder> orders = bathOrderRepository.findAllOrderByIdDesc();
        List<BathOrderVO> result = new ArrayList<>();
        for (BathOrder order : orders) {
            ClothCart cart = cartRepository.findById(order.getCartId()).orElse(null);
            result.add(toVO(order, cart, true));
        }
        return result;
    }

    // ============================================================
    // 失效：供事件监听方调用（来源变化时把引用旧资料的待确认单打掉）
    // ============================================================

    /** 配方内容变化：引用该配方的待确认单全部失效（已下达快照不动）。 */
    @Transactional
    public int invalidateByRecipe(Long recipeId, int newRevision) {
        int count = 0;
        for (BathOrder order : bathOrderRepository.findByStatus("PENDING")) {
            if (Objects.equals(order.getRecipeId(), recipeId)
                    && !Objects.equals(order.getRecipeRevision(), newRevision)) {
                markInvalid(order, "配方 " + order.getRecipeCode() + " 内容已修改（引用时版本号 "
                        + order.getRecipeRevision() + "，最新 " + newRevision + "），请基于最新配方重新创建执行单");
                bathOrderRepository.save(order);
                count++;
            }
        }
        return count;
    }

    /** 曲线节点变化：引用该曲线的待确认单全部失效。 */
    @Transactional
    public int invalidateByCurve(Long curveId, int newRevision) {
        int count = 0;
        for (BathOrder order : bathOrderRepository.findByStatus("PENDING")) {
            if (Objects.equals(order.getCurveId(), curveId)
                    && !Objects.equals(order.getCurveNodeRevision(), newRevision)) {
                markInvalid(order, "工艺曲线 " + order.getCurveCode() + " 节点已调整（引用时节点版本号 "
                        + order.getCurveNodeRevision() + "，最新 " + newRevision + "），请基于最新曲线重新创建执行单");
                bathOrderRepository.save(order);
                count++;
            }
        }
        return count;
    }

    /** 布车排缸结果变化：令牌对不上的待确认单失效。 */
    @Transactional
    public int invalidateByCart(Long cartId, String newToken, String detail) {
        int count = 0;
        for (BathOrder order : bathOrderRepository.findPendingByCart(cartId)) {
            if (!Objects.equals(order.getAssignToken(), newToken)) {
                markInvalid(order, "布车排缸结果已变化：" + detail);
                bathOrderRepository.save(order);
                count++;
            }
        }
        return count;
    }

    /** 手动作废自己那张待确认单（建错了允许丢弃，不影响任何机台）。 */
    @Transactional
    public BathOrder cancel(Long id) {
        BathOrder order = bathOrderRepository.findById(id)
                .orElseThrow(() -> new BizException("开缸执行单不存在：id=" + id));
        if ("ISSUED".equals(order.getStatus())) {
            throw new BizException("执行单 " + order.getOrderNo() + " 已下达并冻结，不能作废");
        }
        if ("INVALID".equals(order.getStatus())) {
            return order;
        }
        markInvalid(order, "操作员手动作废该待确认单");
        return bathOrderRepository.save(order);
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /** 逐项核对，所有差异都给出具体数值。 */
    private List<BathOrderVO.SourceCheck> buildChecks(ClothCart cart, DyeVat vat, Recipe recipe,
                                                      ProcessCurve curve, CurveSummary summary) {
        List<BathOrderVO.SourceCheck> checks = new ArrayList<>();

        // ① 布车必须已排入染缸
        if (cart.getVatId() == null || vat == null) {
            checks.add(new BathOrderVO.SourceCheck("cartAssigned", "布车已排缸", false,
                    "布车 " + cart.getCartCode() + " 尚未排入染缸，请先在布车看板完成排缸"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("cartAssigned", "布车已排缸", true,
                    "布车 " + cart.getCartCode() + " 已排入 " + vat.getVatCode()));
        }

        // ② 布种匹配
        if (!Objects.equals(cart.getFabricType(), recipe.getFabricType())) {
            checks.add(new BathOrderVO.SourceCheck("fabricMatch", "布种匹配", false,
                    "布车布种为 " + cart.getFabricType() + "，配方 " + recipe.getRecipeCode()
                            + " 适用布种为 " + recipe.getFabricType() + "，两者不一致"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("fabricMatch", "布种匹配", true,
                    "布车与配方布种均为 " + cart.getFabricType()));
        }

        // ③ 色号匹配
        if (!Objects.equals(cart.getColorCode(), recipe.getColorCode())) {
            checks.add(new BathOrderVO.SourceCheck("colorMatch", "色号匹配", false,
                    "布车色号为 " + cart.getColorCode() + "（" + nullToEmpty(cart.getColorName())
                            + "），配方色号为 " + recipe.getColorCode() + "（" + nullToEmpty(recipe.getColorName()) + "），两者不一致"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("colorMatch", "色号匹配", true,
                    "布车与配方色号均为 " + cart.getColorCode() + "（" + cart.getColorName() + "）"));
        }

        // ④ 配方必须已发布
        if (!"RELEASED".equals(recipe.getStatus())) {
            checks.add(new BathOrderVO.SourceCheck("recipeReleased", "配方已发布", false,
                    "配方 " + recipe.getRecipeCode() + " 当前为 " + recipeStatusName(recipe.getStatus())
                            + "，只有已发布配方才能作为开缸依据"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("recipeReleased", "配方已发布", true,
                    "配方 " + recipe.getRecipeCode() + " 已发布（V" + recipe.getVersionNo() + "）"));
        }

        // ⑤ 曲线与配方/布车/色号匹配
        boolean curveRecipeOk = curve.getRecipeId() != null && Objects.equals(curve.getRecipeId(), recipe.getId());
        boolean curveFabricOk = Objects.equals(curve.getFabricType(), cart.getFabricType());
        boolean curveColorOk = curve.getColorCode() == null
                || Objects.equals(curve.getColorCode(), cart.getColorCode());
        if (!curveRecipeOk || !curveFabricOk || !curveColorOk) {
            List<String> diffs = new ArrayList<>();
            if (!curveRecipeOk) {
                diffs.add("曲线关联配方 id=" + curve.getRecipeId() + "，与所选配方 " + recipe.getId() + " 不同");
            }
            if (!curveFabricOk) {
                diffs.add("曲线适用布种 " + curve.getFabricType() + "，布车为 " + cart.getFabricType());
            }
            if (!curveColorOk) {
                diffs.add("曲线色号 " + curve.getColorCode() + "，布车为 " + cart.getColorCode());
            }
            checks.add(new BathOrderVO.SourceCheck("curveMatch", "曲线匹配", false,
                    "工艺曲线 " + curve.getCurveCode() + " 不匹配：" + String.join("；", diffs)));
        } else {
            checks.add(new BathOrderVO.SourceCheck("curveMatch", "曲线匹配", true,
                    "曲线 " + curve.getCurveCode() + " 与配方、布种、色号一致"));
        }

        // ⑥ 染缸容量（布重按 0.18kg/m 折算，与排缸口径一致）
        BigDecimal weight = weightOf(cart);
        if (vat != null && vat.getCapacityKg() != null
                && weight.compareTo(vat.getCapacityKg()) > 0) {
            BigDecimal over = weight.subtract(vat.getCapacityKg()).setScale(2, RoundingMode.HALF_UP);
            checks.add(new BathOrderVO.SourceCheck("capacity", "染缸容量", false,
                    "布车折算布重 " + weight.setScale(2, RoundingMode.HALF_UP) + "kg，超过 "
                            + vat.getVatCode() + " 容量 " + vat.getCapacityKg() + "kg，超出 " + over + "kg"));
        } else if (vat != null) {
            checks.add(new BathOrderVO.SourceCheck("capacity", "染缸容量", true,
                    "布重 " + weight.setScale(2, RoundingMode.HALF_UP) + "kg ≤ 染缸容量 "
                            + vat.getCapacityKg() + "kg"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("capacity", "染缸容量", false, "无已排染缸，无法核对容量"));
        }

        // ⑦ 机台温度上限（关键温度 = 曲线最高节点温度）
        BigDecimal machineMax = vat == null ? null : MACHINE_MAX_TEMP.get(vat.getMachineType());
        if (vat != null && machineMax != null && summary.targetTemp.compareTo(machineMax) > 0) {
            checks.add(new BathOrderVO.SourceCheck("tempLimit", "机台温度上限", false,
                    "曲线关键温度 " + summary.targetTemp + "℃ 超过 " + vat.getVatCode() + "（"
                            + vat.getMachineType() + "）机台上限 " + machineMax + "℃，超高 "
                            + summary.targetTemp.subtract(machineMax).setScale(1, RoundingMode.HALF_UP) + "℃"));
        } else if (vat != null) {
            checks.add(new BathOrderVO.SourceCheck("tempLimit", "机台温度上限", true,
                    "关键温度 " + summary.targetTemp + "℃ ≤ 机台上限 " + machineMax + "℃"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("tempLimit", "机台温度上限", false, "无已排染缸，无法核对温度上限"));
        }

        return checks;
    }

    /** 下达/查看时拿当前最新资料复核一张待确认单。 */
    private List<BathOrderVO.SourceCheck> revalidate(BathOrder order, ClothCart cart) {
        List<BathOrderVO.SourceCheck> checks = new ArrayList<>();
        if (cart == null) {
            checks.add(new BathOrderVO.SourceCheck("cart", "布车", false, "布车已不存在或已报废"));
            return checks;
        }

        // 排缸令牌：排缸结果变没变
        if (!Objects.equals(cart.getAssignToken(), order.getAssignToken())) {
            String now = cart.getVatId() == null
                    ? "布车已退出排缸"
                    : "布车已重新排到 " + cart.getVatCode();
            checks.add(new BathOrderVO.SourceCheck("assign", "排缸结果", false,
                    now + "（建单时为 " + order.getVatCode() + "）"));
        } else if (cart.getVatId() == null) {
            checks.add(new BathOrderVO.SourceCheck("assign", "排缸结果", false, "布车当前不在任何染缸上"));
        } else {
            checks.add(new BathOrderVO.SourceCheck("assign", "排缸结果", true,
                    "布车仍在 " + cart.getVatCode() + "，排缸批次未变"));
        }

        Optional<Recipe> recipeOpt = recipeRepository.findById(order.getRecipeId());
        if (recipeOpt.isEmpty()) {
            checks.add(new BathOrderVO.SourceCheck("recipe", "配方", false,
                    "配方 " + order.getRecipeCode() + " 已被删除"));
        } else {
            Recipe recipe = recipeOpt.get();
            if (!Objects.equals(recipe.getRevision(), order.getRecipeRevision())) {
                checks.add(new BathOrderVO.SourceCheck("recipe", "配方版本", false,
                        "配方 " + recipe.getRecipeCode() + " 内容已修改（建单时版本号 "
                                + order.getRecipeRevision() + "，最新 " + recipe.getRevision() + "）"));
            } else if (!"RELEASED".equals(recipe.getStatus())) {
                checks.add(new BathOrderVO.SourceCheck("recipe", "配方状态", false,
                        "配方 " + recipe.getRecipeCode() + " 已变为 " + recipeStatusName(recipe.getStatus())));
            } else {
                checks.add(new BathOrderVO.SourceCheck("recipe", "配方版本", true,
                        "配方 " + recipe.getRecipeCode() + " 仍为建单时版本（V"
                                + recipe.getVersionNo() + " #r" + recipe.getRevision() + "）"));
            }
        }

        Optional<ProcessCurve> curveOpt = curveRepository.findById(order.getCurveId());
        if (curveOpt.isEmpty()) {
            checks.add(new BathOrderVO.SourceCheck("curve", "曲线", false,
                    "曲线 " + order.getCurveCode() + " 已被删除"));
        } else {
            ProcessCurve curve = curveOpt.get();
            if (!Objects.equals(curve.getNodeRevision(), order.getCurveNodeRevision())) {
                checks.add(new BathOrderVO.SourceCheck("curve", "曲线节点", false,
                        "曲线 " + curve.getCurveCode() + " 节点已调整（建单时节点版本号 "
                                + order.getCurveNodeRevision() + "，最新 " + curve.getNodeRevision() + "）"));
            } else {
                checks.add(new BathOrderVO.SourceCheck("curve", "曲线节点", true,
                        "曲线 " + curve.getCurveCode() + " 节点未变（#n" + curve.getNodeRevision() + "）"));
            }
        }

        // 完整匹配项（布种/色号/容量/温度）再跑一遍，防止来源被改成别的布种色号
        DyeVat vat = cart.getVatId() == null ? null
                : vatRepository.findById(cart.getVatId()).orElse(null);
        Recipe recipe = recipeOpt.orElse(null);
        ProcessCurve curve = curveOpt.orElse(null);
        if (recipe != null && curve != null) {
            CurveSummary summary = CurveSummary.of(curve);
            for (BathOrderVO.SourceCheck check : buildChecks(cart, vat, recipe, curve, summary)) {
                if (List.of("fabricMatch", "colorMatch", "recipeReleased", "curveMatch", "capacity", "tempLimit")
                        .contains(check.getKey()) && !check.isPass()) {
                    checks.add(check);
                }
            }
        }

        // 并发收口：同布车同排缸批次若已有生效单，本单只能失效
        List<BathOrder> winners = bathOrderRepository
                .findIssuedByCartAndToken(cart.getId(), cart.getAssignToken());
        for (BathOrder winner : winners) {
            if (!Objects.equals(winner.getId(), order.getId())) {
                checks.add(new BathOrderVO.SourceCheck("winner", "唯一生效单", false,
                        "布车 " + cart.getCartCode() + " 已由执行单 " + winner.getOrderNo()
                                + " 下达生效，本单不能重复下达"));
            }
        }
        return checks;
    }

    /** 组装展示对象：PENDING 展示最新资料，ISSUED 展示冻结快照。 */
    private BathOrderVO toVO(BathOrder order, ClothCart cart, boolean listMode) {
        BathOrderVO vo = new BathOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setInvalidReason(order.getInvalidReason());
        vo.setCartId(order.getCartId());
        vo.setCartCode(order.getCartCode());
        vo.setVatId(order.getVatId());
        vo.setVatCode(order.getVatCode());
        vo.setVatMachineType(order.getVatMachineType());
        vo.setVatCapacityKg(order.getVatCapacityKg());
        vo.setFabricType(order.getFabricType());
        vo.setColorCode(order.getColorCode());
        vo.setColorName(order.getColorName());
        vo.setMeters(order.getMeters());
        vo.setFabricWeightKg(order.getFabricWeightKg());
        vo.setRecipeId(order.getRecipeId());
        vo.setRecipeCode(order.getRecipeCode());
        vo.setRecipeVersionNo(order.getRecipeVersionNo());
        vo.setRecipeRevision(order.getRecipeRevision());
        vo.setCurveId(order.getCurveId());
        vo.setCurveCode(order.getCurveCode());
        vo.setCurveNodeRevision(order.getCurveNodeRevision());
        vo.setTargetTemp(order.getTargetTemp());
        vo.setHoldMinutes(order.getHoldMinutes());
        vo.setTotalMinutes(order.getTotalMinutes());
        vo.setMachineMaxTemp(MACHINE_MAX_TEMP.get(order.getVatMachineType()));
        vo.setCreatedBy(order.getCreatedBy());
        vo.setCreateTime(order.getCreateTime());
        vo.setIssuedBy(order.getIssuedBy());
        vo.setIssuedTime(order.getIssuedTime());
        if (cart != null) {
            vo.setPlanStartTime(cart.getDyeStartTime());
            vo.setPlanEndTime(cart.getDyeEndTime());
        }

        if ("ISSUED".equals(order.getStatus())) {
            // 已下达：永远读快照，来源再编辑也不改写
            vo.setSnapshot(true);
            vo.setItems(order.getSnapshotItems().stream().map(this::toItemVO).toList());
            vo.setNodes(order.getSnapshotNodes().stream().map(this::toNodeVO).toList());
            vo.setSourceChecks(List.of(new BathOrderVO.SourceCheck("snapshot", "工艺快照", true,
                    "已于 " + order.getIssuedTime() + " 由 " + nullToEmpty(order.getIssuedBy())
                            + " 下达并冻结，后续来源编辑不影响本单")));
            vo.setIssueable(false);
            return vo;
        }

        // 待确认/已失效：读最新来源资料
        vo.setSnapshot(false);
        Recipe recipe = recipeRepository.findById(order.getRecipeId()).orElse(null);
        ProcessCurve curve = curveRepository.findById(order.getCurveId()).orElse(null);
        if (recipe != null) {
            vo.setRecipeStatus(recipe.getStatus());
            vo.setItems(recipe.getItems().stream().map(this::toItemVO).toList());
        } else {
            vo.setItems(List.of());
        }
        if (curve != null) {
            vo.setNodes(curve.getNodes().stream().map(this::toNodeVO).toList());
        } else {
            vo.setNodes(List.of());
        }

        List<BathOrderVO.SourceCheck> checks = revalidate(order, cart);
        vo.setSourceChecks(checks);
        boolean allPass = checks.stream().allMatch(BathOrderVO.SourceCheck::isPass);

        if ("PENDING".equals(order.getStatus()) && !allPass) {
            // 刷新时发现来源已变而事件还没标到（含并发下达落败方），补标失效，保证页面与下达口径一致
            List<BathOrderVO.SourceCheck> failed = checks.stream()
                    .filter(c -> !c.isPass()).toList();
            markInvalid(order, joinDetails(failed));
            bathOrderRepository.save(order);
            vo.setStatus("INVALID");
            vo.setInvalidReason(order.getInvalidReason());
            vo.setIssueable(false);
        } else {
            vo.setIssueable("PENDING".equals(order.getStatus()) && allPass);
        }
        return vo;
    }

    private void freezeSnapshots(BathOrder order, Recipe recipe, ProcessCurve curve) {
        order.getSnapshotItems().clear();
        int seq = 1;
        for (RecipeItem item : recipe.getItems()) {
            BathSnapshotItem snap = new BathSnapshotItem();
            snap.setBathOrder(order);
            snap.setSeqNo(seq++);
            snap.setItemType(item.getItemType());
            snap.setDyeName(item.getDyeName());
            snap.setOwfPercent(item.getOwfPercent());
            snap.setUnitPrice(item.getUnitPrice());
            snap.setColorHex(item.getColorHex());
            order.getSnapshotItems().add(snap);
        }
        order.getSnapshotNodes().clear();
        seq = 1;
        for (CurveNode node : curve.getNodes()) {
            BathSnapshotNode snap = new BathSnapshotNode();
            snap.setBathOrder(order);
            snap.setSeqNo(seq++);
            snap.setStage(node.getStage());
            snap.setTargetTemp(node.getTargetTemp());
            snap.setDurationMin(node.getDurationMin());
            snap.setRampRate(node.getRampRate());
            snap.setMemo(node.getMemo());
            order.getSnapshotNodes().add(snap);
        }
    }

    private BathOrderVO.ItemLine toItemVO(RecipeItem item) {
        BathOrderVO.ItemLine line = new BathOrderVO.ItemLine();
        line.setSeqNo(item.getSeqNo());
        line.setItemType(item.getItemType());
        line.setDyeName(item.getDyeName());
        line.setOwfPercent(item.getOwfPercent());
        line.setUnitPrice(item.getUnitPrice());
        line.setColorHex(item.getColorHex());
        return line;
    }

    private BathOrderVO.ItemLine toItemVO(BathSnapshotItem item) {
        BathOrderVO.ItemLine line = new BathOrderVO.ItemLine();
        line.setSeqNo(item.getSeqNo());
        line.setItemType(item.getItemType());
        line.setDyeName(item.getDyeName());
        line.setOwfPercent(item.getOwfPercent());
        line.setUnitPrice(item.getUnitPrice());
        line.setColorHex(item.getColorHex());
        return line;
    }

    private BathOrderVO.NodeLine toNodeVO(CurveNode node) {
        BathOrderVO.NodeLine line = new BathOrderVO.NodeLine();
        line.setSeqNo(node.getSeqNo());
        line.setStage(node.getStage());
        line.setTargetTemp(node.getTargetTemp());
        line.setDurationMin(node.getDurationMin());
        line.setRampRate(node.getRampRate());
        line.setMemo(node.getMemo());
        return line;
    }

    private BathOrderVO.NodeLine toNodeVO(BathSnapshotNode node) {
        BathOrderVO.NodeLine line = new BathOrderVO.NodeLine();
        line.setSeqNo(node.getSeqNo());
        line.setStage(node.getStage());
        line.setTargetTemp(node.getTargetTemp());
        line.setDurationMin(node.getDurationMin());
        line.setRampRate(node.getRampRate());
        line.setMemo(node.getMemo());
        return line;
    }

    private void markInvalid(BathOrder order, String reason) {
        order.setStatus("INVALID");
        order.setInvalidReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
    }

    private String nextOrderNo() {
        String day = LocalDateTime.now().format(ORDER_DATE);
        String prefix = "BO-" + day + "-";
        for (int seq = 1; seq < 1000; seq++) {
            String candidate = prefix + String.format("%03d", seq);
            if (bathOrderRepository.countByOrderNo(candidate) == 0) {
                return candidate;
            }
        }
        throw new BizException("当日开缸执行单序号已用尽");
    }

    private BigDecimal weightOf(ClothCart cart) {
        if (cart.getMeters() == null) {
            return BigDecimal.ZERO;
        }
        return cart.getMeters().multiply(KG_PER_METER).setScale(2, RoundingMode.HALF_UP);
    }

    private ClothCart getCart(Long id) {
        return cartRepository.findById(id)
                .orElseThrow(() -> new BizException("布车不存在或已报废：id=" + id));
    }

    private Recipe getRecipe(Long id) {
        return recipeRepository.findById(id)
                .orElseThrow(() -> new BizException("染色配方不存在或已删除：id=" + id));
    }

    private ProcessCurve getCurve(Long id) {
        return curveRepository.findById(id)
                .orElseThrow(() -> new BizException("工艺曲线不存在：id=" + id));
    }

    private String joinDetails(List<BathOrderVO.SourceCheck> checks) {
        return checks.stream().map(c -> c.getLabel() + "：" + c.getDetail())
                .reduce((a, b) -> a + "；" + b).orElse("来源资料不一致");
    }

    private String recipeStatusName(String status) {
        return switch (status == null ? "" : status) {
            case "DRAFT" -> "草稿";
            case "RELEASED" -> "已发布";
            case "ARCHIVED" -> "已归档";
            default -> status;
        };
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 曲线关键工艺结论：关键温度=最高节点温度；保温时长=最高温 HOLD 节点合计；总时长=各节点合计。 */
    private static final class CurveSummary {
        private final BigDecimal targetTemp;
        private final int holdMinutes;
        private final int totalMinutes;

        private CurveSummary(BigDecimal targetTemp, int holdMinutes, int totalMinutes) {
            this.targetTemp = targetTemp;
            this.holdMinutes = holdMinutes;
            this.totalMinutes = totalMinutes;
        }

        private static CurveSummary of(ProcessCurve curve) {
            BigDecimal max = curve.getStartTemp() == null ? BigDecimal.valueOf(30) : curve.getStartTemp();
            int total = 0;
            for (CurveNode node : curve.getNodes()) {
                if (node.getTargetTemp() != null && node.getTargetTemp().compareTo(max) > 0) {
                    max = node.getTargetTemp();
                }
                if (node.getDurationMin() != null) {
                    total += node.getDurationMin();
                }
            }
            int hold = 0;
            for (CurveNode node : curve.getNodes()) {
                if ("HOLD".equalsIgnoreCase(node.getStage())
                        && node.getTargetTemp() != null
                        && node.getTargetTemp().compareTo(max) == 0
                        && node.getDurationMin() != null) {
                    hold += node.getDurationMin();
                }
            }
            return new CurveSummary(max.setScale(1, RoundingMode.HALF_UP), hold, total);
        }
    }
}
