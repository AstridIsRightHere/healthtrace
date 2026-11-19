<template>
  <div class="admin-page">
    <header class="admin-header">
      <h1>管理员控制台</h1>
      <p class="admin-subtitle">系统管理与配置</p>
    </header>

    <div class="admin-tabs">
      <button
        :class="['tab-btn', { active: activeTab === 'invitations' }]"
        @click="activeTab = 'invitations'"
        data-testid="admin-tab-invitations"
      >
        邀请码管理
      </button>
      <button
        :class="['tab-btn', { active: activeTab === 'metrics' }]"
        @click="activeTab = 'metrics'"
        data-testid="admin-tab-metrics"
      >
        指标字典
      </button>
    </div>

    <div v-if="activeTab === 'invitations'" class="tab-content">
      <div class="admin-section">
        <h2>生成邀请码</h2>
        <form @submit.prevent="createInvitation" class="invitation-form">
          <label>
            <span>有效天数</span>
            <input
              v-model.number="validDays"
              type="number"
              min="1"
              max="365"
              required
              data-testid="invitation-valid-days"
            />
          </label>
          <button type="submit" :disabled="creating" data-testid="invitation-create-btn">
            {{ creating ? '生成中...' : '生成邀请码' }}
          </button>
        </form>

        <div v-if="lastCreated" class="created-invitation" data-testid="invitation-created">
          <h3>邀请码已生成</h3>
          <div class="token-display">
            <code>{{ lastCreated.plainToken }}</code>
            <button @click="copyToken" data-testid="invitation-copy-btn">
              {{ copied ? '已复制' : '复制' }}
            </button>
          </div>
          <p class="expiry-note">
            过期时间：{{ formatDateTime(lastCreated.expiresAt) }}
          </p>
        </div>
      </div>

      <div class="admin-section">
        <h2>邀请码列表</h2>
        <button @click="loadInvitations" :disabled="loadingInvitations" data-testid="invitation-refresh-btn">
          {{ loadingInvitations ? '加载中...' : '刷新列表' }}
        </button>
        <table v-if="invitations.length > 0" class="invitation-table" data-testid="invitation-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>创建时间</th>
              <th>过期时间</th>
              <th>状态</th>
              <th>使用者</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="inv in invitations" :key="inv.id">
              <td>{{ inv.id }}</td>
              <td>{{ formatDateTime(inv.createdAt) }}</td>
              <td>{{ formatDateTime(inv.expiresAt) }}</td>
              <td>
                <span :class="['status-badge', getStatusClass(inv)]">
                  {{ getStatusText(inv) }}
                </span>
              </td>
              <td>{{ inv.usedBy || '-' }}</td>
              <td>
                <button
                  v-if="!inv.used && !isExpired(inv)"
                  @click="disableInvitation(inv.id)"
                  class="btn-danger-sm"
                  :data-testid="`invitation-disable-${inv.id}`"
                >
                  禁用
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="!loadingInvitations" class="empty-state">暂无邀请码</p>
      </div>
    </div>

    <div v-if="activeTab === 'metrics'" class="tab-content">
      <div class="admin-section">
        <h2>标准指标字典</h2>
        <button @click="loadMetrics" :disabled="loadingMetrics" data-testid="metrics-refresh-btn">
          {{ loadingMetrics ? '加载中...' : '刷新列表' }}
        </button>
        <table v-if="metrics.length > 0" class="metrics-table" data-testid="metrics-table">
          <thead>
            <tr>
              <th>代码</th>
              <th>标准名称</th>
              <th>单位</th>
              <th>报告类型</th>
              <th>国家码</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="metric in metrics" :key="metric.code">
              <td><code>{{ metric.code }}</code></td>
              <td>{{ metric.standardName }}</td>
              <td>{{ metric.standardUnit || '-' }}</td>
              <td>{{ metric.reportType }}</td>
              <td>{{ metric.nationalCode || '-' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="!loadingMetrics" class="empty-state">暂无数据</p>
      </div>
    </div>

    <div v-if="error" class="error-toast" data-testid="admin-error">
      {{ error }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { adminApi, type InvitationListItem, type MetricDefinition } from '../api/admin';

const activeTab = ref<'invitations' | 'metrics'>('invitations');
const validDays = ref(7);
const creating = ref(false);
const lastCreated = ref<{ plainToken: string; expiresAt: string } | null>(null);
const copied = ref(false);

const invitations = ref<InvitationListItem[]>([]);
const loadingInvitations = ref(false);

const metrics = ref<MetricDefinition[]>([]);
const loadingMetrics = ref(false);

const error = ref('');

async function createInvitation() {
  creating.value = true;
  error.value = '';
  try {
    lastCreated.value = await adminApi.createInvitation(validDays.value);
    await loadInvitations();
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } } };
    error.value = err.response?.data?.message || '生成邀请码失败';
  } finally {
    creating.value = false;
  }
}

