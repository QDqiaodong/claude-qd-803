package com.dyeing.mill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.BathOrder;
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
import com.dyeing.mill.dto.CartAssignRequest;
import com.dyeing.mill.dto.CurveNodeInput;
import com.dyeing.mill.dto.CurvePayload;
import com.dyeing.mill.dto.RecipeItemInput;
import com.dyeing.mill.dto.RecipePayload;
import com.dyeing.mill.repository.BathOrderRepository;
import com.dyeing.mill.repository.ClothCartRepository;
import com.dyeing.mill.repository.DyeVatRepository;
import com.dyeing.mill.repository.ProcessCurveRepository;
import com.dyeing.mill.repository.RecipeRepository;
import com.dyeing.mill.service.BathOrderService;
import com.dyeing.mill.service.CartService;
import com.dyeing.mill.service.CurveService;
import com.dyeing.mill.service.RecipeService;

/**
 * 开缸执行单验收测试（每个场景独立数据，互不干扰）：
 *  正常下达、来源变化失效、并发重复下达、中途异常回滚、快照不可变、创建前差异核对。
 */
@SpringBootTest
class BathOrderIntegrationTest {

    @Autowired BathOrderService bathOrderService;
    @Autowired BathOrderRepository bathOrderRepository;
    @Autowired ClothCartRepository cartRepository;
    @Autowired DyeVatRepository vatRepository;
    @Autowired RecipeRepository recipeRepository;
    @Autowired ProcessCurveRepository curveRepository;
    @Autowired RecipeService recipeService;
    @Autowired CurveService curveService;
    @Autowired CartService cartService;

    @MockBean StringRedisTemplate redisTemplate;
    @MockBean ValueOperations<String, String> valueOps;

    // 静态序号：Spring 测试上下文跨方法复用、库里种子已占用 1~9xxx，编码必须全程唯一
    private static final AtomicLong SEQ = new AtomicLong(9100);

    @BeforeEach
    void mockRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private long next() { return SEQ.incrementAndGet(); }

    // ---------- 造数 ----------

    private DyeVat newVat(String code, String machine, double capacity, double target, String status) {
        DyeVat v = new DyeVat();
        v.setVatCode(code);
        v.setMachineType(machine);
        v.setCapacityKg(BigDecimal.valueOf(capacity));
        v.setLiquorRatio(BigDecimal.valueOf(8));
        v.setStatus(status);
        v.setCurrentTemp(BigDecimal.valueOf(32));
        v.setTargetTemp(BigDecimal.valueOf(target));
        v.setHoldMinutes(0);
        v.setHoldRemainMinutes(0);
        v.setWorkshop("验收区");
        v.setDeleted(0);
        return vatRepository.save(v);
    }

    private ClothCart newCart(String code, String fabric, String color, String colorName,
                              double meters, DyeVat vat, String token) {
        ClothCart c = new ClothCart();
        c.setCartCode(code);
        c.setFabricType(fabric);
        c.setColorCode(color);
        c.setColorName(colorName);
        c.setCustomer("验收客户");
        c.setMeters(BigDecimal.valueOf(meters));
        c.setStatus(vat != null ? "DYEING" : "PENDING");
        c.setReworkTimes(0);
        c.setDeleted(0);
        if (vat != null) {
            c.setVatId(vat.getId());
            c.setVatCode(vat.getVatCode());
            c.setDyeStartTime(LocalDateTime.now());
            c.setDyeEndTime(LocalDateTime.now().plusHours(3));
            c.setAssignToken(token);
        }
        return cartRepository.save(c);
    }

