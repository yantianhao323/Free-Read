<div align="center">
  <img src="/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="200" height="200" alt="Free-Read Logo" />
  <h1>Free-Read (纯净专属版)</h1>
  <p>基于 Material You 风格构建的现代 Android 新闻阅读器，专为极致阅读体验深度定制。</p>
</div>

## ✨ 专属定制特色

**本项目是在开源优秀作品 [ReadYou](https://github.com/Ashinch/ReadYou) 的基础上，针对特定需求进行的深度“魔改”与增强版本。**

相比于原版，本专属版本实现了以下核心功能与突破：

### 1. 🚀 顶级海外媒体反爬虫/付费墙突破
为了解决现代媒体越来越严苛的反爬虫限制（例如 403 Forbidden, 401 Unauthorized, JS-Challenge），本项目重构了底层的文章抓取管道：
- **Headless WebView 智能渲染引擎**：
  针对 **Bloomberg** 和 **The Wall Street Journal (WSJ)** 等采用严格前端拦截和动态反爬的媒体，直接切入底层不可见的 WebView 引擎进行云端渲染。
  - 自动拦截阻止一切多余网络请求（广告、图片、媒体资源）。
  - 精准拦截并干掉 `transporter` 追踪组件与其他恶意拦阻脚本。
  - 智能等待前端 React SPA 框架与动态 DOM 树完全渲染后再进行内容提取。
- **动态请求头与身份伪装 (BypassInterceptor)**：
  针对 **The New York Times (NYT)** 和 **The Economist** 等媒体，实现了底层的 OkHttp 拦截器。通过模拟 `Googlebot` 与专属的 `User-Agent`、`Referer` 链路，直接从源头获取无障碍文章。
- **自适应回退机制 (Fallback Pipeline)**：
  极大地增强了稳定性。当 OkHttp 遭遇 403 等 HTTP 阻断时，不再直接报错抛弃，而是自动平滑回退到第三方网页存档（Archive）或 Headless WebView 引擎进行最后的抢救式抓取。

### 2. 📡 告别 Google News 封锁
原版中许多国外媒体由于直接依赖 Google News 的 RSS 源，导致文章链接被谷歌的双重 Protobuf base64 强行加密混淆，导致所有提取器全部失效。
- **本版本彻底剥离了这部分毒瘤源**，手动寻找并替换成了各大媒体最原始、最纯净的 Native RSS Feeds，从而让文章解析链路彻底重生。

### 3. 🇨🇳 引入优质中文深度媒体矩阵
无需任何配置，打开“发现”页面，即刻掌握全球局势发声：
默认集成了 9 家优质无墙中文新闻源，包括：**澎湃新闻、界面新闻、南方周末、36氪、虎嗅、人民日报、少数派、端传媒、联合早报**。

### 4. 🎨 沉浸式阅读UI定制
- 删除了底部繁杂的“目录”标签与不必要的 UI 控件。
- 设计并替换了全新的 App 启动图标，彰显专属定制感。

---

## 📥 下载安装

请前往本仓库的 **[Releases](https://github.com/yantianhao323/Free-Read/releases)** 页面下载最新版本的 `.apk` 文件并在您的安卓手机上安装。

## ⚠️ 免责声明 (Disclaimer)

本项目仅作为学习 Android WebView 渲染机制、OkHttp 拦截器原理以及网络爬虫攻防对抗技术的**个人研究与技术验证**用途。
**请勿将其用于任何商业化变现或违法用途。文章版权归原媒体所属，如有侵权请联系删除。**

## 🤝 致谢
感谢原版开源项目 [ReadYou](https://github.com/Ashinch/ReadYou) 提供的卓越 UI 与基础架构。
