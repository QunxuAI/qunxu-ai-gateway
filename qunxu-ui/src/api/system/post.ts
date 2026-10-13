import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = true

export interface Post {
  id: number
  name: string
  code: string
  sort: number
  status: 0 | 1
  remark: string
  createTime: string
}
export interface PostPageQuery {
  pageNo: number
  pageSize: number
  name?: string
  code?: string
  status?: 0 | 1 | null
}

let seq = 1
const MOCK_POSTS: Post[] = [
  { id: seq++, name: '董事长', code: 'ceo', sort: 1, status: 1, remark: '', createTime: '2026-01-01 10:00:00' },
  { id: seq++, name: '研发工程师', code: 'dev', sort: 2, status: 1, remark: '', createTime: '2026-01-01 10:10:00' },
  { id: seq++, name: '测试工程师', code: 'qa', sort: 3, status: 1, remark: '', createTime: '2026-01-01 10:20:00' },
  { id: seq++, name: '市场专员', code: 'market', sort: 4, status: 1, remark: '', createTime: '2026-01-01 10:30:00' },
  { id: seq++, name: '财务专员', code: 'finance', sort: 5, status: 0, remark: '已停用', createTime: '2026-01-02 09:00:00' },
]

export function getPostPage(query: PostPageQuery): Promise<{ list: Post[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_POSTS.filter(
      (p) =>
        (!query.name || p.name.includes(query.name)) &&
        (!query.code || p.code.includes(query.code)) &&
        (query.status === null || query.status === undefined || p.status === query.status),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/post/page', { params: query })
}

export function getPostSimpleList(): Promise<Post[]> {
  if (USE_MOCK) return mockDelay([...MOCK_POSTS])
  return request.get('/system/post/simple-list')
}

export function createPost(data: Partial<Post>): Promise<number> {
  if (USE_MOCK) {
    MOCK_POSTS.push({ ...data, id: seq++, createTime: new Date().toISOString().slice(0, 16).replace('T', ' ') } as Post)
    return mockDelay(seq - 1)
  }
  return request.post('/system/post/create', data)
}

export function updatePost(id: number, data: Partial<Post>): Promise<boolean> {
  if (USE_MOCK) {
    const p = MOCK_POSTS.find((x) => x.id === id)
    if (p) Object.assign(p, data)
    return mockDelay(true)
  }
  return request.put('/system/post/update', { id, ...data })
}

export function deletePost(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_POSTS.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_POSTS.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/post/delete', { params: { id } })
}