    /** 已发布配方：2 条染料凑 100% + 1 条助剂 */
    private Recipe newRecipe(String code, String fabric, String color, String colorName, String dye0Name) {
        Recipe r = new Recipe();
        r.setRecipeCode(code);
        r.setColorCode(color);
        r.setColorName(colorName);
        r.setCustomer("验收客户");
        r.setFabricType(fabric);
        r.setVersionNo(1);
        r.setColorHex("#123456");
        r.setStatus("RELEASED");
        r.setTotalOwf(new BigDecimal("100.000"));
        r.setCostPerKg(new BigDecimal("80.00"));
        r.setRevision(1);
        r.setCreateTime(LocalDateTime.now());
        r.setDeleted(0);
        r = recipeRepository.save(r);

        addItem(r, 1, "DYE", dye0Name, "60.000", "80.00");
        addItem(r, 2, "DYE", "副染料", "40.000", "70.00");
        addItem(r, 3, "AUX", "匀染剂", "1.500", "20.00");
        return recipeRepository.save(r);
    }

    private void addItem(Recipe r, int seqNo, String type, String name, String owf, String price) {
        RecipeItem item = new RecipeItem();
        item.setRecipe(r);
        item.setSeqNo(seqNo);
        item.setItemType(type);
        item.setDyeName(name);
        item.setOwfPercent(new BigDecimal(owf));
        item.setUnitPrice(new BigDecimal(price));
        item.setDeleted(0);
        r.getItems().add(item);
    }

    /**
     * 工艺曲线：30→95℃（升温45′，速率1.44，对 COTTON/NYLON/POLY 布种档案都不超限）
     * 保温30′ → 降温15′，总时长 90，关键温度 95
     */
    private ProcessCurve newCurve(String code, String fabric, String color, Recipe recipe) {
        ProcessCurve c = new ProcessCurve();
        c.setCurveCode(code);
        c.setCurveName("验收曲线");
        c.setFabricType(fabric);
        c.setColorCode(color);
        c.setRecipeId(recipe.getId());
        c.setStartTemp(BigDecimal.valueOf(30));
        c.setTotalMinutes(90);
        c.setMaxRampRate(new BigDecimal("1.44"));
        c.setNodeRevision(1);
        c.setDeleted(0);
        c = curveRepository.save(c);
        addNode(c, 1, "RAMP", 95, 45, "1.44", "升温");
        addNode(c, 2, "HOLD", 95, 30, "0.00", "保温");
        addNode(c, 3, "COOL", 45, 15, "-3.33", "降温");
        return curveRepository.save(c);
    }

    private void addNode(ProcessCurve c, int seqNo, String stage, int temp, int dur, String rate, String memo) {
        CurveNode n = new CurveNode();
        n.setCurve(c);
        n.setSeqNo(seqNo);
        n.setStage(stage);
        n.setTargetTemp(BigDecimal.valueOf(temp));
        n.setDurationMin(dur);
        n.setRampRate(new BigDecimal(rate));
        n.setMemo(memo);
        c.getNodes().add(n);
    }

    private BathOrder createPending(ClothCart cart, Recipe recipe, ProcessCurve curve, String op) {
        BathOrderCreateRequest req = new BathOrderCreateRequest();
        req.setCartId(cart.getId());
        req.setRecipeId(recipe.getId());
        req.setCurveId(curve.getId());
        req.setOperator(op);
        return bathOrderService.create(req);
    }

    private BathIssueRequest issueReq(String op, String failStep) {
        BathIssueRequest r = new BathIssueRequest();
        r.setOperator(op);
        r.setFailStep(failStep);
        return r;
    }

    // ============================================================
    // 场景 0：创建前核对 —— 布种/色号不符、容量超限、温度超上限 都只能报差异
    // ============================================================

    @Test
    void preflight_reports_concrete_differences_and_blocks_create() {
        DyeVat jig = newVat("VT-" + next(), "JIG", 300, 32, "IDLE");
        DyeVat overflow = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        // 匹配车：COTTON/C-9001
        Recipe cotton = newRecipe("RC-" + next(), "COTTON", "C-9001", "测试藏青", "活性藏青");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9001", cotton);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9001", "测试藏青", 1000, overflow, "tok-match");

        // 全部通过
        BathPreflightDTO ok = bathOrderService.preflight(cart.getId(), cotton.getId(), curve.getId());
        assertThat(ok.isAllPass()).isTrue();
        assertThat(ok.getTargetTemp()).isEqualByComparingTo("95.0");
        assertThat(ok.getHoldMinutes()).isEqualTo(30);

