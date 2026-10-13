#!/usr/bin/env bash
# QunxuAI-Gateway 一体化镜像启动入口
# 职责：确保 qunxu_ai 库存在（首次启动自动建库 + 建表 + 种子数据），随后由 supervisor 拉起全部进程

set -e

MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
MYSQL_DATABASE="${MYSQL_DATABASE:-qunxu_ai}"
MYSQL_DATA_DIR=/var/lib/mysql
REDIS_DATA_DIR=/var/lib/redis
MYSQL_SOCK=/var/run/mysqld/mysqld.sock

log() { echo "[entrypoint] $*"; }

# ============ 0. 准备运行目录 ============
mkdir -p /var/run/mysqld /app/logs "$REDIS_DATA_DIR"
chown -R mysql:mysql /var/run/mysqld
chown -R redis:redis "$REDIS_DATA_DIR"

# ============ 1. 确保 MySQL 数据目录已初始化 ============
if [ ! -d "$MYSQL_DATA_DIR/mysql" ]; then
  log "MySQL 数据目录为空，执行初始化..."
  mysqld --initialize-insecure --user=mysql --datadir="$MYSQL_DATA_DIR" 2>/dev/null \
    || { log "mysqld --initialize-insecure 失败，尝试 mysqld --initialize"; mysqld --initialize --user=mysql --datadir="$MYSQL_DATA_DIR"; }
fi

# ============ 2. 启动临时 mysqld 并确保 qunxu_ai 库存在 ============
log "启动临时 mysqld..."
mysqld --user=mysql --datadir="$MYSQL_DATA_DIR" --socket="$MYSQL_SOCK" &
MYSQLD_PID=$!

for i in $(seq 1 60); do
  if mysqladmin --socket="$MYSQL_SOCK" ping >/dev/null 2>&1; then
    log "MySQL 就绪 (${i}s)"
    break
  fi
  [ "$i" = "60" ] && { log "MySQL 启动超时"; exit 1; }
  sleep 1
done

# 探测 root 是否已有密码（apt 初始化后为无密码）
ROOT_AUTH=(-uroot --socket="$MYSQL_SOCK")
if ! mysql "${ROOT_AUTH[@]}" -e "SELECT 1" >/dev/null 2>&1; then
  ROOT_AUTH=(-uroot -p"${MYSQL_PASSWORD}" --socket="$MYSQL_SOCK")
fi

# 确保 root 密码与预期一致（幂等）
mysql "${ROOT_AUTH[@]}" <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL
ROOT_AUTH=(-uroot -p"${MYSQL_PASSWORD}" --socket="$MYSQL_SOCK")

# 建库（幂等）
mysql "${ROOT_AUTH[@]}" -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 首次（库中无业务表时）执行 schema + data
TABLE_COUNT=$(mysql "${ROOT_AUTH[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}';" 2>/dev/null || echo 0)
if [ "$TABLE_COUNT" = "0" ]; then
  log "初始化数据库结构（schema.sql + data.sql）..."
  mysql "${ROOT_AUTH[@]}" "${MYSQL_DATABASE}" < /docker-entrypoint-initdb.d/01-schema.sql
  mysql "${ROOT_AUTH[@]}" "${MYSQL_DATABASE}" < /docker-entrypoint-initdb.d/02-data.sql
  log "数据库初始化完成"
else
  log "数据库 ${MYSQL_DATABASE} 已存在 ${TABLE_COUNT} 张表，跳过初始化 SQL"
fi

# 关闭临时 mysqld，交由 supervisor 接管
log "关闭临时 mysqld，交由 supervisor 接管"
mysqladmin "${ROOT_AUTH[@]}" shutdown
wait "$MYSQLD_PID" 2>/dev/null || true

# ============ 3. 导出后端连接环境变量（一体化内默认本机） ============
export MYSQL_MASTER_URL="${MYSQL_MASTER_URL:-jdbc:mysql://127.0.0.1:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}"
export MYSQL_SLAVE_URL="${MYSQL_SLAVE_URL:-jdbc:mysql://127.0.0.1:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}"
export MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-}"
export GATEWAY_SECURITY_ENABLED="${GATEWAY_SECURITY_ENABLED:-false}"
export GATEWAY_CRYPTO_SECRET="${GATEWAY_CRYPTO_SECRET:-qunxu-default-dev-secret-change-me}"

# ============ 4. 交由 supervisor 启动全部进程 ============
log "启动全部服务（MySQL / Redis / 后端 / Nginx）..."
exec /usr/bin/supervisord -c /etc/supervisor/supervisord.conf
