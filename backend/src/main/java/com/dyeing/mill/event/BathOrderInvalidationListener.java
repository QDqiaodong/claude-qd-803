package com.dyeing.mill.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dyeing.mill.service.BathOrderService;

/**
 * 来源资料变更 → 待确认开缸执行单失效。
 *
 * 来源服务（配方/曲线/布车排缸）在自己的事务里调用本组件注册 afterCommit 钩子：
 *  - 只有来源事务成功提交后才执行失效（回滚不触发），保证「最新资料已落库」；
 *  - 失效动作在全新事务中独立提交，来源保存不会被失效处理的失败拖累；
 *  - 已下达（ISSUED）单只引用冻结快照，任何钩子都不影响它。
 *
 * 另一条最终防线：下达时在布车行悲观锁内重新核对版本，即使钩子执行失败也绝不放过过期单。
 */
@Component
public class BathOrderInvalidationListener {

    private final BathOrderService bathOrderService;

    public BathOrderInvalidationListener(BathOrderService bathOrderService) {
        this.bathOrderService = bathOrderService;
    }

    /** 配方内容版本变化：引用旧 revision 的待确认单失效 */
    public void afterRecipeCommit(Long recipeId, int newRevision) {
        registerOrRun(() -> bathOrderService.invalidateByRecipe(recipeId, newRevision));
    }

    /** 曲线节点版本变化：引用旧 nodeRevision 的待确认单失效 */
    public void afterCurveCommit(Long curveId, int newRevision) {
        registerOrRun(() -> bathOrderService.invalidateByCurve(curveId, newRevision));
    }

    /** 布车排缸结果变化：令牌对不上的待确认单失效 */
    public void afterCartAssignmentCommit(Long cartId, String newToken, String detail) {
        registerOrRun(() -> bathOrderService.invalidateByCart(cartId, newToken, detail));
    }

    /** 在调用方事务上注册提交后钩子；当前无事务上下文则直接在新事务执行。 */
    private void registerOrRun(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runInNewTx(action);
                }
            });
        } else {
            runInNewTx(action);
        }
    }

    private void runInNewTx(Runnable action) {
        try {
            bathOrderService.runInNewTransaction(action);
        } catch (Exception e) {
            // 钩子失败不影响来源保存结果；下达时的行锁内复核是最终防线
            System.err.println("[bath-invalidation] 失效处理失败，将由下达复核兜底：" + e.getMessage());
        }
    }
}