        // 布种/色号不符：POLY 配方配 COTTON 车
        Recipe poly = newRecipe("RC-" + next(), "POLY", "C-9002", "测试酒红", "分散红玉");
        ProcessCurve polyCurve = newCurve("PC-" + next(), "POLY", "C-9002", poly);
        BathPreflightDTO mismatch = bathOrderService.preflight(cart.getId(), poly.getId(), polyCurve.getId());
        assertThat(mismatch.isAllPass()).isFalse();
        assertThat(mismatch.getChecks()).anyMatch(c -> c.getKey().equals("fabricMatch") && !c.isPass()
                && c.getDetail().contains("COTTON") && c.getDetail().contains("POLY"));
        assertThat(mismatch.getChecks()).anyMatch(c -> c.getKey().equals("colorMatch") && !c.isPass());

        long before = bathOrderRepository.count();
        BathOrderCreateRequest bad = new BathOrderCreateRequest();
        bad.setCartId(cart.getId());
        bad.setRecipeId(poly.getId());
        bad.setCurveId(polyCurve.getId());
        assertThatThrownBy(() -> bathOrderService.create(bad))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法创建")
                .hasMessageContaining("布种匹配");
        assertThat(bathOrderRepository.count()).isEqualTo(before);

        // 容量超限：4000m=720kg 排入 300kg JIG
        ClothCart heavy = newCart("CC-" + next(), "COTTON", "C-9001", "测试藏青", 4000, jig, "tok-heavy");
        BathPreflightDTO cap = bathOrderService.preflight(heavy.getId(), cotton.getId(), curve.getId());
        assertThat(cap.getChecks()).anyMatch(c -> c.getKey().equals("capacity") && !c.isPass()
                && c.getDetail().contains("720.00kg") && c.getDetail().contains("超出 420.00kg"));

        // 温度超机台上限：95℃ 曲线给 JIG（上限98℃其实不超）——再造一条 130℃ 曲线
        ClothCart hotCart = newCart("CC-" + next(), "POLY", "C-9002", "测试酒红", 500, jig, "tok-hot");
        ProcessCurve hotCurve = newCurve("PC-" + next(), "POLY", "C-9002", poly);
        // 直接把节点温度改成 130
        hotCurve.getNodes().get(0).setTargetTemp(BigDecimal.valueOf(130));
        hotCurve.getNodes().get(1).setTargetTemp(BigDecimal.valueOf(130));
        curveRepository.save(hotCurve);
        BathPreflightDTO temp = bathOrderService.preflight(hotCart.getId(), poly.getId(), hotCurve.getId());
        assertThat(temp.getChecks()).anyMatch(c -> c.getKey().equals("tempLimit") && !c.isPass()
                && c.getDetail().contains("130") && c.getDetail().contains("98"));

