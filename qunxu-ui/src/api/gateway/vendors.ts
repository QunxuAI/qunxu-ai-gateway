/**
 * 模型开发商识别与品牌图标映射。
 *
 * <p>逻辑模型数据本身不含「开发商」字段，这里按模型名启发式推断（与后端
 * {@code CapabilityDetector} 同一思路）：命中关键字则归入对应开发商，未命中返回
 * {@code null}，由调用方回退到能力图标。</p>
 *
 * <p>图标为下载到本地 {@code src/assets/vendor-icons/} 的官方品牌 SVG（已填品牌色），
 * 离线可用、不依赖外网 CDN。新增开发商时：下载 SVG 到该目录 → 在 {@link VENDORS}
 * 追加一条（含匹配关键字）即可。</p>
 */

import openaiIcon from '@/assets/vendor-icons/openai.svg'
import deepseekIcon from '@/assets/vendor-icons/deepseek.svg'
import zhipuIcon from '@/assets/vendor-icons/zhipu.svg'
import moonshotIcon from '@/assets/vendor-icons/moonshot.svg'
import qwenIcon from '@/assets/vendor-icons/qwen.svg'
import minimaxIcon from '@/assets/vendor-icons/minimax.svg'
import anthropicIcon from '@/assets/vendor-icons/anthropic.svg'
import googleIcon from '@/assets/vendor-icons/google.svg'
import metaIcon from '@/assets/vendor-icons/meta.svg'
import mistralIcon from '@/assets/vendor-icons/mistral.svg'
import baiduIcon from '@/assets/vendor-icons/baidu.svg'
import sparkIcon from '@/assets/vendor-icons/spark.svg'
import doubaoIcon from '@/assets/vendor-icons/doubao.svg'
import hunyuanIcon from '@/assets/vendor-icons/hunyuan.svg'
import alibabaIcon from '@/assets/vendor-icons/alibaba.svg'

export interface VendorMeta {
  /** 开发商唯一标识（文件名 / key） */
  key: string
  /** 开发商展示名 */
  name: string
  /** 品牌图标资源 */
  icon: string
  /** 品牌主色（图标色，也用于浅色底） */
  color: string
  /** 模型名匹配关键字（小写包含匹配，按 VENDORS 顺序先命中先返回） */
  keywords: string[]
}

/** 匹配优先级：越靠前越先判定，具体关键字排前面，避免通用词误命中。 */
export const VENDORS: VendorMeta[] = [
  {
    key: 'openai',
    name: 'OpenAI',
    icon: openaiIcon,
    color: '#10A37F',
    keywords: ['gpt', 'o1-', 'o3-', 'o4-', 'chatgpt', 'text-embedding', 'whisper', 'dall-e', 'dalle', 'gpt-image', 'tts-1'],
  },
  {
    key: 'deepseek',
    name: 'DeepSeek',
    icon: deepseekIcon,
    color: '#4D6BFE',
    keywords: ['deepseek'],
  },
  {
    key: 'zhipu',
    name: '智谱',
    icon: zhipuIcon,
    color: '#0B63F6',
    keywords: ['glm', 'chatglm', 'cogview', 'cogvideox', 'zhipu'],
  },
  {
    key: 'moonshot',
    name: '月之暗面',
    icon: moonshotIcon,
    color: '#6C5CE7',
    keywords: ['kimi', 'moonshot'],
  },
  {
    key: 'qwen',
    name: '通义千问',
    icon: qwenIcon,
    color: '#6950EF',
    keywords: ['qwen', 'tongyi'],
  },
  {
    key: 'alibaba',
    name: '阿里通义万相',
    icon: alibabaIcon,
    color: '#FF6A00',
    keywords: ['wan2', 'wanx', 'wan-', 'wan_'],
  },
  {
    key: 'minimax',
    name: 'MiniMax',
    icon: minimaxIcon,
    color: '#6E59F5',
    keywords: ['minimax', 'abab'],
  },
  {
    key: 'anthropic',
    name: 'Anthropic',
    icon: anthropicIcon,
    color: '#D97757',
    keywords: ['claude'],
  },
  {
    key: 'google',
    name: 'Google',
    icon: googleIcon,
    color: '#4285F4',
    keywords: ['gemini', 'gemma', 'palm2', 'text-bison', 'chat-bison', 'imagen'],
  },
  {
    key: 'meta',
    name: 'Meta',
    icon: metaIcon,
    color: '#0668E1',
    keywords: ['llama', 'llama2', 'llama3', 'llama4'],
  },
  {
    key: 'mistral',
    name: 'Mistral',
    icon: mistralIcon,
    color: '#FF7000',
    keywords: ['mistral', 'mixtral', 'codestral'],
  },
  {
    key: 'baidu',
    name: '百度',
    icon: baiduIcon,
    color: '#2932E1',
    keywords: ['ernie', 'wenxin', 'qianfan'],
  },
  {
    key: 'spark',
    name: '讯飞星火',
    icon: sparkIcon,
    color: '#1F64FF',
    keywords: ['spark', 'generalv3', 'xf-'],
  },
  {
    key: 'doubao',
    name: '豆包',
    icon: doubaoIcon,
    color: '#2E5BFF',
    keywords: ['doubao', 'seedream', 'volcengine'],
  },
  {
    key: 'hunyuan',
    name: '腾讯混元',
    icon: hunyuanIcon,
    color: '#1475FF',
    keywords: ['hunyuan'],
  },
]

/**
 * 根据模型名推断开发商。
 *
 * @returns 命中的开发商元数据；无法识别返回 {@code null}（调用方回退能力图标）
 */
export function detectVendor(modelName?: string | null): VendorMeta | null {
  if (!modelName) return null
  const name = modelName.trim().toLowerCase()
  if (!name) return null
  for (const vendor of VENDORS) {
    if (vendor.keywords.some((kw) => name.includes(kw))) {
      return vendor
    }
  }
  return null
}
