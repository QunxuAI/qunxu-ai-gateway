# ===== 多阶段构建：Node 构建前端 + Nginx 托管 =====
# 构建阶段
FROM node:22-alpine AS build

WORKDIR /app

# 先拷贝依赖清单，最大化利用 Docker 层缓存
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# 拷贝源码并构建（Vite 产物输出到 dist/）
COPY . .
RUN npm run build

# ===== 运行阶段：Nginx 托管静态产物并反代后端 =====
FROM nginx:1.27-alpine

# 自定义站点配置：静态托管 + /admin-api 反代到后端服务
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 拷贝构建产物
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --retries=30 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