        // 未排缸
        ClothCart pendingCart = newCart("CC-" + next(), "COTTON", "C-9001", "测试藏青", 500, null, null);
        BathPreflightDTO unassigned = bathOrderService.preflight(pendingCart.getId(), cotton.getId(), curve.getId());
        assertThat(unassigned.getChecks()).anyMatch(c -> c.getKey().equals("cartAssigned") && !c.isPass());
    }

    // ============================================================
    // 场景 1：正常下达 —— 冻结快照 + 推进染缸 + 追溯号 + 禁止重复
    // ============================================================

    @Test
    void normal_issue_freezes_snapshot_and_pushes_vat_params() {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 60, "HEATING");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9101", "正常色", "活性染料A");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9101", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9101", "正常色", 1000, vat, "tok-normal");

        BathOrder order = createPending(cart, recipe, curve, "王建国");
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getSnapshotItems()).isEmpty();

        BathOrder issued = bathOrderService.issue(order.getId(), issueReq("王建国", null));
        assertThat(issued.getStatus()).isEqualTo("ISSUED");
        assertThat(issued.getSnapshotItems()).hasSize(3);
        assertThat(issued.getSnapshotNodes()).hasSize(3);
        assertThat(issued.getTargetTemp()).isEqualByComparingTo("95.0");
        assertThat(issued.getHoldMinutes()).isEqualTo(30);
        assertThat(issued.getTotalMinutes()).isEqualTo(90);

        DyeVat vatAfter = vatRepository.findById(vat.getId()).orElseThrow();
        assertThat(vatAfter.getTargetTemp()).isEqualByComparingTo("95.0");
        assertThat(vatAfter.getHoldMinutes()).isEqualTo(30);
        assertThat(vatAfter.getStatus()).isEqualTo("HEATING");
        assertThat(vatAfter.getActiveOrderNo()).isEqualTo(issued.getOrderNo());
        assertThat(cartRepository.findById(cart.getId()).orElseThrow().getActiveOrderNo())
                .isEqualTo(issued.getOrderNo());

        verify(valueOps, atLeastOnce()).set("mill:vat:" + vat.getVatCode() + ":target", "95.0");

        // 重复下达被拒绝
        assertThatThrownBy(() -> bathOrderService.issue(issued.getId(), issueReq("李红梅", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已下达");
        // 已下达不可作废
        assertThatThrownBy(() -> bathOrderService.cancel(issued.getId()))
                .isInstanceOf(BizException.class);
    }

    // ============================================================
    // 场景 2：来源变化 → 待确认单失效并阻止下达
    // ============================================================

    @Test
    void recipe_edit_invalidates_pending_but_not_issued() {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        Recipe recipe = newRecipe("RC-" + next(), "NYLON", "C-9102", "失效色", "酸性染料");
        ProcessCurve curve = newCurve("PC-" + next(), "NYLON", "C-9102", recipe);
        ClothCart cart = newCart("CC-" + next(), "NYLON", "C-9102", "失效色", 800, vat, "tok-inv-recipe");

        BathOrder pending = createPending(cart, recipe, curve, "张伟");
        int oldRev = recipe.getRevision();

        // 保存配方（明细改名，owf 仍 100%）
        RecipePayload payload = payloadOf(recipe);
        payload.getItems().get(0).setDyeName("酸性染料-改版");
        recipeService.update(recipe.getId(), payload);
        assertThat(recipeRepository.findById(recipe.getId()).orElseThrow().getRevision())
                .isEqualTo(oldRev + 1);

        BathOrder after = bathOrderRepository.findById(pending.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("INVALID");
        assertThat(after.getInvalidReason()).contains("配方");
        assertThat(bathOrderService.detail(pending.getId()).isIssueable()).isFalse();

        assertThatThrownBy(() -> bathOrderService.issue(pending.getId(), issueReq("张伟", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("失效");

        // 已下达单不受影响：另起一套数据下达后再改配方
        DyeVat vat2 = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        Recipe recipe2 = newRecipe("RC-" + next(), "NYLON", "C-9103", "快照色", "酸性染料B");
        ProcessCurve curve2 = newCurve("PC-" + next(), "NYLON", "C-9103", recipe2);
        ClothCart cart2 = newCart("CC-" + next(), "NYLON", "C-9103", "快照色", 800, vat2, "tok-snap");
        BathOrder issued = bathOrderService.issue(createPending(cart2, recipe2, curve2, "李梅").getId(),
                issueReq("李梅", null));
        String frozenDye = issued.getSnapshotItems().get(0).getDyeName();

        recipeService.update(recipe2.getId(), payloadOf(recipe2, "酸性染料B-事后改名"));
        BathOrderVO vo = bathOrderService.detail(issued.getId());
        assertThat(vo.isSnapshot()).isTrue();
        assertThat(vo.getStatus()).isEqualTo("ISSUED");
        assertThat(vo.getItems().get(0).getDyeName()).isEqualTo(frozenDye);
    }

    @Test
    void curve_node_change_invalidates_pending() {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9104", "曲线色", "活性染料C");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9104", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9104", "曲线色", 800, vat, "tok-inv-curve");

        BathOrder pending = createPending(cart, recipe, curve, "李红梅");

        CurvePayload payload = new CurvePayload();
        List<CurveNodeInput> nodes = new ArrayList<>();
        for (CurveNode n : curveRepository.findById(curve.getId()).orElseThrow().getNodes()) {
            CurveNodeInput in = new CurveNodeInput();
            in.setStage(n.getStage());
            in.setTargetTemp(n.getTargetTemp());
            in.setDurationMin(n.getDurationMin());
            in.setMemo(n.getMemo() + "·微调");
            nodes.add(in);
        }
        payload.setNodes(nodes);
        curveService.saveNodes(curve.getId(), payload);

        BathOrder after = bathOrderRepository.findById(pending.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("INVALID");
        assertThat(after.getInvalidReason()).contains("曲线");
    }

    @Test
    void reassignment_invalidates_pending() {
        DyeVat v1 = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        DyeVat v2 = newVat("VT-" + next(), "OVERFLOW", 600, 32, "IDLE");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9105", "换缸色", "活性染料D");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9105", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9105", "换缸色", 800, v1, "tok-assign-1");

        BathOrder pending = createPending(cart, recipe, curve, "王建国");

        // 用排缸接口把布车重新排到 v2（时段不与其他车冲突）
        CartAssignRequest assign = new CartAssignRequest();
        assign.setVatId(v2.getId());
        assign.setStartTime("2026-10-02 09:00");
        assign.setEndTime("2026-10-02 12:00");
        cartService.assign(cart.getId(), assign);

        BathOrder after = bathOrderRepository.findById(pending.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("INVALID");
        assertThat(after.getInvalidReason()).contains(v2.getVatCode());
    }

    // ============================================================
    // 场景 3：并发重复下达 —— 只一张成功，另一方刷新看到生效单
    // ============================================================

    @Test
    void concurrent_issue_only_one_succeeds() throws Exception {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9106", "并发色", "活性染料E");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9106", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9106", "并发色", 800, vat, "tok-concurrent");
        BathOrder pending = createPending(cart, recipe, curve, "组长");
        long orderId = pending.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<String>> futures = List.of(
                pool.submit(() -> { gun.await(); bathOrderService.issue(orderId, issueReq("甲", null)); return "OK"; }),
                pool.submit(() -> { gun.await(); bathOrderService.issue(orderId, issueReq("乙", null)); return "OK"; })
        );
        gun.countDown();

        int success = 0, rejected = 0;
        for (Future<String> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                rejected++;
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                assertThat(msg).containsAnyOf("已下达", "失效", "生效", "并发", "冲突");
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        BathOrder finalOrder = bathOrderRepository.findById(orderId).orElseThrow();
        assertThat(finalOrder.getStatus()).isEqualTo("ISSUED");
        // 落败方刷新后：详情给出唯一生效单的提示（自己就是生效单；再造一张同批待确认单验证落败视角）
        BathOrder loser = createPending(cart, recipe, curve, "组长2");
        BathOrderVO loserVO = bathOrderService.detail(loser.getId());
        assertThat(loserVO.getStatus()).isEqualTo("INVALID");
        assertThat(loserVO.getSourceChecks()).anyMatch(c -> c.getKey().equals("winner"));

        DyeVat vatAfter = vatRepository.findById(vat.getId()).orElseThrow();
        assertThat(vatAfter.getActiveOrderNo()).isEqualTo(finalOrder.getOrderNo());
        ClothCart cartAfter = cartRepository.findById(cart.getId()).orElseThrow();
        assertThat(cartAfter.getActiveOrderNo()).isEqualTo(finalOrder.getOrderNo());
    }

    // ============================================================
    // 场景 4：下达中途异常 —— 单据/布车/染缸全部回滚
    // ============================================================

    @Test
    void failure_at_vat_step_rolls_back_everything() {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 77, "HOLDING");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9107", "故障色A", "活性染料F");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9107", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9107", "故障色A", 800, vat, "tok-fail-vat");
        BathOrder pending = createPending(cart, recipe, curve, "刘志强");

        assertThatThrownBy(() -> bathOrderService.issue(pending.getId(), issueReq("刘志强", "vat")))
                .isInstanceOf(BizException.class).hasMessageContaining("故障注入");

        BathOrder o = bathOrderRepository.findById(pending.getId()).orElseThrow();
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getSnapshotItems()).isEmpty();
        DyeVat v = vatRepository.findById(vat.getId()).orElseThrow();
        assertThat(v.getStatus()).isEqualTo("HOLDING");
        assertThat(v.getTargetTemp()).isEqualByComparingTo("77");
        assertThat(v.getActiveOrderNo()).isNull();
        assertThat(cartRepository.findById(cart.getId()).orElseThrow().getActiveOrderNo()).isNull();
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    void failure_at_redis_step_rolls_back_db() {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 50, "HEATING");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9108", "故障色B", "活性染料G");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9108", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9108", "故障色B", 800, vat, "tok-fail-redis");
        BathOrder pending = createPending(cart, recipe, curve, "刘志强");

        assertThatThrownBy(() -> bathOrderService.issue(pending.getId(), issueReq("刘志强", "redis")))
                .isInstanceOf(BizException.class).hasMessageContaining("故障注入");

        BathOrder o = bathOrderRepository.findById(pending.getId()).orElseThrow();
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getSnapshotItems()).isEmpty();
        DyeVat v = vatRepository.findById(vat.getId()).orElseThrow();
        assertThat(v.getStatus()).isEqualTo("HEATING");
        assertThat(v.getTargetTemp()).isEqualByComparingTo("50");
        assertThat(v.getActiveOrderNo()).isNull();
        verify(valueOps, never()).set(anyString(), anyString());
    }

    // ============================================================
    // 场景 5：同排缸批次不能开两张待确认单
    // ============================================================

    @Test
    void duplicate_pending_same_assignment_blocked() {
        DyeVat vat = newVat("VT-" + next(), "OVERFLOW", 500, 32, "IDLE");
        Recipe recipe = newRecipe("RC-" + next(), "COTTON", "C-9109", "重复色", "活性染料H");
        ProcessCurve curve = newCurve("PC-" + next(), "COTTON", "C-9109", recipe);
        ClothCart cart = newCart("CC-" + next(), "COTTON", "C-9109", "重复色", 800, vat, "tok-dup");
        createPending(cart, recipe, curve, "甲");

        BathOrderCreateRequest again = new BathOrderCreateRequest();
        again.setCartId(cart.getId());
        again.setRecipeId(recipe.getId());
        again.setCurveId(curve.getId());
        assertThatThrownBy(() -> bathOrderService.create(again))
                .isInstanceOf(BizException.class).hasMessageContaining("已存在待确认");
    }

    // ---------- helpers ----------

    private RecipePayload payloadOf(Recipe r) {
        return payloadOf(r, null);
    }

    private RecipePayload payloadOf(Recipe r, String renameFirst) {
        RecipePayload p = new RecipePayload();
        p.setRecipeCode(r.getRecipeCode());
        p.setColorCode(r.getColorCode());
        p.setColorName(r.getColorName());
        p.setCustomer(r.getCustomer());
        p.setFabricType(r.getFabricType());
        p.setVersionNo(r.getVersionNo());
        p.setColorHex(r.getColorHex());
        p.setLabL(r.getLabL());
        p.setLabA(r.getLabA());
        p.setLabB(r.getLabB());
        p.setStatus(r.getStatus());
        List<RecipeItemInput> items = new ArrayList<>();
        boolean first = true;
        for (RecipeItem it : r.getItems()) {
            RecipeItemInput in = new RecipeItemInput();
            in.setItemType(it.getItemType());
            in.setDyeName(renameFirst != null && first && "DYE".equals(it.getItemType()) ? renameFirst : it.getDyeName());
            in.setOwfPercent(it.getOwfPercent());
            in.setUnitPrice(it.getUnitPrice());
            in.setColorHex(it.getColorHex());
            items.add(in);
            first = false;
        }
        p.setItems(items);
        return p;
    }
}
