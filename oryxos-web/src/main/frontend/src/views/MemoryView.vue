<script setup>
import { computed, onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * 长期记忆页（契约 admin-console.md §3-第 4 页）。
 *
 * 两区的口径与注入 prompt 时完全一致：核心区一字不截；归档区超限时从尾部截断，
 * 页面上如实标出「已截断」——运维看到的是同一份东西，不是另一份加工过的视图。
 */
const loading = ref(true)
const error = ref('')
const memory = ref({ core: '', archival: '', truncated: false })

const coreEmpty = computed(() => !memory.value.core || memory.value.core.trim() === '')
const archivalEmpty = computed(() => !memory.value.archival || memory.value.archival.trim() === '')

async function load() {
  loading.value = true
  error.value = ''
  try {
    memory.value = await get('/api/v1/memory')
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div v-if="loading" class="card state">正在读取长期记忆…</div>

    <div v-else-if="error" class="card state state-error">读取失败：{{ error }}</div>

    <template v-else>
      <div class="card">
        <h2 class="card-title">核心记忆（全量注入 prompt，永不截断）</h2>
        <p v-if="coreEmpty" class="state">核心区还是空的。</p>
        <pre v-else class="memory-block">{{ memory.core }}</pre>
      </div>

      <div class="card">
        <h2 class="card-title">
          归档记忆（关键词检索只搜这一区）
          <span v-if="memory.truncated" class="tag tag-warn truncated">归档区已截断</span>
        </h2>
        <p v-if="archivalEmpty" class="state">归档区还是空的。</p>
        <pre v-else class="memory-block">{{ memory.archival }}</pre>
      </div>
    </template>
  </section>
</template>

<style scoped>
.memory-block {
  max-height: 420px;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.truncated {
  font-weight: 400;
}
</style>
