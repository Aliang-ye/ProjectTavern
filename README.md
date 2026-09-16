# 暮色酒馆 Project Tavern

本地优先的 Android AI 角色 / RPG 客户端。**你自带模型，酒馆只负责世界。**

> Bring your own AI. Bring your own world.

App **不提供推理、不做 API 中转、没有账号、没有云同步**。Key 只存在这台手机上。

当前构建：**v1.0.0-polished**（已附带发布版 APK）

## 版本说明

本次版本聚焦于成品化体验：

- 统一页面卡片与底栏风格，视觉更稳定
- 修正设置页、配置页、聊天页状态显示
- 增强 API 配置的默认值和保存行为
- 添加更清晰的提示与失败状态
- 提高备份导入 / 恢复体验与错误提示
- 默认启用更稳妥的安全网络与本地优先设计

Android APK 已上传到 GitHub Releases，可直接下载体验：

- Release: https://github.com/Aliang-ye/ProjectTavern/releases/tag/v1.0.0-polished

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
