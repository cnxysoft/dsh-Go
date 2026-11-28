# Fork Changelog (`cnxysoft/dsh-Go`)

本文件记录 `cnxysoft/dsh-Go`（默认分支 `fork`）相对上游 [`ccch1mneyyy/dsh-Go`](https://github.com/ccch1mneyyy/dsh-Go) 新增与修改的全部内容。`main` 分支保持与上游纯净同步；上游不包含本文件，以保持后续同步上游时的零合并冲突。

- **当前上游基线**：`v1.4.1`（`3b9fbbbb5`，于 `2026-11-28` 合并 `62cc3f17f`）
- **分支约定**：`main` 纯净跟踪上游 `upstream/main`；`fork` 为唯一长期维护的 Fork 默认分支
- **并行测试包名**：`io.github.ccch1mneyyy.dsh_app.test`（可与官方正式版并存）
- **Fork 构建入口**：GitHub Actions → `Android fork APK`（`workflow_dispatch` 手动触发或推送 `apk-*` tag）

---

## 核心特性总览

### 1. Android Shell 直连 Root、审批策略、请求关联与锁屏续跑
- 新增显式连接的 **Android Root Shell** 后端（无需依赖 PRoot 内绕路，支持冷启动自动重连与独立的重连卡片）。
- **4 档审批策略**：支持「每次确认 / 限时有效 / 重启前有效 / 放行」及对应过期收尾。
- **本机输入请求关联（`android-phone-prompt-correlation.diff` + `LocalPromptReceipts.kt`）**：将本机 App 输入框发送的用户消息 `rpcId` 透传到引擎 `phoneCaller.promptRequestId`，与原生回执核对关联，确认调用是否对应本机当轮输入。
- **可配置执行限额**：支持调整单次命令字节上限（2–64 KB）、超时上限（60 秒–10 分钟）、输出上限（64–512 KB，Shizuku 侧维持 Binder 256 KB 硬顶），同步放宽 `ShellWire.MAX_FRAME` 至 2 MB 与 `PhoneCore` 动态调用预算。
- **息屏 / 锁屏保活与执行开关**：息屏时保留仅含 Android Shell 的任务会话，新增「锁屏时允许执行 Android Shell」开关（UI 操控仍受锁屏限制）。

### 2. 实验性原生 Root `chroot` 引擎后端与多目录挂载
- 新增实验性 **原生 Root `chroot` 后端**，并自动清理过期宿主挂载点。
- 允许 Root `chroot` 引擎活跃时 `uid 0` 访问本机 HTTP 桥；优化 `chroot` 清理速度并改用 `toybox find -user 0` 修复文件属主。
- 支持 **多目录宿主工作区挂载**，方便同时向引擎暴露多个手机目录。
- 新增 **托管 `AGENTS.md` 开关**，可按需控制是否自动写入/接管工作区 `AGENTS.md`。

### 3. 手机操控提速（设备侧筛选 + 连点链 + 智能等待）
在不新增工具名（保持 `PhoneToolCatalog` 原有 16 个工具不变、兼容上游 schema 与单测）的前提下，为既有工具增强可选参数：
- **设备侧关键词筛选（`screen` / `tap` / `open` 的 `query` 参数）**：在手机端按 `|` 或空格分隔的关键词过滤语义树并保留所属区块/列表标题；零命中或部分命中时显式附带 `命中 X/Y 行` 提示，避免误判为空屏。
- **多步连点链（`tap` / `open` 的 `then` 参数）**：单次调用最多连续点 5 步，每步完整走原有 `SafetyGate`、`policyAllowed`、`actionSourcesAllowed` 与 `returnedScreen` 安全边界；支持 `文案#N` / `文案#-1` 序号消歧与每步最多 2.4 秒动态轮询。
- **智能等待（`screen` / `tap` / `open` 的 `wait_for` 参数）**：最多轮询 5 秒等待正文行出现目标关键词（不误匹配顶部 App 标题行），超时明确提示并返回当前屏。
- **安全回执与未过滤恢复屏**：`then` 中途停止或 `wait_for` 超时时，强制原样返回**未过滤**的当前屏，避免确认/弹窗按钮被终点 `query` 筛掉；中途停止回执逐条列出 `1.✓ / 2.✗` 并明确提示「已执行的步骤不会撤销，请勿重跑已完成步骤」。
- **`open` 回执收口**：最终前台包名确认命中目标包名时，将第 1 步精简为 `已打开「<应用名>」`；若被权限弹窗或系统拦截导致包名不符，保留上游原提示以便排查。

### 4. Android DSH 运行时兼容与模型请求图片压缩
两项仅作用于 Android 运行时的补丁（不改动鸿蒙运行时）：
- **`0.2.2-rc.3` 历史会话事件向下兼容（`android-session-team-mode.diff`）**：在内置 DSH `0.2.2-rc.1` 的会话恢复白名单中识别 `0.2.2-rc.3` 写入的 `team/mode`（智能体团队开关）只读日志事件，使从 `rc.3` 迁移的历史会话可以正常加载恢复（不启用团队模式功能，也不允许写入新事件）。
- **模型请求图片压缩规格收紧（`android-request-preview.diff`）**：将 Android 发给大模型的请求图片长边上限从 `4096px` 压至 `1600px`、单图体积上限压至 `<= 1 MB`，并去掉小图直传分支统一走编码压缩阶梯（`request-image-android-p1`），降低传图体积与失败率。

### 5. 界面与用量统计修复
- 修复十亿级 token 计数显示溢出：`1M–9.99M` 保留 2 位小数、`10M–99.9M` 保留 1 位小数、`100M–999M` 取整、`>= 1000M` 格式化为 `B`（如 `3.47B`）。
- 用量统计数值外包 `FittedBox(BoxFit.scaleDown)`，防止超长数值与相邻列重叠。

### 6. 自检与后台屏稳定性
- 关闭自检启动闪屏预览，稳定后台虚拟屏探测时序。
- 修复并行测试包自检与自检锁泄漏、收尾清理问题。

### 7. Fork 独立打包与 CI 工作流
- 独立 `.github/workflows/android-fork-apk.yml`：同步构建 runtime 与 arm64 APK，支持可选 Fork 签名密钥与固定 NDK 版本。
- 改为 `workflow_dispatch` 手动触发（加 `apk-*` tag 回退），避免公开 Fork 频繁自动触发构建被平台限流。

---

## 变更日志

### 2026-11-28
- **`ed3c275c7`** — `open` 结合 `then` / `wait_for` / `query` 时，若最终前台包名等于目标包名则将第 1 步回执精简为 `已打开「…」`，被弹窗或系统拦住时保留上游未确认提示；补充单元与端到端测试。
- **`8c3ae9877`** — 为 `open` 接入 `then` / `wait_for` / `query`，为 `screen` / `tap` 接入 `wait_for`（5 秒智能轮询）；`then` 每步轮询提升至 2.4 秒；中途停止或超时原样返回未过滤当前屏并附带防重跑提示。
- **`13e387f02`** — 新增 `PhoneFastActions.kt`，为 `screen` / `tap` 增加设备侧 `query` 筛选与 `tap(then)` 多步连点（含 `#N/#-1` 消歧与逐条执行回执）。
- **`fd9b8ae57`** — 修复用量面板十亿级 token 显示（`B` 单位格式化 + `FittedBox` 防重叠）。
- **`4836e5325`** — 将 `Android fork APK` 工作流切换为手动 `workflow_dispatch` 与 `apk-*` tag 触发，并修正 hosted runner `sdkmanager` 路径。
- **`62cc3f17f`** — 零冲突合并上游 **`v1.4.1`**（`3b9fbbbb5`，含 `tokens/sec`、远程后台保护、DS Bot 中继、更新检查修复等 35 项上游更新），同步重新生成 l10n。
- **`becb0ac6d` / `a31423696`** — 新增 `android_shell` 可配置限额（命令大小 / 超时 / 输出上限）与锁屏执行开关，并同步在 `PhoneCore.prepare` 中透传校验。
- **`ff19862ac` / `dbf634e55` / `d19cee233`** — 新增实验性原生 Root `chroot` 引擎后端、过期挂载点清理、`uid 0` 桥接放行，以及基于 `toybox find -user 0` 的清理/属主修复加速。

### 2026-11-27
- **`176256d30`** — 支持 Android 宿主多目录工作区挂载。
- **`acc4cad2a`** — 关闭自检闪屏预览并稳定虚拟屏探测时序。
- **`d2c9e3e42`** — 新增托管 `AGENTS.md` 开关，修复自检锁泄漏与收尾清理。
- **`98129f53b`** — 息屏时保持 Android Shell 存活，并修复并行测试包自检。
- **`87d87b92c`** — 新增 Android Shell 4 档审批策略（含定时与重启前自动失效）。
- **`af95fc644` / `bfba88628`** — 支持 Android Shell 冷启动自动重连、修复重连竞态、拆分重连卡片，新增 `android-phone-prompt-correlation.diff` + `LocalPromptReceipts` 本机输入关联，并支持可选 Fork 签名。

### 2026-11-26
- **`4d1597263` / `7dfba1cdb`** — 新增 `android-session-team-mode.diff`（在 DSH `0.2.2-rc.1` 恢复 `0.2.2-rc.3` 含 `team/mode` 事件的历史会话）与 `android-request-preview.diff`（Android 请求图片压至 `1600px / <=1MB`），并配置独立测试包构建。
- **`44a184da8` / `cf456795c` / `fdb7f1b34` / `1d240a75e` / `319f9ccbc` / `23ea5efed`** — 初始引入显式连接的 Android Root Shell 后端与 Fork 独立 APK CI 构建链路。

---

## 合并上游提示

为降低后续同步上游时的合并成本，本 Fork 尽量将核心增量代码收口在独立文件与最小接线点：
- **独立文件（零冲突）**：`FORK_CHANGELOG.md`、`.github/workflows/android-fork-apk.yml`、`PhoneFastActions.kt`、`PhoneFastActionsTest.kt`、`RootBackend.kt`、`runtime/patches/android-*.diff` 等。
- **高频交汇接线点（合上游时重点留意）**：`PhoneCore.kt`、`PhoneToolCatalog.kt`、`PhonePlugin.kt`、`usage_sheet.dart`、`runtime/patches/pins.json`、`app/lib/l10n/parts/*.arb`（合并后需重跑 `python3 app/tool/l10n_merge.py`）。
