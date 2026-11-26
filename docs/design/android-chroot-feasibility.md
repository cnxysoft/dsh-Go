# Android：原生 chroot 作为可选引擎后端的可行性（2026-11-26）

**判断：值得做受控原型，不宜替换默认启动，也不应让 DSH、BC 或工具进程以 Android root 运行。** chroot 选择的是引擎的文件系统根，不是模型操作手机的授权方式。该调研不涉及鸿蒙。

## 已证明与未证明

- 在本次 SukiSU 真机实验中，通过已授权的 ADB shell 做最小准备，使用测试 APK 的 arm64 Ubuntu rootfs，在独立 mount namespace 中启动 chroot，运行工作负载前降到 Go 的 App 数字 UID。隔离副本的 Host 读到 118,842 的日志末序号，BC 折后上游请求 565 项/1 图，服务端返回 200 并写入 assistant/message 和 turn/end。临时目录和测试进程清零。这证明该内核、rootfs、Node 和此插件组合可以工作，但**不等同于从 App 进程申请 Root、走 Android 前台服务和生命周期**；从 su 启动后设 UID 也不证明 SELinux 域与普通 App 相同。该机的 mount namespace 可用，但同时申请 PID namespace 的实验失败，不应把 PID namespace 当必备功能。
- 现有 `runtime/DshProcess.kt`（位于 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/` 下）已负责 PRoot / Direct / tawcroot 的选择和退出。`TawcrootLaunch.kt` 是路径翻译，不是真 chroot。能够复用 APK 的引擎内容，但不能假设给 PRoot 准备的目录布局、硬链接、副本、启动参数或停止协议都能原封照搬。
- 没有同机同输入的测速证明 chroot 会让 BC 规划更快或减少 V8 活对象；它不能当作 2 GiB 堆耗尽的修复，也不会自动解决 Android 虚拟屏权限。

## 最小可验收的产品形态

1. 保持现有默认。用户选择“Root chroot（实验）”时才通过 Android Root 管理器申请授权。引导端只支持固定的准备、降权启动与停止操作，不接受模型、插件或网络传来的任意 root 命令。验证实际 UID、组、能力、SELinux 上下文以及宿主可见文件；仅 UID 对齐并不够。
2. 私有挂载不向宿主传播。只暴露运行需要的数据与设备；不把整棵 Android `/data`、管理器、调试套接字或系统设备树交给访客。不依赖 chroot 本身做安全隔离。工作区、附件与既有会话使用同一套路径语义，不额外复制用户数据；不同后端不得同时打开同一个活动会话库。
3. 从 Go App 冷启动正常模式和安全模式；覆盖拒绝/撤销 Root、强制停止、引擎崩溃、切换后端与 APK 引擎升级。验收 Phone bridge、DNS/TLS、凭据读取、Native addon、工具子进程和停止后的清理。授权失败而尚未写入会话时提示用户切回原后端；不能偷偷重发已受理的请求。
4. 用隔离的同版本配置分别跑 tawcroot、PRoot 和 chroot。把就绪时间、恢复会话时间、代理预处理、上游首字分别记录，配合峰值 PSS、V8 堆和停机残留；不要用总首字时间替代引擎开销。在更多 Android/Root 管理器组合验证前不提高支持承诺。

核查依据：Linux [`chroot(2)`](https://man7.org/linux/man-pages/man2/chroot.2.html)、[`mount_namespaces(7)`](https://man7.org/linux/man-pages/man7/mount_namespaces.7.html)；[AOSP：Security-Enhanced Linux in Android](https://source.android.com/docs/security/features/selinux)；本仓库 Android runtime 启停实现及上述隔离试验。没有修改手机的挂载、启动模式或权限配置。
