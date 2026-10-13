<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  NModal, NForm, NFormItem, NInput, NButton, NSpace, useMessage, type FormInst, type FormRules,
} from 'naive-ui'
import { updatePassword } from '@/api/system/auth'

/**
 * 修改密码弹窗：校验旧密码 → 提交新密码。
 * 成功后后端会吊销当前会话，由父组件（SidebarUser）统一执行登出跳转。
 */
const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [value: boolean]; success: [] }>()

const message = useMessage()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: ['blur', 'input'] }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: ['blur', 'input'] },
    { min: 4, max: 32, message: '新密码长度需在 4-32 位之间', trigger: ['blur', 'input'] },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: ['blur', 'input'] },
    {
      validator: (_rule, value: string) => {
        if (!value) return true
        return value === form.newPassword || new Error('两次输入的密码不一致')
      },
      trigger: ['blur', 'input'],
    },
  ],
}

function resetForm() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.restoreValidation()
}

function handleClose() {
  emit('update:show', false)
  resetForm()
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await updatePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    message.success('密码修改成功，请重新登录')
    handleClose()
    emit('success')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="修改密码"
    :style="{ width: '440px', maxWidth: '94vw' }"
    :bordered="false"
    :mask-closable="false"
    @update:show="(v) => !v && handleClose()"
    @after-leave="resetForm"
  >
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="84" size="medium">
      <n-form-item label="旧密码" path="oldPassword">
        <n-input
          v-model:value="form.oldPassword"
          type="password"
          show-password-on="click"
          placeholder="请输入旧密码"
          @keyup.enter="handleSubmit"
        />
      </n-form-item>
      <n-form-item label="新密码" path="newPassword">
        <n-input
          v-model:value="form.newPassword"
          type="password"
          show-password-on="click"
          placeholder="4-32 位新密码"
          @keyup.enter="handleSubmit"
        />
      </n-form-item>
      <n-form-item label="确认密码" path="confirmPassword">
        <n-input
          v-model:value="form.confirmPassword"
          type="password"
          show-password-on="click"
          placeholder="再次输入新密码"
          @keyup.enter="handleSubmit"
        />
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="end" :size="10">
        <n-button :disabled="loading" @click="handleClose">取消</n-button>
        <n-button type="primary" :loading="loading" @click="handleSubmit">确认修改</n-button>
      </n-space>
    </template>
  </n-modal>
</template>
