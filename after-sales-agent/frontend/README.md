# 登录模块前端

Vue 3 + TypeScript + Vite。代码放在现有 `frontend` 目录，直接连接 Backend 的真实认证接口，未内置演示密码或模拟登录开关。

## 启动

需要 Node.js 20.19+ 或 22.12+（推荐 24 LTS）。先按项目主 README 启动 PostgreSQL 与 Backend，后端默认监听 `127.0.0.1:8080`。另开一个终端：

```powershell
Set-Location A:\A_workSpace\AgentProjects\after-sales-agent\frontend
npm install
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`。使用已有数据库账号；本机初始化过的测试账号和密码在项目的 `data/local/test-accounts.properties`，请仅在自己电脑查看，不要放进前端代码。没有账号时参照 [数据库与测试数据](../docs/数据库与测试数据.md) 初始化。

后端端口不同时，可复制 `.env.example` 为 `.env.local` 并修改 `BACKEND_URL`，之后重启前端。这个变量由开发代理读取，不会成为浏览器中的 API 地址。每次联调固定使用同一个前端主机名，避免 `localhost` 与 `127.0.0.1` 的 Cookie 混用。

```powershell
npm test
npm run build
npm run preview
```

`test` 验证接口、CSRF、输入约束与状态切换；`build` 先执行 TypeScript 检查，再生成 `dist`；`preview` 在 `http://127.0.0.1:4173` 本地预览构建结果，并继续代理到 Backend。测试使用模拟响应，通过测试不能替代真实数据库和服务的联调。

## 代码结构

```text
frontend/
├─ public/favicon.svg
├─ src/
│  ├─ api/auth.ts                 # 四个认证接口、CSRF 获取与轮换
│  ├─ components/                 # 品牌标识、图标、请求反馈
│  ├─ features/auth/validation.ts # 登录输入约束
│  ├─ lib/http.ts                 # Cookie、超时、响应解包、统一错误
│  ├─ router/index.ts             # /login、/workspace 与访问保护
│  ├─ stores/auth.ts              # 当前用户、恢复、登录与退出状态
│  ├─ styles/                    # 主题变量、布局、移动端样式
│  ├─ types/auth.ts               # 后端 DTO 类型
│  ├─ views/LoginView.vue         # 登录表单和输入交互
│  ├─ views/WorkspaceView.vue     # 当前会话与账号信息
│  ├─ App.vue                    # 路由出口与初始加载
│  └─ main.ts                    # 应用入口
├─ tests/                        # Vitest 测试
└─ vite.config.ts                # /api 同源代理
```

目前登录状态很小，使用 Vue 的 `reactive` 管理即可。组件只负责交互，接口层负责认证协议，后续订单、工单功能可沿用 `api/`、`features/`、`views/` 的划分。

## 登录调用顺序

1. 刷新页面或直接访问 `/workspace`：先 `GET /api/v1/auth/me`；200 恢复身份，401 显示登录页。初次检查期间显示加载提示。
2. 提交表单：在前端校验账号 3–64 字符、密码 8–128 字符且 UTF-8 不超过 72 字节。账号去掉首尾空格，密码保持原样。
3. `GET /auth/csrf` 获取响应中的 `headerName` 和 `token`，带该请求头 `POST /auth/login`。
4. 登录后重新获取 CSRF，再请求 `/auth/me` 确认身份，跳转工作台。密码输入随本次提交结束而清空。
5. 退出前重新获取 CSRF，`POST /auth/logout` 接受 204 空响应；成功或 401 后清空当前用户，回到登录页。网络错误时保留身份展示并明确报错，不声称退出成功。
6. 回到工作台标签页或点击“重新核验会话”时调用 `/me`。会话过期自动返回登录页；网络或 503 错误保留上次身份并显示等待核验。

CSRF 403 拒绝只自动重试一次；密码错误、普通权限错误和网络故障不重放写请求。HttpOnly Session Cookie 由浏览器管理；用户、密码、CSRF 和 Session 标识均不写入 localStorage/sessionStorage。前端路由保护仅用于页面交互，最终身份和权限仍由 Backend 校验。

错误提示区兼容 Backend 当前 `detials.requestId` 的字段拼写，便于追查请求，但不会显示服务端内部异常。

## 人工联调清单

- 未登录直接访问 `/workspace` 应跳到 `/login`。
- 密码错误应提示账号或密码不正确，不显示工作台；修正后能够继续登录。
- 登录后显示真实用户名、角色与 UUID，刷新页面后仍可恢复。
- 普通客户与管理员分别显示对应角色；页面不据此替代后端授权。
- 退出后刷新或直接打开工作台仍回到登录；另一个标签页退出后，切回工作台能够发现失效。
- 关闭 Backend 时提示连接或服务错误；接口 401、403、429、503 分别处理。
- 手机宽度可以完整操作表单；可用键盘提交、切换显示密码，错误字段有可访问提示。

当前范围仅为登录和会话管理，工作台里的咨询、订单工单、知识栏目均标记“待接入”，不请求尚未实现的业务接口，也不提供无后端支持的注册或找回密码入口。

## 部署说明

生产部署时，将 `dist` 作为静态站点，把 `/api/` 同源反向代理到 Backend，并将前端页面路由回退到 `index.html`。Vite 代理只用于本地开发/预览。Backend 当前安全规则是 `anyRequest().denyAll()`，因此不能直接把 `dist` 拷入 Spring 静态目录而不调整资源放行规则。HTTPS 环境继续使用后端的 Secure、HttpOnly Cookie 配置。

本次另修正了 `backend/common/ApiExceptionHandler.java` 的认证异常导入，使用 Spring Security 的 `AuthenticationException`，使密码错误能进入已有的 401 响应逻辑。
