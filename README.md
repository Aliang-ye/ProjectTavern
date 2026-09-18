# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 只存在这台手机上。

当前构建：**v1.4.0**（草稿机制与正式创建分离、世界意志内聚体系、QQ式侧滑置顶删除、生成模式独立分支管理、系统级流畅度大重构）

> [!NOTE]
> **版本与源码说明**：
> - **开源代码库（Community Lite Edition）**：本仓库提供核心客户端骨架、Android UI 交互与标准模型通信能力，适合二次开发与定制。
> - **官方正式安装包（Official Packaged Release）**：包含完整高级多级世界书注入引擎、记忆浓缩流与官方预设剧本。推荐直接前往 [GitHub Releases](https://github.com/Aliang-ye/ProjectTavern/releases) 下载官方构建的 APK 安装包体验完整功能。

## 版本说明 (v1.4.0)

本次更新聚焦于“防误触与草稿保护、世界意志内聚、侧滑置顶与操作效率、生成模式独立化以及底层流畅度重构”：

1. **草稿机制与正式创建分离**：
   - 点击加号（FAB）进入时为纯内存草稿模式，顶部提供「创建」按钮；
   - 未点击最终创建前退出，自动销毁，不产生未命名卡片、不生成任何正式会话或世界意志，彻底杜绝数据残留。
2. **生成模式（Preset）独立分支管理**：
   - 移出设置主页，采用独立「生成模式管理」页面，设置页大幅瘦身；
   - 默认精简为 1 个预设（回复预算 300 tokens，通用生动），用户可在子页面自由新增、定制与删除预设。
3. **QQ 风格向左滑动交互（置顶 + 删除）**：
   - 对话、角色、世界、故事 4 大列表全部支持向左滑动露出「置顶」与「删除」操作块；
   - 置顶项目带有 📌 标识并始终优先沉淀在列表最顶部，支持随时取消置顶与一键确认删除。
4. **GM 升级为内聚式「世界意志」**：
   - 角色列表彻底净化，不再出现伪装成角色卡的 GM；
   - GM 升级为内嵌在世界书内的【世界意志】（默认名字为“世界意志”，玩家可自定义名称、头像、开场白与世界法则系统词）；
   - 在世界卡片与详情中点击「与世界意志对话」，直接开启世界本源的沉浸式对话。
5. **系统级流畅度与性能优化**：
   - **头像图片 LruCache**：内存缓存最近 48 张头像，杜绝主线程频繁重复从本地文件解码 Bitmap，大幅消灭滑动掉帧；
   - **异步后台持久化**：`Store.persist()` 移入后台独立单线程队列执行，杜绝大 JSON 写入造成的 UI 线程卡顿；
   - **搜索输入 150ms 防抖**：输入搜索词时避免逐字全量重算全屏布局，体验顺滑如丝。

Android APK 发布与下载：

- **最新版本 Release**：[Project Tavern v1.4.0](https://github.com/Aliang-ye/ProjectTavern/releases/tag/v1.4.0)
- **最新安装包直接下载**：[ProjectTavern-v1.4.0.apk](https://github.com/Aliang-ye/ProjectTavern/releases/download/v1.4.0/ProjectTavern-v1.4.0.apk)
- **所有历史版本**：https://github.com/Aliang-ye/ProjectTavern/releases

## 能做什么

| 页 | 说明 |
| --- | --- |
| **角色** | 创建 / 编辑 / 复制 / 删除。描述、性格、场景、开场白、示例对话、系统提示。可直接「单独对话」。 |
| **世界书** | 概况、地理、历史、制度、人文、个人补充设定（始终注入）。另有高级条目测试。 |
| **故事** | 长期 RPG 存档。主角 / 同伴 / 路人 + 故事世界书。 |
| **对话** | 流式输出、停止、编辑、删除、重写、多版本 Generation。小说阅读感。 |
| **设置** | 主题、语言、用户主体、API、生成模式、流式/自动摘要、开发者模式、备份。 |

## 原则

1. AI 是用户自己的，Tavern 是控制 AI 的工具。
2. Character、World、Story、Memory、Chat 解耦。
3. 本地优先。App 不依赖自有后端。
4. 只保留纯工具型体验，不引入社区、商城、账号或云同步。

## API（BYOK）

只需两种格式：

| 类型 | 适用 |
| --- | --- |
| **OpenAI 兼容** | OpenAI、DeepSeek、xAI、Groq，以及任何 `/v1/chat/completions` |
| **本地 / 局域网模型** | Ollama（`http://192.168.x.x:11434/v1`）、LM Studio、SillyTavern |
| **Claude** | Anthropic 官方或兼容 Messages 接口 |

Key 仅保存在本机 `filesDir/tavern.json`，**导出备份时会自动去掉 Key**。

浏览器预览可能被跨域拦住；**Android 客户端直连，无此限制**。

## 语言与人格

两套纯语言，不混合：

- 界面中文 → 模型只写简体中文
- 界面 English → 模型只写英文

设置里填写你的名字和人设，会替换 `{{user}}`，并作为 `PERSONA` 块注入每一轮提示词。

## 生成模式

内置四套（中/英各一份），对话页可随时切换：

| 模式 | 用途 |
| --- | --- |
| 小说模式 | 第三人称有限视角，动作 / 光线 / 气味 |
| 创意模式 | 意象更密，仍保持角色声音 |
| 快速模式 | 短句、多对话 |
| 推理模式 | 先核对设定再写正文，不把思考过程写出来 |

## 主题

深色烛火 / 浅色原木纸张 / 跟随系统。

## 使用方式

1. 在设置里创建一个 API 配置
2. 选择 OpenAI 兼容或 Claude
3. 填写模型、接口地址、API Key
4. 在角色页或故事页建立对话
5. 进行角色扮演、世界书注入和长对话创作

首次会带示例角色「艾莉丝 / 莱恩」和世界书「暮色酒馆」。

## 环境

- Android 8.0（API 26）+
- 编译：JDK 17、Android SDK 34、Gradle 8.7（已带 Wrapper）
- 权限：仅 `INTERNET`

## 用 Android Studio 打开

1. `File → Open` 选本仓库根目录
2. 等待 Gradle 同步
3. 连真机或模拟器 Run

## 命令行打 Debug 包

```bash
# 根目录创建 local.properties（不要提交）
# sdk.dir=/your/Android/Sdk

chmod +x gradlew
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

debug 签名，仅供自己安装测试。换机器重新打包可能要先卸载再装。

## 数据存在哪

`filesDir/tavern.json`。导出备份不含 API Key。恢复备份时会保留当前 Key。

## 明确不做

公共社区、账号系统、云同步、自有模型、API Proxy、商城、广告。

## 许可

MIT。见 [LICENSE](LICENSE)。