async function copyToken() {
  if (!lastCreated.value) return;
  try {
    await navigator.clipboard.writeText(lastCreated.value.plainToken);
    copied.value = true;
    setTimeout(() => (copied.value = false), 2000);
  } catch {
    error.value = '复制失败';
  }
}

async function loadInvitations() {
  loadingInvitations.value = true;
  error.value = '';
  try {
    invitations.value = await adminApi.listInvitations();
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } } };
    error.value = err.response?.data?.message || '加载邀请码列表失败';
  } finally {
    loadingInvitations.value = false;
  }
}

async function disableInvitation(id: number) {
  if (!confirm('确定要禁用这个邀请码吗？')) return;
  try {
    await adminApi.disableInvitation(id);
    await loadInvitations();
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } } };
    error.value = err.response?.data?.message || '禁用失败';
  }
}

async function loadMetrics() {
  loadingMetrics.value = true;
  error.value = '';
  try {
    metrics.value = await adminApi.listMetrics();
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } } };
    error.value = err.response?.data?.message || '加载指标字典失败';
  } finally {
    loadingMetrics.value = false;
  }
}

function formatDateTime(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN');
}

function isExpired(inv: InvitationListItem): boolean {
  return new Date(inv.expiresAt) < new Date();
}

function getStatusText(inv: InvitationListItem): string {
  if (inv.used) return '已使用';
  if (isExpired(inv)) return '已过期';
  return '有效';
}

function getStatusClass(inv: InvitationListItem): string {
  if (inv.used) return 'used';
  if (isExpired(inv)) return 'expired';
  return 'active';
}

onMounted(() => {
  loadInvitations();
});
</script>

<style scoped>
.admin-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
}

.admin-header {
  margin-bottom: 32px;
}

.admin-header h1 {
  font-size: 28px;
  font-weight: 600;
  margin-bottom: 8px;
}

.admin-subtitle {
  color: #666;
  font-size: 14px;
}

.admin-tabs {
  display: flex;
  gap: 8px;
  border-bottom: 1px solid #e0e0e0;
  margin-bottom: 24px;
}

.tab-btn {
  padding: 12px 24px;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  font-size: 16px;
  color: #666;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: #333;
}

.tab-btn.active {
  color: #2196f3;
  border-bottom-color: #2196f3;
  font-weight: 500;
}

.tab-content {
  animation: fadeIn 0.3s;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.admin-section {
  background: white;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.admin-section h2 {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 16px;
}

.invitation-form {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  margin-bottom: 24px;
}

.invitation-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.invitation-form input {
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  width: 120px;
}

.invitation-form button {
  padding: 8px 16px;
  background: #2196f3;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.invitation-form button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.created-invitation {
  background: #e3f2fd;
  border-radius: 8px;
  padding: 16px;
  margin-top: 16px;
}

.created-invitation h3 {
  font-size: 16px;
  margin-bottom: 12px;
  color: #1976d2;
}

.token-display {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 8px;
}

.token-display code {
  flex: 1;
  background: white;
  padding: 12px;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 14px;
  word-break: break-all;
}

.token-display button {
  padding: 8px 16px;
  background: #1976d2;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
}

.expiry-note {
  font-size: 14px;
  color: #666;
}

.invitation-table,
.metrics-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 16px;
}

.invitation-table th,
.invitation-table td,
.metrics-table th,
.metrics-table td {
  padding: 12px;
  text-align: left;
  border-bottom: 1px solid #e0e0e0;
}

.invitation-table th,
.metrics-table th {
  font-weight: 600;
  background: #f5f5f5;
}

.status-badge {
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.status-badge.active {
  background: #c8e6c9;
  color: #2e7d32;
}

.status-badge.used {
  background: #e0e0e0;
  color: #616161;
}

.status-badge.expired {
  background: #ffcdd2;
  color: #c62828;
}

.btn-danger-sm {
  padding: 6px 12px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.btn-danger-sm:hover {
  background: #d32f2f;
}

.empty-state {
  text-align: center;
  color: #999;
  padding: 24px;
}

.error-toast {
  position: fixed;
  bottom: 24px;
  right: 24px;
  background: #f44336;
  color: white;
  padding: 16px 24px;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  animation: slideIn 0.3s;
}

@keyframes slideIn {
  from {
    transform: translateY(100%);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

@media (max-width: 768px) {
  .admin-page {
    padding: 16px;
  }

  .invitation-form {
    flex-direction: column;
    align-items: stretch;
  }

  .invitation-form input {
    width: 100%;
  }

  .token-display {
    flex-direction: column;
  }

  .invitation-table,
  .metrics-table {
    font-size: 14px;
  }

  .invitation-table th,
  .invitation-table td,
  .metrics-table th,
  .metrics-table td {
    padding: 8px;
  }
}
</style>
