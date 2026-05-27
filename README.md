# CDNViewer

> 内置浏览器嗅探网页资源，一键下载 M3U8/HLS 视频并自动合并转码 — Android 网络工具箱

![Platform](https://img.shields.io/badge/Android-11+-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue)
![License](https://img.shields.io/badge/仅供学习交流-orange)

**适合谁用**：需要从网页抓取视频/音频资源、下载 M3U8 直播流、或加速 GitHub 访问的 Android 开发者和普通用户。

## 功能亮点

- **网页资源嗅探**：内置 WebView 浏览器，自动检测页面中的图片、视频、音频、下载链接，支持按类型和大小筛选
- **M3U8 全流程下载**：自动解析 HLS 播放列表 → 多线程并发下载 TS 分片 → AES-128-CBC 解密 → FFmpeg 合并转码，支持 TS / MP4 / H.264 / H.265 / AV1 输出
- **GitHub Hosts 优化**：内置 IP 池 TCP 延迟测试，自动获取社区 hosts，代理镜像测速排序
- **应用内更新**：GitHub Releases 多镜像 failover 下载，兼容 MIUI / 华为 / 三星等品牌安装器
- **下载管理**：暂停/恢复/取消，多选批量操作，Room 数据库持久化记录，存储空间可视化

## 安装

从 [GitHub Releases](https://github.com/kelven-coding/cdn_speedtest/releases) 下载最新 APK 安装即可。

要求：Android 11 (API 30) 及以上，arm64-v8a 架构。

## 快速开始

1. 打开应用，默认加载 `speedtest.2026524.xyz` 测速页面
2. 在地址栏输入目标网址，浏览任意网页
3. 点击底部嗅探按钮，自动列出页面中的媒体资源
4. 选择资源 → 点击下载，M3U8 视频会自动解析、下载、合并

## 使用说明

### 资源嗅探

- 嗅探面板支持 filter：全部 / 图片 / 视频 / 下载 / 其他
- 拖动 SeekBar 按文件大小过滤（0 ~ 100MB）
- 视频资源自动获取封面缩略图、时长、分辨率
- 支持多选批量下载、复制链接、在线预览

### M3U8 视频下载

| 步骤 | 说明 |
|------|------|
| 解析 | 自动提取 TS 分片 URL 和 AES 密钥 (`#EXT-X-KEY`) |
| 下载 | 多线程并发（1~8 线程可配），支持断点续传 |
| 解密 | AES-128-CBC 自动解密加密分片 |
| 合并 | FFmpegKit 转码，支持 Original TS / MP4 / H.264 / H.265 / AV1 |

### GitHub Hosts 优化

- 设置 → Hosts 测试，自动测速 GitHub 域名 IP
- 支持从 `raw.hellogithub.com` 获取远程 hosts
- 代理镜像自动排序：ghfast.top、ghproxy.net 等

### 下载管理

- 三种来源筛选：应用目录 / 系统目录 / 系统下载管理器
- 实时进度显示，支持暂停全部 / 恢复全部
- 视频文件自动生成缩略图
- 存储空间使用条形图

### 设置项

- User-Agent 切换（预设 + 自定义管理）
- 搜索引擎配置（预设 + 自定义 URL 模板）
- 下载目录选择（应用内部 / 系统下载目录）
- TS 输出格式 & 下载线程数
- 日志开关 & 日志级别

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| 最低版本 | Android 11 (API 30) |
| 目标版本 | Android 16 (API 36) |
| 构建工具 | AGP 8.1.4 + KSP 1.9.22-1.0.17 |
| 本地数据库 | Room 2.6.1 |
| 视频处理 | FFmpegKit 6.1.1 (16KB page-aligned) |
| 压缩库 | XZ for Java 1.9 |
| UI | Material Components 1.11.0 + WebView |
| 异步 | Kotlin Coroutines |

## 项目结构

```
app/src/main/java/org/tan/cdntest/
├── MainActivity.kt              # 主界面：WebView + 资源嗅探 + 下载流程
├── MoreActivity.kt              # 设置页
├── DownloadManagerActivity.kt   # 下载管理：文件列表、多选删除、存储信息
├── HostsTestActivity.kt         # GitHub Hosts 测速
├── PreviewPlayerActivity.kt     # 全屏音视频播放器
├── DownloadEngine.kt            # 核心下载引擎：并发队列、暂停/恢复、M3U8 流程编排
├── DownloadService.kt           # 前台服务：持久通知显示下载进度
├── M3u8Parser.kt                # M3U8 播放列表解析
├── TsDownloader.kt              # TS 多线程下载 + AES 解密
├── TsMerger.kt                  # FFmpeg 合并转码
├── VideoInfoFetcher.kt          # 视频元信息获取
├── GitHubHostsHelper.kt         # Hosts 核心逻辑
├── CdnSnifferAdapter.kt         # 嗅探结果列表适配器
├── ActiveDownloadAdapter.kt     # 下载任务列表适配器
├── DownloadHelper.kt            # 下载目录 & 存储管理
├── DownloadRecordEntity.kt      # Room Entity + DAO
├── DownloadRecordStore.kt       # 下载记录 CRUD
├── AppDatabase.kt               # Room Database 单例
├── UserAgentHelper.kt           # UA 预设管理
├── SearchEngineHelper.kt        # 搜索引擎配置
├── CertHelper.kt                # SSL 证书解析
├── AppLogger.kt                 # 文件日志 + XZ 压缩归档
├── UpdateChecker.kt             # GitHub Releases 更新检测
└── SimpleDownloader.kt          # 简单 HTTP 下载器
```

## 构建

### 环境要求

- Android Studio Hedgehog 或更高
- JDK 17
- Android SDK 36

### 编译

```bash
# Debug
./gradlew assembleDebug

# Release（需签名配置）
./gradlew assembleRelease
```

### 签名配置

Release 构建需要签名，优先级从高到低：

1. 环境变量：`KEYSTORE_PASSWORD`、`KEY_PASSWORD`
2. 项目根目录 `keystore.properties`
3. 默认值（仅 CI 使用）

```properties
storePassword=your_store_password
keyPassword=your_key_password
```

### CI/CD

GitHub Actions (`.github/workflows/build.yml`)：

- 每周二自动检查新提交
- 构建签名 APK → 创建 GitHub Release
- 自动清理旧 Release（保留最近 10 个）

需在 GitHub Secrets 配置：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | Base64 编码的 keystore 文件 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_PASSWORD` | key 密码 |

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络访问 |
| `ACCESS_NETWORK_STATE` | 检查网络连接状态 |
| `REQUEST_INSTALL_PACKAGES` | 安装 APK 更新 |
| `POST_NOTIFICATIONS` | 下载进度通知 (Android 13+) |
| `FOREGROUND_SERVICE` | 后台下载前台服务 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 前台服务类型声明 |

## 许可证

本项目仅供学习交流使用。
