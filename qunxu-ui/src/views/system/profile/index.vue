<template>
  <div class="profile-page">
    <!-- 左：用户信息卡 -->
    <n-card :bordered="false" class="card-block info-card">
      <div class="profile-head">
        <n-avatar round :size="72" style="background: linear-gradient(135deg, #6366f1, #ec4899); color: #fff; flex-shrink: 0; font-size: 28px">
          {{ displayName.charAt(0) }}
        </n-avatar>
        <div class="ph-meta">
          <div class="ph-name">{{ displayName }}</div>
          <div class="ph-role">{{ displayRole }}</div>
        </div>
      </div>

      <n-descriptions :column="1" label-placement="left" class="p-desc">
        <n-descriptions-item label="登录账号">
          <span class="tabular-nums">{{ profile?.username || '-' }}</span>
        </n-descriptions-item>
        <n-descriptions-item label="所属部门">{{ profile?.deptName || '-' }}</n-descriptions-item>
        <n-descriptions-item label="岗位">{{ (profile?.postNames || []).join('、') || '-' }}</n-descriptions-item>
        <n-descriptions-item label="角色">
          <n-tag v-for="r in profile?.roleNames || []" :key="r" size="small" round type="warning" style="margin-right: 6px">{{ r }}</n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="手机号">
          <span class="tabular-nums">{{ profile?.mobile || '-' }}</span>
        </n-descriptions-item>
        <n-descriptions-item label="邮箱">{{ profile?.email || '-' }}</n-descriptions-item>
        <n-descriptions-item label="创建时间">
          <span class="tabular-nums">{{ profile?.createTime ? profile.createTime.slice(0, 16).replace('T', ' ') : '-' }}</span>
        </n-descriptions-item>
      </n-descriptions>
    </n-card>

    <!-- 右：基本资料编辑 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <span class="form-title">基本资料</span>
      </template>
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="84" class="profile-form">
        <n-form-item label="用户昵称" path="nickname">
          <n-input v-model:value="form.nickname" placeholder="请输入昵称" clearable />
        </n-form-item>
        <n-form-item label="手机号" path="mobile">
          <n-input v-model:value="form.mobile" placeholder="请输入手机号" clearable />
        </n-form-item>
        <n-form-item label="邮箱" path="email">
          <n-input v-model:value="form.email" placeholder="请输入邮箱" clearable />
        </n-form-item>
        <div class="form-actions">
          <n-button type="primary" :loading="saving" @click="handleSave">保存修改</n-button>
          <n-button :disabled="saving" @click="resetForm">重置</n-button>
        </div>
      </n-form>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  NCard, NAvatar, NDescriptions, NDescriptionsItem, NForm, NFormItem, NInput,
  NButton, NTag, useMessage, useThemeVars, type FormInst, type FormRules,
} from 'naive-ui'
import { useUserStore } from '@/stores/user'

const message = useMessage()
const themeVars = useThemeVars()
const userStore = useUserStore()

const profile = computed(() => userStore.profile)
const displayName = computed(() => userStore.displayName)
const displayRole = computed(() => userStore.displayRole)

/* ================= 编辑表单 ================= */
const form = reactive({ nickname: '', email: '', mobile: '', avatar: '' })

// 资料就绪后回填表单（资料为异步加载，需要监听）
watch(
  profile,
  (p) => {
    if (p) {
      form.nickname = p.nickname
      form.email = p.email || ''
      form.mobile = p.mobile || ''
      form.avatar = p.avatar || ''
    }
  },
  { immediate: true },
)

const rules: FormRules = {
  nickname: [{ required: true, message: '请输入昵称', trigger: ['blur', 'input'] }],
  email: [
    {
      validator: (_rule, value: string) => {
        if (!value) return true
        return /^[\w.-]+@[\w-]+(\.[\w-]+)+$/.test(value) || new Error('邮箱格式不正确')
      },
      trigger: ['blur', 'input'],
    },
  ],
  mobile: [
    {
      validator: (_rule, value: string) => {
        if (!value) return true
        return /^1[3-9]\d{9}$/.test(value) || new Error('手机号格式不正确')
      },
      trigger: ['blur', 'input'],
    },
  ],
}

const formRef = ref<FormInst | null>(null)
const saving = ref(false)

function resetForm() {
  if (profile.value) {
    form.nickname = profile.value.nickname
    form.email = profile.value.email || ''
    form.mobile = profile.value.mobile || ''
    form.avatar = profile.value.avatar || ''
  }
  formRef.value?.restoreValidation()
}

async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    await userStore.updateProfileAction({
      nickname: form.nickname,
      email: form.email,
      mobile: form.mobile,
      avatar: form.avatar,
    })
    message.success('个人资料已更新')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-page {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 16px;
  align-items: start;
}
.card-block {
  border-radius: 14px;
}

/* 左：信息卡 */
.profile-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 4px 18px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
  margin-bottom: 16px;
}
.ph-meta {
  min-width: 0;
  line-height: 1.35;
}
.ph-name {
  font-size: 18px;
  font-weight: 700;
  color: v-bind('themeVars.textColor1');
}
.ph-role {
  margin-top: 3px;
  font-size: 13px;
  color: v-bind('themeVars.textColor3');
}
.p-desc {
  padding: 0 4px;
}

/* 右：编辑表单 */
.form-title {
  font-size: 15px;
  font-weight: 600;
}
.profile-form {
  max-width: 460px;
}
.form-actions {
  margin-top: 4px;
  display: flex;
  gap: 10px;
}

@media (max-width: 960px) {
  .profile-page {
    grid-template-columns: 1fr;
  }
}
</style>
