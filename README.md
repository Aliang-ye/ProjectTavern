# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 只存在这台手机上。

当前构建：**v1.8.0**（世界意志备用开场白、对话重命名标题生效、角色卡头像 Base64 便携跨机分享、排版选择文本崩溃修复、全面板即时搜索、世界书条目弹窗滚动防遮挡、异步 Markdown 导出）

> [!NOTE]
> **版本与源码说明**：
> - **开源代码库（Community Lite Edition）**：本仓库提供核心客户端骨架、Android UI 交互与标准模型通信能力，适合二次开发与定制。
> - **官方正式安装包（Official Packaged Release）**：包含完整高级多级世界书注入引擎、记忆浓缩流与官方预设剧本。推荐直接前往 [GitHub Releases](https://github.com/Aliang-ye/ProjectTavern/releases) 下载官方构建的 APK 安装包体验完整功能。

## 版本说明 (v1.8.0)

本次更新聚焦于真实深度使用场景中的 19 项细节与体验缺陷，大幅增强稳定性与易用性：

1. **世界意志备用开场白支持（World Will Alternate Greetings）**：
   - 世界书编辑新增「备用开场白」，与世界意志单独对话同样支持通过 `1/n` 自由切换不同开篇剧本线。
2. **对话重命名自定义标题生效**：
   - 修复了此前即使对对话进行了重命名、标题依然被角色名强行覆盖的问题，自定义章节名与剧情备注即时呈现。
3. **角色卡头像 Base64 嵌入（便携导出与导入）**：
   - 导出角色卡时，本地头像将自动编码为 Base64 Data URI 嵌入 JSON；跨设备导入时自动无缝还原图片，告别分享后头像丢失。
4. **斜体格式化与文本选择崩溃彻底修复**：
   - 优化 `Engine.formatRpText`，采用 `SpannableStringBuilder` 与 `BufferType.SPANNABLE`，杜绝长按复制或选择文本时与富文本 Span 产生的底层冲突。
5. **角色/世界书/故事全面板即时搜索**：
   - 扩展搜索框至世界书与故事面板，支持名称与描述防抖即时筛选，海量设定库瞬间检索定位。
6. **世界书条目弹窗自适应滚动**：
   - 编辑条目弹窗外层加入 `ScrollView`，小屏机型或软键盘弹起时不再遮挡正文输入。
7. **深层健壮性与性能优化**：
   - 生成失败后清理空白 assistant 消息脏数据，防止消息树分叉紊乱；
   - 会话列表最后一条消息预览自动清洗 Markdown `*` 星号，排版干净清晰；
   - 对话记录导出 Markdown 移至后台子线程执行，长篇对话导出杜绝 ANR 卡顿；
   - 避免主页 Tab 切换与恢复时的重复冗余重绘，列表滑动更为跟手。

Android APK 发布与下载：

- **最新版本 Release**：[Project Tavern v1.8.0](https://github.com/Aliang-ye/ProjectTavern/releases/tag/v1.8.0)
- **最新安装包直接下载**：[ProjectTavern-v1.8.0.apk](https://github.com/Aliang-ye/ProjectTavern/releases/download/v1.8.0/ProjectTavern-v1.8.0.apk)
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
