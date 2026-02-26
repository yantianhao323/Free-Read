<div align="center">
  <img src="/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="200" height="200" alt="Free Read Logo" />
  <h1>Free Read</h1>
  <p>一款基于 Material You 风格的现代 Android 资讯阅读工具，由 Vibe Coding 辅助完成。</p>
</div>

## ✨ 项目特色

**本项目是基于优秀开源作品 [ReadYou](https://github.com/Ashinch/ReadYou) 进行学习与定制的衍生版本。**

在原版体验的基础上，本项目主要进行了以下优化与调整：

### 1. 🛡️ 资讯获取链路优化
针对部分资讯源获取受阻的问题，对网络请求链路进行了深度优化：
- 引入了更灵活的网络请求特征模拟配置，提升了获取部分受限资讯源的成功率。
- 增加了针对复杂前端渲染页面的解析回退机制，确保在常规请求受阻时仍有备用方案提取正文文本。
- 优化了订阅源的抓取逻辑，使用了更直接、更纯净的新闻订阅源，解决了部分聚合源导致的链接解析失败问题。

*注：受限于移动端沙盒环境与技术可行性，对于防护等级极高的顶级媒体（如 Bloomberg, The New York Times, The Wall Street Journal, The Economist 等）目前客观上无法提供稳定的解析支持。*

### 2. 🇨🇳 本地化资讯矩阵推荐
为了更贴合中文用户的阅读习惯，在“发现”页面默认首发集成了 9 家优质的中文新闻源（如澎湃新闻、界面新闻、南方周末、36氪、虎嗅、端传媒、联合早报等），免去手动寻找抓取的烦恼，即开即用。

### 3. 🎨 沉浸阅读体验
精简了部分不适用的 UI 元素与底栏标签，更换了全新的启动图标，致力于提供更纯粹、无打扰的文本阅读环境。

---

## 📥 下载安装

请前往本仓库的 **[Releases](https://github.com/yantianhao323/Free-Read/releases)** 页面下载最新版本的 `.apk` 文件，并在您的 Android 手机上安装。

## ⚠️ 免责声明 (Disclaimer)

本项目仅作为学习 Android WebView 机制、OkHttp 拦截器原理以及网络通信技术的**个人研究与技术验证**。
**请勿将其用于任何商业化用途或大规模并发请求。所有资讯内容的版权归原媒体机构所有，解析受限属正常现象，如有关切请联系删除。**

## 🤝 致谢
* 感谢原版开源项目 [ReadYou](https://github.com/Ashinch/ReadYou) 提供的卓越 UI 与基础架构。
* 感谢 [bypass-paywalls-chrome](https://github.com/bpc-clone/bypass-paywalls-chrome-clean) 插件项目在网页元素特征提取上提供的技术分析灵感与参考。
* 本项目代码开发与功能重构完全由 **Vibe Coding**（AI 辅助编程）独立完成。
