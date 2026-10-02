<template>
  <div class="page bath-page">
    <!-- 顶部概览 + 操作 -->
    <div class="panel bath-summary">
      <div class="bath-summary__item">
        <span class="muted">待确认</span>
        <b class="c-pending">{{ overview.pending || 0 }}</b>
      </div>
      <div class="bath-summary__item">
        <span class="muted">已下达</span>
        <b class="c-issued">{{ overview.issued || 0 }}</b>
      </div>
      <div class="bath-summary__item">
        <span class="muted">已失效</span>
        <b class="c-invalid">{{ overview.invalid || 0 }}</b>
      </div>
      <div class="bath-summary__grow"></div>
      <el-button size="small" @click="load">刷新</el-button>
      <el-button type="primary" size="small" @click="openCreate">新建开缸执行单</el-button>
    </div>

    <!-- 执行单列表 -->
    <div class="panel">
      <div class="panel__head">
        <span class="panel__title">开缸执行单</span>
        <el-radio-group v-model="statusFilter" size="small">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="PENDING">待确认</el-radio-button>
          <el-radio-button label="ISSUED">已下达</el-radio-button>
          <el-radio-button label="INVALID">已失效</el-radio-button>
        </el-radio-group>
      </div>
      <div class="panel__body">
        <el-table :data="filteredOrders" size="small" border stripe @row-click="openDetail">
          <el-table-column prop="orderNo" label="执行单号" width="150" />
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <span :class="['status-tag', 'st-' + row.status]">{{ statusName(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="布车 / 染缸" width="150">
            <template #default="{ row }">
              <b>{{ row.cartCode }}</b>
              <span class="muted"> / {{ row.vatCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="布种 · 色号" width="150">
            <template #default="{ row }">
              {{ fabricName(row.fabricType) }} · {{ row.colorName }}
            </template>
          </el-table-column>
          <el-table-column label="配方版本" width="130">
            <template #default="{ row }">
              {{ row.recipeCode }}
              <span class="muted">V{{ row.recipeVersionNo }}#r{{ row.recipeRevision }}</span>
            </template>
          </el-table-column>
          <el-table-column label="曲线" width="110">
            <template #default="{ row }">{{ row.curveCode }}<span class="muted"> #n{{ row.curveNodeRevision }}</span></template>
          </el-table-column>
          <el-table-column label="关键温度" width="120">
            <template #default="{ row }">
              <b class="temp-hot">{{ row.targetTemp }}℃</b>
              <span class="muted"> / 上限{{ row.machineMaxTemp }}℃</span>
            </template>
          </el-table-column>
          <el-table-column label="保温 / 总时长" width="120">
            <template #default="{ row }">{{ row.holdMinutes }}′ / {{ row.totalMinutes }}′</template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="openDetail(row)">查看</el-button>
              <el-button v-if="row.status === 'PENDING' && row.issueable" link type="success" size="small"
                         @click.stop="quickIssue(row)">下达</el-button>
              <el-button v-if="row.status === 'PENDING'" link type="warning" size="small"
                         @click.stop="quickCancel(row)">作废</el-button>
              <span v-if="row.status === 'ISSUED'" class="muted">{{ row.issuedBy }} 已下达</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ================= 新建执行单：先核对差异，核对不过不落单 ================= -->
    <el-dialog v-model="createVisible" title="新建开缸执行单 · 先核对三项匹配" width="760px" top="6vh">
      <el-form label-width="84px">
        <el-form-item label="布车">
          <el-select v-model="form.cartId" filterable placeholder="仅可选择已排入染缸的布车" style="width: 100%"
                     @change="onSourceChange">
            <el-option v-for="c in assignedCarts" :key="c.id" :value="c.id"
                       :label="`${c.cartCode} · ${fabricName(c.fabricType)} · ${c.colorName} · ${c.vatCode}${c.activeOrderNo ? '（已开缸 ' + c.activeOrderNo + '）' : ''}`"
                       :disabled="!!c.activeOrderNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="染色配方">
          <el-select v-model="form.recipeId" filterable placeholder="选择已发布配方" style="width: 100%"
                     @change="onSourceChange">
            <el-option v-for="r in recipes" :key="r.id" :value="r.id"
                       :label="`${r.recipeCode} · ${r.colorName} · ${fabricName(r.fabricType)} · ${recipeStatusName(r.status)} V${r.versionNo}#r${r.revision || 1}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="工艺曲线">
          <el-select v-model="form.curveId" filterable placeholder="选择匹配工艺曲线" style="width: 100%"
                     @change="onSourceChange">
            <el-option v-for="c in curves" :key="c.id" :value="c.id"
                       :label="`${c.curveCode} · ${c.curveName} · ${fabricName(c.fabricType)}${c.colorCode ? ' · ' + c.colorCode : ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="下达人">
          <el-select v-model="form.operator" style="width: 200px">
            <el-option v-for="op in OPERATORS" :key="op" :value="op" :label="op" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- 核对结论 -->
      <div v-if="preflight" class="preflight">
        <div class="preflight__head">
          <span>创建前核对（任一项不通过都不能生成执行单）</span>
          <span :class="['preflight__verdict', preflight.allPass ? 'is-ok' : 'is-bad']">
            {{ preflight.allPass ? '全部通过，可创建待确认单' : '存在差异，禁止创建' }}
          </span>
        </div>
        <div v-for="chk in preflight.checks" :key="chk.key" :class="['chk-row', chk.pass ? 'is-ok' : 'is-bad']">
          <i class="chk-dot"></i>
          <b>{{ chk.label }}</b>
          <span>{{ chk.detail }}</span>
        </div>
        <div class="preflight__nums">
          <span>折算布重 <b>{{ fmtNum(preflight.fabricWeightKg, 2) }}kg</b></span>
          <span>预计时长 <b>{{ preflight.totalMinutes }}′</b></span>
          <span>关键温度 <b class="temp-hot">{{ preflight.targetTemp }}℃</b></span>
          <span>保温 <b>{{ preflight.holdMinutes }}′</b></span>
        </div>
      </div>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!preflight || !preflight.allPass" :loading="creating"
                   @click="submitCreate">
          创建待确认执行单
        </el-button>
      </template>
    </el-dialog>

    <!-- ================= 执行单详情 ================= -->
    <el-drawer v-model="detailVisible" size="62%" :title="detail ? detail.orderNo + ' · 开缸执行单' : '开缸执行单'">
      <div v-if="detail" class="detail">
        <!-- 状态横幅 -->
        <div :class="['banner', 'bn-' + detail.status]">
          <template v-if="detail.status === 'PENDING'">
            <b>待确认</b>
            <span>页面引用最新资料；确认无误后下达，下达瞬间冻结完整工艺快照。</span>
          </template>
          <template v-else-if="detail.status === 'ISSUED'">
            <b>已下达 · 工艺快照已冻结</b>
            <span>{{ detail.issuedBy }} 于 {{ fmtTime(detail.issuedTime) }} 下达，染缸已接收目标温度与保温时长；来源资料再编辑不影响本单。</span>
          </template>
          <template v-else>
            <b>已失效 · 禁止下达</b>
            <span>{{ detail.invalidReason }}</span>
          </template>
        </div>

        <!-- 布车与染缸 + 关键工艺 -->
        <div class="kv-grid">
          <div class="kv"><span>布车</span><b>{{ detail.cartCode }}</b></div>
          <div class="kv"><span>染缸</span><b>{{ detail.vatCode }} · {{ machineName(detail.vatMachineType) }}</b></div>
          <div class="kv"><span>染缸容量</span><b>{{ detail.vatCapacityKg }}kg</b></div>
          <div class="kv"><span>布重折算</span><b>{{ fmtNum(detail.fabricWeightKg, 2) }}kg（{{ detail.meters }}m）</b></div>
          <div class="kv"><span>布种 / 色号</span><b>{{ fabricName(detail.fabricType) }} · {{ detail.colorName }}（{{ detail.colorCode }}）</b></div>
          <div class="kv"><span>计划时段</span><b>{{ fmtTime(detail.planStartTime) }} → {{ fmtTime(detail.planEndTime) }}</b></div>
          <div class="kv kv--hot"><span>关键温度</span><b>{{ detail.targetTemp }}℃</b><i>机台上限 {{ detail.machineMaxTemp }}℃</i></div>
          <div class="kv"><span>保温时长</span><b>{{ detail.holdMinutes }} 分钟</b></div>
          <div class="kv"><span>预计总时长</span><b>{{ detail.totalMinutes }} 分钟</b></div>
        </div>

        <!-- 来源核对 -->
        <div class="panel detail__panel">
          <div class="panel__head">
            <span class="panel__title">{{ detail.snapshot ? '工艺快照（不可变）' : '来源核对（实时最新资料）' }}</span>
            <span class="muted">
              配方 {{ detail.recipeCode }} V{{ detail.recipeVersionNo }}#r{{ detail.recipeRevision }}
              ｜ 曲线 {{ detail.curveCode }} #n{{ detail.curveNodeRevision }}
            </span>
          </div>
          <div class="panel__body">
            <div v-for="chk in detail.sourceChecks" :key="chk.key" :class="['chk-row', chk.pass ? 'is-ok' : 'is-bad']">
              <i class="chk-dot"></i>
              <b>{{ chk.label }}</b>
              <span>{{ chk.detail }}</span>
            </div>
          </div>
        </div>

        <!-- 配方明细 -->
        <div class="panel detail__panel">
          <div class="panel__head">
            <span class="panel__title">染色配方明细{{ detail.snapshot ? ' · 快照' : ' · 最新' }}</span>
            <span class="muted" v-if="!detail.snapshot">配方状态：{{ recipeStatusName(detail.recipeStatus) }}</span>
          </div>
          <div class="panel__body">
            <table class="mini-table">
              <thead><tr><th>#</th><th>类型</th><th>染料 / 助剂</th><th>owf%</th><th>单价</th></tr></thead>
              <tbody>
                <tr v-for="it in detail.items" :key="it.seqNo">
                  <td>{{ it.seqNo }}</td>
                  <td><span :class="['type-tag', it.itemType === 'DYE' ? 'tt-dye' : 'tt-aux']">{{ it.itemType === 'DYE' ? '染料' : '助剂' }}</span></td>
                  <td>{{ it.dyeName }}</td>
                  <td>{{ fmtNum(it.owfPercent, 3) }}%</td>
                  <td>{{ it.unitPrice }} 元/kg</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 曲线节点 -->
        <div class="panel detail__panel">
          <div class="panel__head">
            <span class="panel__title">工艺曲线节点{{ detail.snapshot ? ' · 快照' : ' · 最新' }}</span>
            <span class="muted">合计 {{ detail.totalMinutes }}′ · 关键温度 {{ detail.targetTemp }}℃</span>
          </div>
          <div class="panel__body">
            <table class="mini-table">
              <thead><tr><th>#</th><th>阶段</th><th>目标温度</th><th>时长</th><th>速率℃/min</th><th>备注</th></tr></thead>
              <tbody>
                <tr v-for="n in detail.nodes" :key="n.seqNo"
                    :class="Number(n.targetTemp) === Number(detail.targetTemp) && n.stage === 'HOLD' ? 'is-peak' : ''">
                  <td>{{ n.seqNo }}</td>
                  <td><span :class="['stage-tag', 'sg-' + n.stage]">{{ stageName(n.stage) }}</span></td>
                  <td>{{ n.targetTemp }}℃</td>
                  <td>{{ n.durationMin }}′</td>
                  <td>{{ fmtNum(n.rampRate, 2) }}</td>
                  <td class="muted">{{ n.memo }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 机台联动状态 -->
        <div class="panel detail__panel" v-if="linkedVat">
          <div class="panel__head"><span class="panel__title">染缸 {{ linkedVat.vatCode }} 实时状态（下达后应与本单一致）</span></div>
          <div class="panel__body vat-strip">
            <span>状态 <b :class="'c-vat-' + linkedVat.status">{{ vatStatusName(linkedVat.status) }}</b></span>
            <span>当前温度 <b>{{ linkedVat.currentTemp }}℃</b></span>
            <span>目标温度 <b class="temp-hot">{{ linkedVat.targetTemp }}℃</b></span>
            <span>保温 <b>{{ linkedVat.holdMinutes }}′</b>（剩 {{ linkedVat.holdRemainMinutes }}′）</span>
            <span>追溯单号 <b>{{ linkedVat.activeOrderNo || '—' }}</b></span>
          </div>
        </div>

        <!-- 操作 -->
        <div class="detail__ops">
          <template v-if="detail.status === 'PENDING'">
            <el-button type="warning" @click="quickCancel(detail)">作废</el-button>
            <el-button type="primary" :disabled="!detail.issueable" :loading="issuing" @click="doIssue(false)">
              确认下达（冻结快照并推进染缸 {{ detail.targetTemp }}℃ / {{ detail.holdMinutes }}′）
            </el-button>
          </template>
          <template v-else-if="detail.status === 'INVALID'">
            <el-button @click="detailVisible = false">关闭</el-button>
            <el-button type="primary" @click="detailVisible = false; openCreate()">基于最新资料重新创建</el-button>
          </template>
          <template v-else>
            <el-button @click="detailVisible = false">关闭</el-button>
            <span class="muted">已下达单据不可编辑、不可作废，永久保留下达时工艺快照</span>
          </template>
        </div>
      </div>
    </el-drawer>

    <!-- ================= 验收工具：并发 / 中途异常 ================= -->
    <el-drawer v-model="verifyVisible" title="验收工具 · 并发重复下达 / 中途异常" size="520px">
      <div class="verify">
        <div class="panel">
          <div class="panel__head"><span class="panel__title">① 正常下达后：来源编辑不影响快照</span></div>
          <div class="panel__body muted">
            先在列表下达一张单，再到「染色配方」保存配方或「工艺曲线」拖动节点保存，
            回到本页打开该单，明细与节点仍为下达时内容，状态保持已下达。
          </div>
        </div>

        <div class="panel">
          <div class="panel__head"><span class="panel__title">② 来源变化导致失效</span></div>
          <div class="panel__body">
            <p class="muted">选一张待确认单，模拟其来源被修改（不真正改配方数据，只演示链路时请直接到对应模块保存一次）。</p>
            <el-button size="small" @click="load">刷新失效标记</el-button>
          </div>
        </div>

        <div class="panel">
          <div class="panel__head"><span class="panel__title">③ 并发重复下达同一布车</span></div>
          <div class="panel__body">
            <p class="muted">选择同一布车的待确认单（若只有一张，可先新建第二张——系统会禁止；
              正确验收方式：两名操作员各自打开同一张单，同时点下达）。这里用两个请求同时打同一张单模拟：</p>
            <el-select v-model="verifyOrderId" placeholder="选择待确认执行单" size="small" style="width: 100%">
              <el-option v-for="o in pendingOrders" :key="o.id" :value="o.id"
                         :label="`${o.orderNo} · ${o.cartCode} · ${o.vatCode}`" />
            </el-select>
            <div class="verify__ops">
              <el-button type="primary" size="small" :disabled="!verifyOrderId" :loading="racing"
                         @click="runConcurrent">两人同时下达（并发两请求）</el-button>
            </div>
            <div v-if="raceResult.length" class="race-log">
              <div v-for="(r, i) in raceResult" :key="i" :class="['race-line', r.ok ? 'is-ok' : 'is-bad']">
                <b>操作员{{ i === 0 ? '甲' : '乙' }}</b>
                <span>{{ r.ok ? '下达成功：' + r.orderNo : '被拒绝：' + r.message }}</span>
              </div>
              <el-button link type="primary" size="small" @click="load()">刷新列表查看唯一生效单</el-button>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel__head"><span class="panel__title">④ 下达中途异常：单据 / 布车 / 染缸全部回滚</span></div>
          <div class="panel__body">
            <p class="muted">在染缸参数推进前、或控温推送前注入故障。失败后单据仍为待确认、染缸温度保持原值。</p>
            <div class="verify__ops">
              <el-button size="small" type="warning" :disabled="!verifyOrderId"
                         @click="runFail('vat')">故障注入：染缸更新前失败</el-button>
              <el-button size="small" type="warning" :disabled="!verifyOrderId"
                         @click="runFail('redis')">故障注入：控温推送前失败</el-button>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 验收工具悬浮入口 -->
    <button class="verify-fab" @click="openVerify">验收</button>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listBathOrders, getBathOrder, bathPreflight, createBathOrder,
  issueBathOrder, cancelBathOrder,
  listCarts, listRecipes, listCurves, listVats
} from '../api'

const props = defineProps({ params: { type: Object, default: () => ({}) } })

const FABRIC = { COTTON: '全棉', POLY: '涤纶', NYLON: '锦纶', BLEND: '涤棉', VISCOSE: '人棉', SILK: '真丝' }
const MACHINE = { OVERFLOW: '溢喷', AIRFLOW: '气流', JIG: '卷染' }
const VAT_STATUS = { IDLE: '空闲', HEATING: '升温', HOLDING: '保温', COOLING: '降温', DRAINING: '排液', WASHING: '清洗' }
const OPERATORS = ['王建国', '李红梅', '张伟', '陈晓芳', '刘志强']

const orders = ref([])
const overview = ref({})
const carts = ref([])
const recipes = ref([])
const curves = ref([])
const vats = ref([])
const statusFilter = ref('')

const createVisible = ref(false)
const creating = ref(false)
const form = ref({ cartId: null, recipeId: null, curveId: null, operator: OPERATORS[0] })
const preflight = ref(null)

const detailVisible = ref(false)
const detail = ref(null)
const issuing = ref(false)
const linkedVat = ref(null)

const verifyVisible = ref(false)
const verifyOrderId = ref(null)
const racing = ref(false)
const raceResult = ref([])

const filteredOrders = computed(() =>
  statusFilter.value ? orders.value.filter((o) => o.status === statusFilter.value) : orders.value)
const pendingOrders = computed(() => orders.value.filter((o) => o.status === 'PENDING'))
const assignedCarts = computed(() =>
  carts.value.filter((c) => c.vatId && c.status === 'DYEING'))

function fabricName(k) { return FABRIC[k] || k }
function machineName(k) { return MACHINE[k] || k }
function vatStatusName(k) { return VAT_STATUS[k] || k }
function recipeStatusName(s) { return { DRAFT: '草稿', RELEASED: '已发布', ARCHIVED: '已归档' }[s] || s }
function stageName(s) { return { RAMP: '升温', HOLD: '保温', COOL: '降温' }[s] || s }
function statusName(s) { return { PENDING: '待确认', ISSUED: '已下达', INVALID: '已失效' }[s] || s }
function fmtNum(v, d = 1) { return v === null || v === undefined ? '--' : Number(v).toFixed(d) }
function fmtTime(v) { return v ? String(v).replace('T', ' ').slice(0, 16) : '' }

async function load() {
  try {
    const [ors, cs, rs, pcs, vs] = await Promise.all([
      listBathOrders(), listCarts(), listRecipes(), listCurves(), listVats()
    ])
    orders.value = ors.data || []
    carts.value = cs.data || []
    recipes.value = rs.data || []
    curves.value = pcs.data || []
    vats.value = vs.data || []
    overview.value = {
      pending: orders.value.filter((o) => o.status === 'PENDING').length,
      issued: orders.value.filter((o) => o.status === 'ISSUED').length,
      invalid: orders.value.filter((o) => o.status === 'INVALID').length
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* ---------------- 新建 + 预核对 ---------------- */
async function openCreate() {
  await load()
  form.value = { cartId: null, recipeId: null, curveId: null, operator: OPERATORS[0] }
  preflight.value = null
  createVisible.value = true
}

let pfTimer = null
function onSourceChange() {
  clearTimeout(pfTimer)
  preflight.value = null
  const { cartId, recipeId, curveId } = form.value
  if (!cartId || !recipeId || !curveId) return
  pfTimer = setTimeout(runPreflight, 250)
  // 智能预选：布车确定后按色号带出已发布配方与匹配曲线
  if (cartId) {
    const cart = carts.value.find((c) => c.id === cartId)
    if (cart && !form.value.recipeId) {
      const r = recipes.value.find((x) => x.colorCode === cart.colorCode && x.status === 'RELEASED')
      if (r) form.value.recipeId = r.id
    }
    if (cart && !form.value.curveId) {
      const c = curves.value.find((x) => x.fabricType === cart.fabricType && x.colorCode === cart.colorCode)
      if (c) form.value.curveId = c.id
    }
  }
}

async function runPreflight() {
  const { cartId, recipeId, curveId } = form.value
  if (!cartId || !recipeId || !curveId) return
  try {
    const res = await bathPreflight(cartId, recipeId, curveId)
    preflight.value = res.data
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitCreate() {
  if (!preflight.value?.allPass) {
    ElMessage.warning('核对未全部通过，不能创建执行单')
    return
  }
  creating.value = true
  try {
    const res = await createBathOrder({
      cartId: form.value.cartId,
      recipeId: form.value.recipeId,
      curveId: form.value.curveId,
      operator: form.value.operator
    })
    ElMessage.success(res.message || '已创建待确认执行单')
    createVisible.value = false
    await load()
    const created = orders.value.find((o) => o.orderNo === res.data.orderNo)
    if (created) openDetail(created)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

/* ---------------- 详情 ---------------- */
async function openDetail(row) {
  try {
    // 先刷新基础数据（染缸实时状态），再取单据详情，保证两侧一致
    const [, res] = await Promise.all([load(), getBathOrder(row.id)])
    detail.value = res.data
    linkedVat.value = vats.value.find((v) => v.id === detail.value.vatId) || null
    detailVisible.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doIssue(withOperator) {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      `确认下达 ${detail.value.orderNo}？染缸 ${detail.value.vatCode} 将接收目标温度 ${detail.value.targetTemp}℃、保温 ${detail.value.holdMinutes} 分钟。`,
      '开缸下达确认', { type: 'warning', confirmButtonText: '确认下达', cancelButtonText: '再看看' })
  } catch {
    return
  }
  issuing.value = true
  try {
    const res = await issueBathOrder(detail.value.id, { operator: withOperator || form.value.operator || OPERATORS[0] })
    ElMessage.success(res.message || '已下达')
    await load()
    const res2 = await getBathOrder(detail.value.id)
    detail.value = res2.data
    linkedVat.value = vats.value.find((v) => v.id === detail.value.vatId) || null
  } catch (e) {
    ElMessage.error(e.message)
    await load()
  } finally {
    issuing.value = false
  }
}

async function quickIssue(row) {
  const fresh = await getBathOrder(row.id).catch((e) => {
    ElMessage.error(e.message)
    return null
  })
  if (!fresh) return
  if (fresh.data.status !== 'PENDING' || !fresh.data.issueable) {
    ElMessage.warning('该单当前不可下达（可能已失效或已被下达），请刷新查看')
    await load()
    return
  }
  detail.value = fresh.data
  linkedVat.value = vats.value.find((v) => v.id === detail.value.vatId) || null
  detailVisible.value = true
  await doIssue(OPERATORS[0])
}

async function quickCancel(row) {
  try {
    await ElMessageBox.confirm(`作废待确认单 ${row.orderNo}？已下达单不受影响。`, '作废确认', { type: 'warning' })
  } catch { return }
  try {
    await cancelBathOrder(row.id)
    ElMessage.success('已作废')
    if (detail.value?.id === row.id) detailVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* ---------------- 验收：并发 / 故障注入 ---------------- */
function openVerify() {
  verifyOrderId.value = pendingOrders.value[0]?.id || null
  raceResult.value = []
  verifyVisible.value = true
}

async function runConcurrent() {
  if (!verifyOrderId.value) return
  racing.value = true
  raceResult.value = []
  // 两个请求同时发出：服务端布车行悲观锁 + 数据库唯一键，保证只有一张成功
  const tasks = [
    issueBathOrder(verifyOrderId.value, { operator: '王建国' })
      .then((r) => ({ ok: true, orderNo: r.data.orderNo }))
      .catch((e) => ({ ok: false, message: e.message })),
    issueBathOrder(verifyOrderId.value, { operator: '李红梅' })
      .then((r) => ({ ok: true, orderNo: r.data.orderNo }))
      .catch((e) => ({ ok: false, message: e.message }))
  ]
  raceResult.value = await Promise.all(tasks)
  racing.value = false
  await load()
}

async function runFail(step) {
  const vatBefore = vats.value.find((v) => v.id === (orders.value.find((o) => o.id === verifyOrderId.value)?.vatId))
  const snapshotBefore = vatBefore ? { ...vatBefore } : null
  try {
    await issueBathOrder(verifyOrderId.value, { operator: '验收员', failStep: step })
    ElMessage.warning('故障注入竟然成功了？请检查')
  } catch (e) {
    ElMessage.error(e.message)
  }
  await load()
  await new Promise((r) => setTimeout(r, 300))
  const vatAfter = vats.value.find((v) => v.id === snapshotBefore?.id)
  const orderAfter = orders.value.find((o) => o.id === verifyOrderId.value)
  if (snapshotBefore && vatAfter && orderAfter) {
    const unchanged = String(vatAfter.targetTemp) === String(snapshotBefore.targetTemp)
      && String(vatAfter.status) === String(snapshotBefore.status)
      && orderAfter.status === 'PENDING'
    ElMessage({
      type: unchanged ? 'success' : 'error',
      message: unchanged
        ? `回滚正确：单据仍为待确认，染缸 ${vatAfter.vatCode} 仍为 ${snapshotBefore.status}/${snapshotBefore.targetTemp}℃`
        : `回滚异常：单据=${orderAfter.status}，染缸 ${snapshotBefore.status}/${snapshotBefore.targetTemp}℃ → ${vatAfter.status}/${vatAfter.targetTemp}℃`
    })
  }
}

load()

// 九宫格「待确认开缸」快捷入口：初始只看待确认
if (props.params && props.params.status) {
  statusFilter.value = props.params.status
}
</script>

<style scoped>
.bath-page { background: var(--mill-bg); }

.bath-summary {
  display: flex; align-items: center; gap: 30px;
  padding: 12px 16px; margin-bottom: 14px;
}
.bath-summary__item { display: flex; flex-direction: column; gap: 3px; }
.bath-summary__item b { font-size: 20px; }
.c-pending { color: #b26a00; }
.c-issued { color: #2e7d32; }
.c-invalid { color: #c62828; }
.bath-summary__grow { flex: 1 1 auto; }

.status-tag {
  display: inline-block; padding: 2px 10px; border-radius: 10px;
  font-size: 11px; font-weight: 700; color: #fff;
}
.st-PENDING { background: #b26a00; }
.st-ISSUED { background: #2e7d32; }
.st-INVALID { background: #c62828; }
.temp-hot { color: #d84315; }

/* 核对行 */
.preflight { border: 1px solid var(--mill-line); border-radius: 8px; margin-top: 8px; }
.preflight__head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 9px 12px; font-size: 13px; font-weight: 700;
  border-bottom: 1px solid var(--mill-line); background: var(--mill-primary-wash);
}
.preflight__verdict.is-ok { color: #2e7d32; }
.preflight__verdict.is-bad { color: #c62828; }
.chk-row {
  display: flex; align-items: baseline; gap: 9px;
  padding: 7px 12px; border-bottom: 1px dashed var(--mill-line);
  font-size: 12.5px;
}
.chk-row:last-child { border-bottom: none; }
.chk-row b { min-width: 84px; color: var(--mill-ink); }
.chk-row span { color: var(--mill-text); }
.chk-row.is-ok .chk-dot { background: #2e7d32; }
.chk-row.is-bad { background: #fff5f5; }
.chk-row.is-bad b, .chk-row.is-bad span { color: #b71c1c; }
.chk-row.is-bad .chk-dot { background: #c62828; }
.chk-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.preflight__nums {
  display: flex; gap: 22px; padding: 10px 12px; font-size: 12.5px;
  background: #faf8fd; border-top: 1px solid var(--mill-line);
}

/* 详情 */
.detail { display: flex; flex-direction: column; gap: 12px; padding: 16px; }
.banner {
  border-radius: 8px; padding: 12px 14px; display: flex; flex-direction: column; gap: 4px;
  font-size: 13px;
}
.banner b { font-size: 14px; }
.bn-PENDING { background: #fff6e5; border: 1px solid #f0c674; color: #8a5a00; }
.bn-ISSUED { background: #edf7ed; border: 1px solid #9ccc9c; color: #1b5e20; }
.bn-INVALID { background: #fdecea; border: 1px solid #e8a49e; color: #8e1b12; }

.kv-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px;
}
.kv {
  background: #fff; border: 1px solid var(--mill-line); border-radius: 8px;
  padding: 9px 12px; display: flex; flex-direction: column; gap: 4px;
}
.kv span { font-size: 11px; color: var(--mill-muted); }
.kv b { font-size: 13.5px; color: var(--mill-ink); }
.kv i { font-style: normal; font-size: 11px; color: var(--mill-muted); }
.kv--hot { border-color: #e8a37a; background: #fff7f3; }
.kv--hot b { color: #d84315; font-size: 16px; }

.detail__panel { }
.mini-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.mini-table th, .mini-table td {
  border: 1px solid var(--mill-line); padding: 6px 8px; text-align: left;
}
.mini-table th { background: var(--mill-primary-wash); color: var(--mill-ink); font-weight: 700; }
.mini-table tr.is-peak td { background: #fff3ec; font-weight: 700; }
.type-tag, .stage-tag {
  padding: 1px 7px; border-radius: 9px; color: #fff; font-size: 11px;
}
.tt-dye { background: var(--mill-primary); }
.tt-aux { background: #6d6d6d; }
.sg-RAMP { background: #ef6c00; }
.sg-HOLD { background: #4a148c; }
.sg-COOL { background: #0277bd; }

.vat-strip { display: flex; flex-wrap: wrap; gap: 20px; font-size: 13px; }
.c-vat-HEATING { color: #ef6c00; }
.c-vat-HOLDING { color: #4a148c; }
.c-vat-COOLING { color: #0277bd; }
.c-vat-IDLE, .c-vat-DRAINING, .c-vat-WASHING { color: var(--mill-muted); }

.detail__ops { display: flex; justify-content: flex-end; gap: 10px; align-items: center; }

/* 验收工具 */
.verify { display: flex; flex-direction: column; gap: 12px; padding: 16px; }
.verify__ops { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.race-log { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.race-line {
  display: flex; gap: 10px; padding: 7px 10px; border-radius: 6px; font-size: 12.5px;
}
.race-line b { min-width: 52px; }
.race-line.is-ok { background: #edf7ed; color: #1b5e20; }
.race-line.is-bad { background: #fdecea; color: #8e1b12; }
.verify-fab {
  position: fixed; right: 22px; bottom: 22px; z-index: 500;
  border: none; border-radius: 22px; padding: 10px 20px;
  background: var(--mill-primary-deep); color: #fff; font-size: 13px;
  box-shadow: 0 8px 22px rgba(44, 10, 85, 0.35); cursor: pointer;
}
.verify-fab:hover { background: var(--mill-primary); }
</style>
