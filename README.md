# Napcat Adapter for Maibot-java

适用于 Maibot-JE 的 Napcat 协议适配mod

## 功能（开发中）

- 支持 Napcat 协议的聊天机器人接入 Maibot-JE

## 安装

1. 构建Jar包：
   ```bash
   ./gradlew jar
   ```
   这将在`build/libs/`目录下生成 `napcat_adapter-x.x.x.jar` 文件。
2. 将生成的 `Napcat-Adapter-x.x.x.jar` 文件放入 Maibot-JE 的 `mods` 目录下。

## 配置

### 连接到Napcat

1. 配置`Napcat-Adapter`：
   - 首次运行 Maibot-JE 后，会在 `config` 目录下生成 `napcat_adapter.config.toml` 文件。
   - 打开该文件，根据需要修改配置项。
2. 在`Napcat->网络配置`中新建`Websocket客户端`，填写以下信息：
   - `名称`：任意方便识别的名称，比如`Napcat-Adapter`
   - `URL`：`ws://<Maibot服务器地址>:<端口>/ws/napcat`（请根据实际情况替换）
   - `消息格式`：保持默认的`Array`
   - `Token`：保持为空即可（后续版本可能会支持Token验证）
   - `心跳间隔`：保持默认值`30000`毫秒（Adapter端默认超过60秒未收到心跳会断开连接）
   - `重连间隔`：视情况而定，若网络不稳定可适当调小

## 致谢

- 参考了Maibot-PE的 [MaiBot-Napcat-Adapter](https://github.com/Mai-with-u/MaiBot-Napcat-Adapter)
- 感谢 [@UnCLAS-Prommer](https://github.com/UnCLAS-Prommer) 提供的技术支持

## 开源协议（License）

本模组采用 MIT 许可证开源，详见 [LICENSE](./LICENSE) 文件。