<template>
  <div class="login-page">
    <div class="login-card">
      <!-- Logo -->
      <div class="login-logo">
        <div class="logo-mark">
          <img :src="logoUrl" alt="Qunxu AI" class="logo-img" />
        </div>
        <div class="logo-text">
          <strong>Qunxu AI</strong>
          <small>群绪AI</small>
        </div>
      </div>

      <h2 class="login-title">欢迎回来</h2>
      <p class="login-subtitle">登录管理后台，开始你的工作</p>

      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="large">
        <n-form-item label="账号" path="username">
          <n-input v-model:value="form.username" placeholder="请输入账号" @keyup.enter="handleLogin">
            <template #prefix>
              <n-icon :component="PersonOutline" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item label="密码" path="password">
          <n-input
            v-model:value="form.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
            @keyup.enter="handleLogin"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item label="验证码" path="captchaCode">
          <div class="captcha-row">
            <n-input v-model:value="form.captchaCode" placeholder="输入计算结果" @keyup.enter="handleLogin">
              <template #prefix>
                <n-icon :component="ScanOutline" />
              </template>
            </n-input>
            <div class="captcha-box" title="点击刷新" @click="loadCaptcha">
              {{ captcha.expression || '加载中…' }}
              <n-icon :size="14" class="captcha-refresh"><RefreshOutline /></n-icon>
            </div>
          </div>
        </n-form-item>

        <n-button type="primary" block size="large" :loading="loading" @click="handleLogin">
          登 录
        </n-button>
      </n-form>

      <div class="login-tips">
        <span>演示账号：admin / admin123（全权限）</span>
        <span>test / 123456（受限权限，可体验按钮级权限）</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NForm, NFormItem, NInput, NButton, NIcon, useMessage, type FormInst, type FormRules } from 'naive-ui'
import { LockClosedOutline, PersonOutline, RefreshOutline, ScanOutline } from '@vicons/ionicons5'
import { getCaptcha, type CaptchaRespVO } from '@/api/system/auth'
import { useUserStore } from '@/stores/user'
import logoUrl from '@/assets/logo.svg'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const message = useMessage()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const captcha = reactive<CaptchaRespVO>({ uuid: '', expression: '' })

const form = reactive({ username: '', password: '', captchaCode: '' })

const rules: FormRules = {
  username: { required: true, message: '请输入账号', trigger: ['input', 'blur'] },
  password: { required: true, message: '请输入密码', trigger: ['input', 'blur'] },
}

async function loadCaptcha() {
  try {
    const res = await getCaptcha()
    captcha.uuid = res.uuid
    captcha.expression = res.expression
    form.captchaCode = ''
  } catch {
    /* 验证码加载失败不阻塞登录 */
  }
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await userStore.loginAction({
      username: form.username,
      password: form.password,
      captchaUuid: captcha.uuid,
      captchaCode: form.captchaCode,
    })
    message.success('登录成功')
    // 跳转到来源页，否则进入首页（守卫会自动加载动态路由）
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (e) {
    message.error((e as Error).message || '登录失败')
    loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1000px 500px at 80% -10%, rgba(99, 102, 241, 0.18), transparent 60%),
    radial-gradient(800px 400px at 0% 110%, rgba(236, 72, 153, 0.12), transparent 55%),
    #f6f7f9;
  padding: 16px;
}
.login-card {
  width: 400px;
  max-width: 100%;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 24px 64px rgba(16, 24, 40, 0.1);
  padding: 36px 36px 28px;
}
.login-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}
.logo-mark {
  width: 42px;
  height: 42px;
  flex-shrink: 0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 6px 16px rgba(99, 102, 241, 0.4);
}
.logo-img {
  display: block;
  width: 100%;
  height: 100%;
}
.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.logo-text strong {
  font-size: 17px;
  font-weight: 700;
  color: #1f2937;
}
.logo-text small {
  color: #98a2b3;
  font-size: 10px;
  letter-spacing: 1.5px;
  font-weight: 500;
}
.login-title {
  margin: 0 0 4px;
  font-size: 21px;
  font-weight: 700;
  color: #1f2937;
}
.login-subtitle {
  margin: 0 0 22px;
  font-size: 13px;
  color: #98a2b3;
}
.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
}
.captcha-box {
  flex-shrink: 0;
  width: 118px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 10px;
  background: linear-gradient(135deg, #eef2ff, #f5f3ff);
  border: 1px solid #e4e4f0;
  color: #4f46e5;
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
  user-select: none;
}
.captcha-refresh {
  color: #a5a5bd;
}
.login-tips {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px dashed #e8e8f0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #98a2b3;
  line-height: 1.5;
}
</style>
