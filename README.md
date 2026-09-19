# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 只存在这台手机上。

当前构建：**v1.7.0**（开场白宏替换与多开场白滑动选择、输入草稿自动暂存防丢失、`*动作描写*`排版轻量斜体格式化、全角中文逗号兼容、世界书条目常驻注入开关、对话全文导出Markdown、顶部统计胶囊联动切换）

> [!NOTE]
> **版本与源码说明**：
> - **开源代码库（Community Lite Edition）**：本仓库提供核心客户端骨架、Android UI 交互与标准模型通信能力，适合二次开发与定制。
> - **官方正式安装包（Official Packaged Release）**：包含完整高级多级世界书注入引擎、记忆浓缩流与官方预设剧本。推荐直接前往 [GitHub Releases](https://github.com/Aliang-ye/ProjectTavern/releases) 下载官方构建的 APK 安装包体验完整功能。

## 版本说明 (v1.7.0)

本次更新站在真实文字 RPG / 酒馆角色扮演爱好者的第一视角，解决日常沉浸体验与创作中的深层痛点：

1. **开场白宏替换与多开场白（Alternate Greetings）切换**：
   - 角色与世界意志的第一条消息在生成时自动替换 `{{user}}` 与 `{{char}}`，告别进入故事第一句就出戏的尴尬；
   - 完整支持角色卡内置的多个备用开场白，开局即可通过消息下方的 `1/n` 一键切换不同开场剧本分支。
2. **输入框草稿自动保存与恢复（Draft Persistence）**：
   - 长文动作描写构思过程中若切出应用、接听电话或误触返回键，再次进入该对话时自动完整还原草稿，发送成功后自动清理，告别心血丢失。
3. **`*动作描写*` 轻量排版美化（Novel Aesthetic）**：
   - 自动识别叙述与角色动作中的 `*心理与动作描写*` 并渲染为典雅斜体，对话与动作分层分明，小说阅读质感大幅提升。
4. **中文输入法全角标点兼容**：
   - 角色标签与世界书关键词全面兼容全角中文逗号 `，`、分号及空格切分，手机拼音输入法敲击无阻滞。
5. **世界书条目常驻注入（Constant）开关**：
   - 世界书条目编辑窗新增「常驻注入」开关，核心世界法则与全局设定无需关键词触发即可恒定注入上下文，并带有醒目的“★ 常驻”标识。
6. **对话全文一键导出（Export to Markdown）**：
   - 长按对话标题选择「导出对话 (Markdown)」，即可一键生成排版工整的故事长文并唤起系统分享。
7. **顶部统计胶囊联动 Tab 切换**：
   - 主页顶部的角色、世界书、故事、对话统计胶囊均支持点击直接平滑跳转对应面板，并带有清晰易懂的类别标签。

Android APK 发布与下载：

- **最新版本 Release**：[Project Tavern v1.7.0](https://github.com/Aliang-ye/ProjectTavern/releases/tag/v1.7.0)
- **最新安装包直接下载**：[ProjectTavern-v1.7.0.apk](https://github.com/Aliang-ye/ProjectTavern/releases/download/v1.7.0/ProjectTavern-v1.7.0.apk)
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
