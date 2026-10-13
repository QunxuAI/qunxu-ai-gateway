import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = true

/** 字典类型 */
export interface DictType {
  id: number
  name: string
  type: string
  status: 0 | 1
  remark: string
  createTime: string
}
/** 字典数据项 */
export interface DictData {
  id: number
  dictType: string
  label: string
  value: string
  sort: number
  status: 0 | 1
  colorType: string
  remark: string
  createTime: string
}

let seq = 1
const MOCK_DICT_TYPES: DictType[] = [
  { id: seq++, name: '系统状态', type: 'common_status', status: 1, remark: '通用状态', createTime: '2026-01-01 09:00:00' },
  { id: seq++, name: '用户性别', type: 'user_gender', status: 1, remark: '性别', createTime: '2026-01-01 09:10:00' },
  { id: seq++, name: '通知类型', type: 'notice_type', status: 1, remark: '公告类型', createTime: '2026-01-01 09:20:00' },
]

const MOCK_DICT_DATA: DictData[] = [
  { id: seq++, dictType: 'common_status', label: '开启', value: '1', sort: 1, status: 1, colorType: 'success', remark: '', createTime: '2026-01-01 09:00:00' },
  { id: seq++, dictType: 'common_status', label: '关闭', value: '0', sort: 2, status: 1, colorType: 'default', remark: '', createTime: '2026-01-01 09:00:00' },
  { id: seq++, dictType: 'user_gender', label: '男', value: '1', sort: 1, status: 1, colorType: 'info', remark: '', createTime: '2026-01-01 09:10:00' },
  { id: seq++, dictType: 'user_gender', label: '女', value: '2', sort: 2, status: 1, colorType: 'warning', remark: '', createTime: '2026-01-01 09:10:00' },
  { id: seq++, dictType: 'notice_type', label: '通知', value: '1', sort: 1, status: 1, colorType: 'info', remark: '', createTime: '2026-01-01 09:20:00' },
  { id: seq++, dictType: 'notice_type', label: '公告', value: '2', sort: 2, status: 1, colorType: 'warning', remark: '', createTime: '2026-01-01 09:20:00' },
]

/* ---------- 字典类型 ---------- */
export function getDictTypePage(query: { pageNo: number; pageSize: number; name?: string; type?: string }): Promise<{ list: DictType[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_DICT_TYPES.filter(
      (d) => (!query.name || d.name.includes(query.name)) && (!query.type || d.type.includes(query.type)),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/dict-type/page', { params: query })
}

export function createDictType(data: Partial<DictType>): Promise<number> {
  if (USE_MOCK) {
    MOCK_DICT_TYPES.push({ ...data, id: seq++, createTime: now() } as DictType)
    return mockDelay(seq - 1)
  }
  return request.post('/system/dict-type/create', data)
}

export function updateDictType(id: number, data: Partial<DictType>): Promise<boolean> {
  if (USE_MOCK) {
    const d = MOCK_DICT_TYPES.find((x) => x.id === id)
    if (d) Object.assign(d, data)
    return mockDelay(true)
  }
  return request.put('/system/dict-type/update', { id, ...data })
}

export function deleteDictType(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_DICT_TYPES.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_DICT_TYPES.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/dict-type/delete', { params: { id } })
}

/* ---------- 字典数据项 ---------- */
export function getDictDataPage(query: { pageNo: number; pageSize: number; dictType: string; label?: string }): Promise<{ list: DictData[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_DICT_DATA.filter(
      (d) => d.dictType === query.dictType && (!query.label || d.label.includes(query.label)),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/dict-data/page', { params: query })
}

export function createDictData(data: Partial<DictData>): Promise<number> {
  if (USE_MOCK) {
    MOCK_DICT_DATA.push({ ...data, id: seq++, createTime: now() } as DictData)
    return mockDelay(seq - 1)
  }
  return request.post('/system/dict-data/create', data)
}

export function updateDictData(id: number, data: Partial<DictData>): Promise<boolean> {
  if (USE_MOCK) {
    const d = MOCK_DICT_DATA.find((x) => x.id === id)
    if (d) Object.assign(d, data)
    return mockDelay(true)
  }
  return request.put('/system/dict-data/update', { id, ...data })
}

export function deleteDictData(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_DICT_DATA.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_DICT_DATA.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/dict-data/delete', { params: { id } })
}

function now(): string {
  return new Date().toISOString().slice(0, 16).replace('T', ' ')
}
