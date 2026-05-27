# CDNViewer - 网速测试

一款 Android 网络工具应用，集成 CDN 资源嗅探、M3U8 视频下载合并转码、GitHub Hosts 优化、应用内更新等功能。

## 功能特性

### CDN 资源嗅探
- 内置 WebView 浏览器，自动嗅探页面中的图片、视频、音频、下载链接等资源
- 支持 `.m3u8` (HLS) 播放列表自动识别与解析
- 视频资源显示封面缩略图、时长、预估大小

### M3U8 视频下载
- 自动解析 M3U8 播放列表，提取 TS 分片 URL
- 多线程并发下载（默认 3 线程），支持进度回调
- AES-128-CBC 加密分片自动解密（解析 `#EXT-X-KEY`）
- FFmpegKit 合并转码，支持多种输出格式：
  - 原格式 (.ts)
  - MP4 容器
  - H.264 编码 (.mp4)
  - H.265/HEVC 编码 (.mp4)
  - AV1 编码 (.mp4)

### GitHub Hosts 优化
- 内置 GitHub 相关域名 IP 池（github.com、api.github.com 等）
- TCP 延迟测试，每个 IP 多次测试取平均值
- 从 `raw.hellogithub.com` 获取社区维护的远程 hosts
- 代理镜像自动测速排序（ghfast.top、ghproxy.net 等）

### 应用内更新
- 通过 GitHub Releases API 检查新版本
- 多镜像源 failover，下载失败自动切换下一个镜像
- 远程 hosts 直连作为最后回退方案
- 兼容多品牌 APK 安装器（MIUI、华为、三星、ColorOS、Vivo 等）

### 下载管理
- 支持三种来源：应用目录、系统目录、系统下载管理器
- 下载记录持久化（Room 数据库），记录 URL、文件名、路径、大小、日期
- 多选删除、全选操作
- 支持下载前重命名文件

### 日志系统
- 文件日志 + Logcat 双输出
- 内存缓冲写入，500KB 阈值自动刷盘
- 按日期目录组织，自动 XZ 压缩归档
- 7 天自动清理，5MB 单文件上限

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| 最低版本 | Android 11 (API 30) |
| 目标版本 | Android 16 (API 36) |
| 构建工具 | Gradle 8.1.4 + KSP |
| 本地数据库 | Room 2.6.1 |
| 视频处理 | FFmpegKit (16KB 对齐版) |
| 压缩库 | XZ for Java (org.tukaani:xz) |
| UI 框架 | Material Components + WebView |
| 异步框架 | Kotlin Coroutines |

## 项目结构

```
app/src/main/java/org/tan/cdntest/
├── MainActivity.kt          # 主界面：WebView 浏览器 + 资源嗅探 + 下载流程
├── MoreActivity.kt          # 设置页：UA、搜索引擎、下载目录、TS 格式、日志、更新
├── DownloadManagerActivity.kt # 下载管理：文件列表、多选删除
├── HostsTestActivity.kt     # GitHub Hosts 测速与优化
├── CdnSnifferAdapter.kt     # 嗅探结果列表适配器
├── UserAgentHelper.kt       # User-Agent 管理
├── SearchEngineHelper.kt    # 搜索引擎配置
├── CertHelper.kt            # SSL 证书信息解析
├── GitHubHostsHelper.kt     # GitHub Hosts 核心逻辑
├── AppLogger.kt             # 日志系统
├── UpdateChecker.kt         # 应用更新检测与下载
├── SimpleDownloader.kt      # 简单 HTTP 下载器
├── DownloadHelper.kt        # 下载目录与安装辅助
├── DownloadRecordEntity.kt  # Room Entity + DAO
├── DownloadRecordStore.kt   # 下载记录 CRUD
├── AppDatabase.kt           # Room Database 单例
├── M3u8Parser.kt            # M3U8 解析器
├── TsDownloader.kt          # TS 多线程下载 + AES 解密
├── TsMerger.kt              # FFmpeg 合并转码
└── VideoInfoFetcher.kt      # 视频信息获取（时长/分辨率/封面）
```

## 构建

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 36

### 编译运行

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（需要签名配置）
./gradlew assembleRelease
```

### 签名配置

Release 构建需要配置签名。支持以下方式（优先级从高到低）：

1. **环境变量**：`KEYSTORE_PASSWORD`、`KEY_PASSWORD`
2. **项目根目录** `keystore.properties` 文件
3. **默认值**（仅用于 CI，不建议在本地使用）

将 `release.keystore` 放在项目根目录，然后配置环境变量或创建 `keystore.properties`：

```properties
storePassword=your_store_password
keyPassword=your_key_password
```

### CI/CD

项目配置了 GitHub Actions 自动构建（`.github/workflows/build.yml`）：
- 每周二自动检查是否有新提交
- 自动构建签名 Release APK
- 创建 GitHub Release 并上传 APK
- 自动清理旧 Release（保留最近 10 个）

需要在 GitHub Secrets 中配置：
- `KEYSTORE_BASE64`：Base64 编码的 keystore 文件
- `KEYSTORE_PASSWORD`：keystore 密码
- `KEY_PASSWORD`：key 密码

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络访问 |
| `ACCESS_NETWORK_STATE` | 检查网络连接状态 |
| `REQUEST_INSTALL_PACKAGES` | 安装下载的 APK 更新 |
| `POST_NOTIFICATIONS` | 下载进度通知 (Android 13+) |

## 使用说明

1. **资源嗅探**：在浏览器中打开目标网页，点击底部嗅探按钮，自动列出页面中的媒体资源
2. **视频下载**：点击嗅探到的视频资源，选择输出格式后开始下载；M3U8 视频会自动解析分片、下载、合并
3. **GitHub 优化**：进入设置 → Hosts 测试，自动测速并推荐最优 IP
4. **下载管理**：进入下载管理页面，可按来源筛选、复制链接、批量删除

## 许可证

本项目仅供学习交流使用。
