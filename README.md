# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 存在本机 Android Keystore，明文不会进 `tavern.json`。

当前构建：**v2.1.0**（重写不再藏后续对话、草稿与重试更稳、世界书优先级可编辑、上下文给系统提示留额度）

## 版本说明 (v2.1.0)

1. **重写中间回复不再把后面藏起来**：tip 仍停在当前分支，后续回合还在。
2. **重写开场白不会把后面剧情塞进提示词**：生成只看到被重写那一条之前的上下文。
3. **草稿、重试、长按发送**：生成中切走也会保存输入；没配 Key 时重试不会先删失败回复。
4. **世界书条目能改优先级和启用**；关掉的故事参与者不再进 CAST。
5. **上下文裁剪先给系统提示留额度**，世界书和角色卡不会被长历史挤掉。
6. **对话列表预览跟当前分支走**；调试按钮只在开发者模式显示。
7. **故事开新对话不再和故事共用世界书列表**，删一条不会改掉另一条。
8. **公网 HTTP / 非法协议直接拒绝**；局域网 Ollama 仍可用明文。

## 版本说明 (v2.0.0)

1. **第一次就能开始**：未配置 API 时弹出向导，引导去填 Key 并测试连接。
2. **PNG / 标准角色卡**：支持 SillyTavern Character Card V2 的 PNG（`tEXt/zTXt/iTXt chara`）和 JSON；也可导出 PNG。
3. **世界书不再是空壳**：次关键词、概率、插入位置（before/after character）全部生效；可单独导入导出世界书。
4. **长对话记得住**：自动摘要写入 MEMORY，调试器能看到真实注入的系统提示词。
5. **对话页补齐**：可新建对话、搜索记录、从某条消息分叉；消息操作为长按菜单。
6. **密钥进 Keystore**：备份仍不含 Key，但会嵌入头像。公网 HTTP 接口会被拒绝，局域网 Ollama 仍可用明文。
7. **服务商**：DeepSeek / 硅基流动 / 智谱 / Ollama（真机填局域网 IP）/ OpenAI / Gemini / Claude。
8. **四种生成模式**重新作为内置预设：小说 / 创意 / 快速 / 推理。

Android APK 发布与下载：

- **最新版本 Release**：[Project Tavern v2.1.0](https://github.com/Aliang-ye/ProjectTavern/releases/tag/v2.1.0)
- **最新安装包直接下载**：[ProjectTavern-v2.1.0.apk](https://github.com/Aliang-ye/ProjectTavern/releases/download/v2.1.0/ProjectTavern-v2.1.0.apk)
- **所有历史版本**：https://github.com/Aliang-ye/ProjectTavern/releases

## 能做什么

| 页 | 说明 |
| --- | --- |
| **角色** | 创建 / 编辑 / 复制 / 删除。PNG 或 JSON 导入导出。可直接「单独对话」。 |
| **世界书** | 概况、地理、历史、制度、人文、个人补充设定（始终注入）。关键词条目支持次键、概率、插入位置。 |
| **故事** | 长期 RPG 存档。主角 / 同伴 / 路人 + 故事世界书。 |
| **对话** | 流式或整段输出、停止、编辑、删除、重写、分叉、多版本 Generation。可搜索。 |
| **设置** | 主题、语言、用户主体、API、生成模式、流式/自动摘要、开发者模式、备份。 |

## 原则

1. AI 是用户自己的，Tavern 是控制 AI 的工具。
2. Character、World、Story、Memory、Chat 解耦。
3. 本地优先。App 不依赖自有后端。
4. 只保留纯工具型体验，不引入社区、商城、账号或云同步。

## API（BYOK）

| 类型 | 适用 |
| --- | --- |
| **OpenAI 兼容** | OpenAI、DeepSeek、xAI、Groq、Gemini OpenAI 端点、任何 `/v1/chat/completions` |
| **本地 / 局域网模型** | Ollama（`http://192.168.x.x:11434/v1`）、LM Studio、SillyTavern |
| **Claude** | Anthropic 官方或兼容 Messages 接口 |

Key 存在 Android Keystore。**导出备份不含 Key，但包含头像。** 恢复备份时会保留当前 Key。

## 语言与人格

- 界面中文 → 模型只写简体中文
- 界面 English → 模型只写英文

设置里填写你的名字和人设，会替换 `{{user}}`，并作为 `PERSONA` 块注入每一轮提示词。

## 生成模式

| 模式 | 用途 |
| --- | --- |
| 小说模式 | 第三人称有限视角，动作 / 光线 / 气味 |
| 创意模式 | 意象更密，仍保持角色声音 |
| 快速模式 | 短句、多对话 |
| 推理模式 | 先核对设定再写正文，不把思考过程写出来 |

## 使用方式

1. 首次打开按向导创建一个 API 配置
2. 选择 OpenAI 兼容、Claude 或 Gemini
3. 填写模型、接口地址、API Key，点测试连接
4. 在角色页、故事页或对话页加号建立对话
5. 进行角色扮演、世界书注入和长对话创作

首次会带示例角色「艾莉丝 / 莱恩」和世界书「暮色酒馆」。

## 环境

- Android 8.0（API 26）+
- 编译：JDK 17、Android SDK 34、Gradle 8.7（已带 Wrapper）
- 权限：仅 `INTERNET`

## 命令行打 Debug 包

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 数据存在哪

角色、世界、对话在 `filesDir/tavern.json`。API Key 在 Android Keystore。头像在 `filesDir/avatars/`。卸载会清掉本机数据，先导出备份。

## 明确不做

公共社区、账号系统、云同步、自有模型、API Proxy、商城、广告。

## 许可

MIT。见 [LICENSE](LICENSE)。
