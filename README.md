# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 只存在这台手机上。

当前版本 **0.3**（`versionCode 3`）。

## 能做什么

| 页 | 说明 |
| --- | --- |
| **角色** | 创建 / 编辑 / 复制 / 删除。描述、性格、场景、开场白、示例对话、系统提示。可直接「单独对话」，不必先开故事。 |
| **世界书** | 概况、地理、历史、制度、人文、个人补充设定（始终注入）。另有关键词条目：优先级、概率、常驻、插入位置、条目测试。 |
| **故事** | 长期 RPG 存档。主角 / 同伴 / 路人 + 故事世界书。 |
| **对话** | 流式输出、停止、编辑、删除、重写、多版本 Generation、从任意句分叉。小说阅读感，不是聊天气泡墙。 |
| **设置** | 主题、语言、用户主体、API、生成模式、流式/自动摘要、开发者模式、备份。 |

## 原则

1. AI 是用户自己的，Tavern 是控制 AI 的工具。
2. Character、World、Story、Memory、Chat 解耦。
3. 本地优先。V0 不依赖自有后端。
4. 社区、商城、账号、云同步全部不做，只留占位。

## API（BYOK）

只需两种格式：

| 类型 | 适用 |
| --- | --- |
| **OpenAI 兼容** | OpenAI、DeepSeek、xAI、Groq，以及任何 `/v1/chat/completions` |
| **Claude** | Anthropic 官方或兼容 Messages 接口 |

Key 明文存在本机 `filesDir/tavern.json`，**备份文件会去掉 Key**。

浏览器预览可能被跨域拦住；**Android 客户端直连，无此限制**。

## 语言

两套纯语言，不混合：

- 界面中文 → 模型只写简体中文
- 界面 English → 模型只写英文

对话提示词带强制语言锁。

## 用户主体

设置里填写你的名字和人设。会替换 `{{user}}`，并作为 `PERSONA` 块注入每一轮提示词。

## 生成模式

内置四套（中/英各一份），对话页可随时切换：

| 模式 | 用途 |
| --- | --- |
| 小说模式 | 第三人称有限视角，动作 / 光线 / 气味 |
| 创意模式 | 意象更密，仍保持角色声音 |
| 快速模式 | 短句、多对话 |
| 推理模式 | 先核对设定再写正文，不把思考过程写出来 |

每套都能改 Temperature、Top P、Max tokens、上下文上限、回复预算、系统提示。

## 主题

深色烛火 / 浅色原木纸张 / 跟随系统。

## 截图式结构

```
启动
 └── 底栏
      ├── 角色 → 详情 / 单独对话
      ├── 世界 → 六栏目 + 条目 + 条目测试
      ├── 故事 → 主角 / 同伴 / 路人 → 开聊
      ├── 对话 → 单独对话 ∪ 故事对话
      └── 设置 → 主题 / 主体 / API / 模式 / 备份
```

## 环境

- Android 8.0（API 26）+
- 编译：JDK 17、Android SDK 34、Gradle 8.7（已带 Wrapper）
- 权限：仅 `INTERNET`（直连你自己的 API）

## 用 Android Studio 打开

1. `File → Open` 选本仓库根目录
2. 等待 Gradle 同步
3. 连真机或模拟器 Run

首次会带示例角色「艾莉丝 / 莱恩」和世界书「暮色酒馆」。去设置里填 API Key 即可开聊。

## 命令行打 Debug 包

```bash
# 根目录创建 local.properties（不要提交）
# sdk.dir=/your/Android/Sdk

chmod +x gradlew
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

debug 签名，仅供自己装。换机器重新打包可能要先卸载再装。

## 目录

```
ProjectTavern/
├── README.md
├── LICENSE
├── docs/USAGE.md
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/projecttavern/app/
│       ├── TavernApp.kt          # Application，主题
│       ├── Models.kt             # 全部数据模型
│       ├── Store.kt              # 本地 JSON 持久化 + 示例数据
│       ├── I18n.kt               # 中/英界面
│       ├── Engine.kt             # Prompt 构建、世界书匹配、分支路径
│       ├── Llm.kt                # OpenAI SSE / Claude SSE
│       ├── MainActivity.kt       # 五栏主界面 + 完整设置
│       ├── CharacterActivity.kt
│       ├── WorldActivity.kt
│       ├── EntryTestActivity.kt
│       ├── StoryActivity.kt
│       ├── ChatActivity.kt
│       ├── ProfileActivity.kt
│       └── Ui.kt
```

## 数据存在哪

`filesDir/tavern.json`。导出备份不含 API Key。恢复备份时会保留当前 Key。

## 明确不做

公共社区、账号系统、云同步、自有模型、API Proxy、商城、广告。

## 许可

MIT。见 [LICENSE](LICENSE)。
