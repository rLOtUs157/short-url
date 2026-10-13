# 短链接服务（Short URL Service）

面向营销推广场景的短链接服务：把长链接变短，方便**传播、统计点击、动态换指向**。核心链路上做了**Redis 缓存、布隆过滤器防穿透、互斥锁防击穿、JWT 登录鉴权、多用户数据隔离**。

技术栈：`Spring Boot 3.3.5` · `Spring Data JPA` · `MySQL 8` · `Redis` · `JWT (jjwt 0.12.6)` · `BCrypt` · 原生 HTML 前端

---

## 功能特性

- **短链生成**：8 位 base62 随机短码 + 冲突重试；`GET /s/{code}` 302 跳转到长链接
- **点击统计**：Redis `INCR` 实时计数，`@Scheduled` 定时回写 MySQL，Redis 重启不丢数据
- **Redis 缓存**：缓存优先，命中不查库；空值占位缓存挡重复穿透
- **布隆过滤器防穿透**：第一层用极小内存判断"短码肯定不存在"，拦截无效请求（不存在的短码连 Redis / 数据库都不碰）
- **互斥锁防击穿**：热点短码缓存过期瞬间，用 `SETNX` 只放一个请求查库回填，其余等待后重查
- **JWT 登录鉴权**：BCrypt 加密密码；`jti` 黑名单实现"退出登录立即失效"（TTL=token 剩余有效期，Redis 自动清理）
- **多用户数据隔离**：每个账号只能看到/管理自己生成的短链

---

## 架构分层（跳转链路）

```
请求 → ① 布隆过滤器（判断"肯定不存在"→ 直接拦截）
      → ② Redis 缓存（命中直接返回）
      → ③ MySQL（未命中查库并回填缓存 / 写空值缓存）
```

三层都挡住才真正查库，数据库只承担最真实、最必要的查询。

---

## 快速开始

### 环境要求
- JDK 17+（本机 JDK 21，项目语言级别 17）
- MySQL 8、Redis

### 步骤

1. **建库**（MySQL）：

   ```sql
   CREATE DATABASE shorturl DEFAULT CHARACTER SET utf8mb4;
   ```

2. **启动 Redis**：

   ```
   redis-server
   ```

3. **配置**：编辑 `src/main/resources/application.properties`
   - 数据库密码默认 `123456`，可通过环境变量覆盖：`DB_PASSWORD`
   - JWT 密钥 `jwt.secret`（生产请用环境变量注入，勿明文提交）

4. **运行**：

   ```
   mvn spring-boot:run
   ```

   或在 IDEA 中直接运行 `ShortUrlApplication`（端口 8081）。

5. **访问**：浏览器打开 `http://localhost:8081`

---

## 接口说明

| 方法 | 路径 | 说明 | 是否需登录 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册（BCrypt 加密） | 否 |
| POST | `/api/auth/login` | 登录，返回 `token` | 否 |
| POST | `/api/auth/logout` | 登出，撤销 token（加入黑名单） | 是 |
| POST | `/api/short-url` | 生成短链，body `{"longUrl":"https://..."}` | 是 |
| GET | `/s/{code}` | 302 跳转长链接 | 否 |
| GET | `/api/my-urls` | 当前用户自己的短链列表 | 是 |
| GET | `/api/click/{code}` | 查询点击次数 | 是 |

登录后请求需携带请求头：`Authorization: Bearer <token>`。

**生成短链响应示例：**

```json
{
  "code": 0,
  "shortUrl": "http://localhost:8081/s/Ab3xY9Kp",
  "shortCode": "Ab3xY9Kp"
}
```

---

## 项目结构

```
short-url
└── src/main
    ├── java/com/example/shorturl
    │   ├── ShortUrlApplication.java      # 启动类（@EnableScheduling）
    │   ├── config/WebConfig.java         # 注册拦截器，放行 /s/** 与 /api/auth/**
    │   ├── common/JwtInterceptor.java    # JWT 校验 + 黑名单检查
    │   ├── common/TokenBlacklist.java    # Redis 黑名单（登出撤销）
    │   ├── controller/AuthController.java# 注册/登录/登出
    │   ├── controller/ShortUrlController.java
    │   ├── service/ShortUrlService.java  # 核心逻辑：缓存/布隆/锁/点击落库
    │   ├── repository/
    │   ├── entity/UrlMapping.java, User.java
    │   └── util/JwtUtil.java, HashBloomFilter.java, ShortCodeGenerator.java
    └── resources
        ├── application.properties
        └── static/index.html             # 前端单页
```

---

## Redis Key 设计

| Key | 用途 | 有效期 |
|---|---|---|
| `short:url:{code}` | 短码 → 长链接 | 1 天（空值占位 `NIL` 2 分钟） |
| `short:click:{code}` | 点击计数（INCR） | 无（定时落库） |
| `short:lock:{code}` | 重建缓存互斥锁（SETNX） | 5 秒 |
| `short:dirty` | 待落库短码集合 | 无（定时清理） |
| `short:jwt:blacklist:{jti}` | 已登出 token 黑名单 | = token 剩余有效期 |
