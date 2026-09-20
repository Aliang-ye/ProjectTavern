# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 只存在这台手机上。

当前构建：**v1.9.0**（多线程并发安全快照防崩、全屏旋转保护防流式中断、Gson反序列化空指针防御、列表单次上限渲染防卡死、角色大图导出降采样防OOM、备份与导入全异步处理、用户气泡平板自适应、数据安全强化）

> [!NOTE]
> **版本与源码说明**：
> - **开源代码库（Community Lite Edition）**：本仓库提供核心客户端骨架、Android UI 交互与标准模型通信能力，适合二次开发与定制。
> - **官方正式安装包（Official Packaged Release）**：包含完整高级多级世界书注入引擎、记忆浓缩流与官方预设剧本。推荐直接前往 [GitHub Releases](https://github.com/Aliang-ye/ProjectTavern/releases) 下载官方构建的 APK 安装包体验完整功能。

## 版本说明 (v1.9.0)

本次更新针对深度架构、多线程并发安全、内存防爆与极端边界情况进行系统性强化：

1. **多线程持久化快照（消灭并发修改崩溃）**：
   - `Store.persist()` 在派发至后台写盘线程前，先在主线程建立深浅结合的集合独立快照，彻底消灭在流式接收消息或快速增删卡片时由于 Gson 遍历引发的 `ConcurrentModificationException` 偶发闪退。
2. **屏幕旋转无损防护（保护流式与编辑草稿）**：
   - 核心 Activity 均配置屏幕旋转与键盘配置变更保护，在流式生成或打字撰写长文时旋转屏幕不再重构 Activity，输出不中断、草稿不丢失。
3. **Gson 反序列化空安全加固**：
   - 导入外部角色卡或恢复备份时，严格防御 JSON 属性值为 `null` 绕过 Kotlin 非空约束的情况，自动回退安全默认值，杜绝底层 NPE。
4. **主界面海量列表渲染防冻结（MAX_DISPLAY 保护）**：
   - 角色、世界书、故事、对话四个面板均设立单次最多渲染 60 条卡片的上限防御，超过时底部展示智能提示，配合即时搜索瞬时过滤出任意条目，百卡千聊依然丝滑跟手。
5. **角色卡大图导出防 OOM（内存安全优化）**：
   - 导出角色卡时对本地头像进行智能降采样（上限 640px）与 JPEG 85% 高保真压缩后再 Base64 编码，生成体积在数十 KB 内的轻盈便携卡片，杜绝超大原图导致内存溢出。
6. **备份恢复与角色导入全面异步化**：
   - 备份文件读取、Gson 解析与 Base64 解码全部移入后台子线程执行，大备份恢复时主界面不再出现假死卡顿。
7. **消息编辑弹窗自适应滚动**：
   - 消息编辑弹窗加入 `ScrollView` 滚动容器，超长 AI 输出轻松翻页编辑。
8. **用户气泡平板与横屏自适应排版**：
   - 移除用户消息气泡的 280dp 硬编码最大宽度限制，改为响应式右侧气泡布局，横屏或平板使用体验大幅提升。
9. **数据安全防护**：
   - 关闭 `allowBackup`，防止敏感配置与明文 API Key 被 adb 备份提取。

Android APK 发布与下载：

- **最新版本 Release**：[Project Tavern v1.9.0](https://github.com/Aliang-ye/ProjectTavern/releases/tag/v1.9.0)
- **最新安装包直接下载**：[ProjectTavern-v1.9.0.apk](https://github.com/Aliang-ye/ProjectTavern/releases/download/v1.9.0/ProjectTavern-v1.9.0.apk)
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
