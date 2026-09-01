# mumdroid

**mumdroid** 是一款开源的 [Mumble](https://www.mumble.info/) Android 语音聊天客户端，使用 **Kotlin** 与 **Jetpack Compose** 从零编写。

[English](README.md) · 简体中文

---

## 目录

- [亮点](#亮点)
- [功能特性](#功能特性)
- [架构](#架构)
- [协议与音频](#协议与音频)
- [安全模型](#安全模型)
- [设置项参考](#设置项参考)
- [目录结构](#目录结构)
- [环境要求](#环境要求)
- [从源码构建](#从源码构建)
- [测试](#测试)
- [故障排查](#故障排查)
- [第三方组件](#第三方组件)
- [参考](#参考)
- [特别鸣谢](#特别鸣谢)
- [许可证](#许可证)

---

## 亮点

- **可自定义的Android 音频路由。** 耳机 / 蓝牙 / 扬声器 / 听筒，支持用户排序的优先级列表与通话/媒体播放路径。
- **服务器管理。** 频道、访问令牌、注册用户、封禁（定时或永久，按证书或 IP）、踢人、移动、静音、聋哑、优先发言。频道访问基于密码，未包含完整的 ACL 编辑器。

---

## 功能特性

### 语音

| 项目 | 选项 |
| --- | --- |
| 发送方式 | 持续发送、语音活动检测（VAD）、按键说话（PTT） |
| VAD | 幅度或信噪比，双阈值（0–100）带语音保持 |
| 编码 | Opus 48 kHz 单声道，8–192 kbit/s，每包 1/2/4/6 帧，受限低延迟 |
| 降噪 | 关闭 / 系统 / Speex / RNNoise / Speex + RNNoise，0–60 dB |
| 增益 | 麦克风音量，系统或 Speex AGC（可调最大增益） |
| 回声消除 | 关闭 / 系统 / Speex（软件，扬声器参考） |
| 输出 | 耳机、蓝牙、扬声器、听筒——用户排序；通话或媒体路径 |
| 扩展 | 半双工、来话音量、软限幅器、带丢包隐藏的抖动缓冲 |
| 传输 | UDP 自动回退 TCP、强制 TCP 模式、QoS（DSCP）标记 |

### 连接

- **TLS 加密的 TCP 控制通道**，支持可选客户端证书；上报 TLS 版本、加密套件及是否提供前向保密。
- **自签名服务器证书处理**，支持 SHA-256 指纹捕获；证书固定默认开启——指纹不匹配时暂停连接，可选择更新固定、信任一次或拒绝。
- **服务器列表**，使用明文 UDP 探测（旧版 12 字节 *和* protobuf ping）上报延迟、用户数、最大用户数、带宽与版本；可选周期重 ping（5–60 s，默认关闭）。
- **自动重连**（带退避策略），恢复上次服务器 / 上次频道。
- **统计信息**：TCP/UDP ping（平均、偏差、正常/迟到/丢失/重同步）及 UDP 包计数。

### 频道、用户与管理

- 可折叠的 **频道 / 用户树**，支持加入、链接/取消链接、收听/取消收听、频道用户计数与说话状态。
- 创建、编辑、删除、移动频道——包括临时频道、最大用户数、描述与密码（按地址记住为访问令牌）。频道访问控制仅限于密码，由客户端转换为服务器 ACL 规则；细粒度的 ACL 编辑（组、按用户授权）尚未实现。
- 用户操作：信息（版本、地址、证书、Opus 支持、ping 统计）、静音、聋哑、移动、踢出、封禁、注册、改名、注销、优先发言、忽略消息、本地屏蔽。
- **注册用户列表**与**封禁列表**，支持搜索；按证书或 IP 定时/永久封禁。
- **文字聊天**：频道与私聊，带历史记录、系统通知（加入、离开、移动、踢出、封禁）与内联回复通知。

### 界面

- 基于 Jetpack Compose 的现代 **Material 3** 界面。
- **中英双语**，应用内语言切换。
- 浅色 / 深色 / 跟随系统主题，听筒距离感应，连接时保持屏幕常亮。
- 按 ABI 拆分 APK（`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`）。

---

## 架构

代码按自包含的分层组织：

```
dev.woms.mumdroid
├── core/                   框架无关层
│   ├── proto/              Protobuf（lite）生成的 Mumble 消息 + 编解码
│   ├── crypto/             OCB2-AES128（CryptOCB2）与 CryptState 密钥材料
│   ├── net/                MumbleClient（TCP/TLS）、UdpVoiceManager、ping 探测
│   ├── audio/              Opus 编解码、采集、播放、抖动缓冲、DSP JNI
│   ├── i18n/               运行时语言切换
│   └── model/              频道 / 用户 / 服务器 / 设置数据类
├── data/                   Room 数据库、DAO、证书存储、设置（DataStore）
├── service/                MumbleService + 各会话协作者
└── ui/                     Compose 界面、对话框、设置、主题
```

---

## 协议与音频

### 协议

- **控制通道。** TCP/TLS，6 字节大端头（`type`、`length`）后接 protobuf 消息。消息类型编号对应官方 `MumbleProtocol.h` 的 `MUMBLE_ALL_TCP_MESSAGES`（类型 26 插件数据被忽略）；声明的客户端版本为 1.5.0。
- **语音通道。** 每包一个 UDP 数据报，整体以 OCB2-AES128 加密（`[4 字节开销][加密头 + 载荷]`）。
    - 服务器 ≥ 1.5.0：protobuf 组帧（`MumbleUDP.Audio` / `MumbleUDP.Ping`）。
    - 旧版服务器：legacy 组帧，`(type << 5) | target`，类型 4 = Opus。
    - 仅解码 Opus；CELT 与 Speex 载荷被丢弃，与现代桌面客户端一致。
- **连通性。** 周期性 UDP ping 测量往返时间并检测语音路径故障。任一方 UDP 失效即切换到 TCP 隧道，UDP 恢复后切回。持续解密失败会触发加密重新同步。
- **带宽自适应。** 请求的码率与包大小会被逐步降低（`adjustBandwidth`），直到 IP + UDP + OCB2 + 组帧开销符合服务器 `max_bandwidth`，最低降至 8 kbit/s。

### 音频流水线

```
AudioRecord ─► MicCaptureEngine ─► SoftLimiter ─► OpusCodec.encode ─► UDP/TCP
                     │
                     ├─ AudioPreprocessor（Speex / RNNoise / 系统）
                     ├─ AGC（系统效果或 Speex）
                     └─ AEC（系统效果或 Speex 对扬声器参考）

UDP/TCP ─► UdpVoiceManager ─► OpusCodec.decode ─► VoiceJitterBuffer ─► AudioTrack
                                                  （重排序、PLC、淡入淡出）
```

- 帧为 10 ms（480 采样）；每包打包 1、2、4 或 6 帧；全程 16 位单声道 48 kHz PCM；按会话的解码器映射支持最多 32 路并发说话人并带空闲回收。
- 抖动缓冲基于官方 `frameNumber` 时间轴，重排序包、丢弃迟到包、预滚说话突增并在播放时隐藏空隙。
- 纯 Java 的 [Concentus](https://github.com/lostromb/concentus) Opus 编解码器通过 Maven 依赖引入；RNNoise 与 speexdsp 由内置 submodule 通过 CMake 原生编译，经轻量 JNI 绑定暴露——无预编译二进制，全部从源码构建。
- 输出路由跟踪设备插拔、启动/停止蓝牙 SCO，并应用所选通话或媒体用途。听筒模式下连接时使用距离感应器熄屏。

---

## 安全模型

| 关注点 | 方案 |
| --- | --- |
| 服务器身份 | 首次连接固定 SHA-256 证书指纹；不匹配时弹出提示（更新 / 信任一次 / 拒绝） |
| 客户端身份 | TLS 握手时出示一个 PKCS#12 用户证书；本地生成（Bouncy Castle）或导入 `.p12`/`.pfx` |
| 密钥存储 | 每个证书存放在应用私有存储中独立的 PKCS#12 keystore；断开时擦除加密密钥材料 |
| 语音隐私 | 每个语音数据报均使用 OCB2-AES128 加密，包括经 TCP 隧道的包 |
| 频道访问 | 频道密码会转换为服务器 ACL 规则（deny-all + 授予 `#password`）；界面未提供细粒度 ACL 编辑 |
| 最小化 | 无遥测、无分析、无账号——应用只与您添加的服务器通信 |

---

## 设置项参考

设置保存在 DataStore 中，分为五个页面（外加"关于"）。

**音频** —— 噪声抑制引擎与强度、音频源（麦克风或语音通话）、AGC 后端与最大增益、
回声消除后端、麦克风音量、发送质量、每包音频时长、低延迟模式、入声音量、播放通路、
默认输出顺序、半双工。

**网络** —— 语音传输方式（UDP 或强制 TCP）、QoS 标记（语音 socket 打 DSCP EF 标记）、
自动重连、证书固定、服务器列表自动 ping 及间隔。

**身份与证书** —— 默认用户名、当前启用的用户证书、生成 / 导入 / 导出、
已记录的服务器证书。

**外观** —— 主题（跟随系统 / 浅色 / 深色）、语言（跟随系统 / English / 简体中文）、
频道人数显示。

**通用** —— 连接时保持屏幕常亮、聊天消息通知。

**关于** —— 版本、构建哈希、开源许可。

---

## 目录结构

```
.
├── app/
│   ├── schemas/                 导出的 Room 架构（v1 … v5）
│   └── src/
│       ├── main/
│       │   ├── cpp/             speexdsp 与 RNNoise 的 CMake 构建与 JNI 绑定
│       │   ├── proto/           Mumble.proto、MumbleUDP.proto
│       │   ├── java/…/          Kotlin 源码（见架构设计）
│       │   └── res/             values/（英文）、values-zh/（中文）
│       └── test/                JVM 单元测试
├── gradle/libs.versions.toml    统一版本目录
└── settings.gradle.kts
```

持久化方面，服务器、证书与访问令牌使用 Room（数据库版本 5，保留 1→2→3→4→5 迁移），
设置使用 DataStore。

---

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android | 8.0（API 26）或更高 |
| ABI | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` |
| 权限 | 网络、录音、修改音频设置、前台服务（麦克风）、通知、唤醒锁 |
| 服务器 | 任何支持 Opus 的 Mumble 服务器（Murmur）——1.5.0+ 使用 protobuf UDP 组帧，旧版使用 legacy 组帧 |

---

## 从源码构建

DSP 库是 git submodule，请使用 `--recursive` 克隆（或之后初始化——缺失时 CMake 构建会给出明确报错）。

```bash
git clone --recursive https://cnb.cool/womsxd/mumdroid.git
cd mumdroid

# 如果克隆时未带 --recursive
git submodule update --init --recursive

./gradlew :app:assembleDebug
```

Debug APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

其他常用任务：

```bash
./gradlew :app:assembleRelease   # R8 优化，按 ABI 拆分 APK
./gradlew :app:test              # JVM 单元测试
./gradlew :app:lint              # 静态分析
```

> **注意**
> RNNoise 模型权重不存储在仓库中。CMake 在配置阶段下载与 `rnnoise/model_version` 匹配的压缩包并校验 SHA-256，因此首次构建需要联网。

工具链：Android Gradle Plugin 9.3、Kotlin 2.4、Compose BOM 2026.02.01、NDK 30 + CMake 3.22、Java 11 源/目标兼容。Release 构建启用 R8 压缩。

---

## 测试

```bash
./gradlew :app:test
```

JVM 测试套件覆盖容易出错的细节：UDP/TCP 编解码、`PacketDataStream` varint 边界、OCB2 组帧、ping 编解码、抖动缓冲行为、VAD 阈值、输出路由规则、频道树维护、ACL 权限判断、频道密码处理、封禁、设置迁移。

---

## 故障排查

| 现象 | 排查方向 |
| --- | --- |
| CMake 报缺少 `rnnoise` / `speexdsp` 目录 | 运行 `git submodule update --init --recursive` |
| 首次构建下载 RNNoise 权重失败 | CMake 会下载并校验模型压缩包——配置阶段允许联网 |
| 服务器已连接但没人听到您说话 | 检查麦克风权限、静音/聋哑状态、发送方式，以及 UDP 是否回退到 TCP |
| 语音断续 | 提高"每包音频帧数"、降低传输码率，或换更强的 Wi-Fi/蜂窝信号 |
| 已知服务器弹证书提示 | 服务器证书已变更。比对指纹后选择更新固定、信任一次或拒绝 |
| 服务器以"not using Opus"拒绝连接 | mumdroid 仅支持 Opus，服务器必须启用 Opus |
| 蓝牙无声音 | 检查输出顺序，以及播放路径是通话（SCO）还是媒体（A2DP） |

---

## 第三方组件

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| [Mumble](https://www.mumble.info/) | BSD-3-Clause | 协议与客户端行为参考 |
| [Concentus](https://github.com/lostromb/concentus) | BSD-3-Clause | 纯 Java Opus 编解码器 |
| [RNNoise](https://github.com/xiph/rnnoise) | BSD-3-Clause | 神经网络降噪（内置 submodule） |
| [speexdsp](https://github.com/xiph/speexdsp) | BSD-3-Clause | 预处理、AGC 与回声消除（内置 submodule） |
| [Bouncy Castle](https://www.bouncycastle.org/) | MIT | 客户端证书生成 |
| Android Jetpack（Compose、Room、DataStore、Lifecycle） | Apache-2.0 | 界面与持久化 |
| Kotlin & kotlinx.coroutines | Apache-2.0 | 语言与并发 |
| Protocol Buffers（Lite） | BSD-3-Clause | 控制与 UDP 消息编码 |
| Material Design icons | Apache-2.0 | 图标 |

完整的多语言归属列表也可见于应用内 **设置 → 关于 → 开源许可证**。

---

## 参考

- 官方客户端：<https://github.com/mumble-voip/mumble>
- 协议库：<https://github.com/mumble-voip/libmumble>
- RNNoise：<https://github.com/xiph/rnnoise>
- speexdsp：<https://github.com/xiph/speexdsp>
- Concentus：<https://github.com/lostromb/concentus>

---

## 特别鸣谢

- Mumla（开发参考与部分灵感来源）：<https://github.com/mumla/mumla>

---

## 许可证

mumdroid 采用 **BSD 3-Clause** 许可证发布。上述内置与第三方组件保留各自许可证——许可证全文见 `app/src/main/assets/licenses` 目录。
