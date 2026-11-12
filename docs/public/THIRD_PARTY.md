# 第三方与模型声明

轮次：R41。文档类型：公开第三方说明；验证状态：基于当前依赖及原有声明，发布时与实际构建核对。

项目自有源码以仓库根目录的GPL-3.0许可证分发。第三方依赖保留各自版权与许可，不因本项目采用GPL而改署作者。

- Flutter/Dart：BSD类许可，构建工具来源 https://github.com/flutter/flutter 。
- AndroidX Room 2.7.2：Apache-2.0，来源 https://developer.android.com/jetpack/androidx/releases/room 。
- ONNX Runtime Android 1.22.0：MIT，来源 https://github.com/microsoft/onnxruntime/tree/v1.22.0 。
- LibADB Android 3.1.1、SPAKE2、Conscrypt 2.5.3及Bouncy Castle 1.81：沿用原许可选项与组件声明，完整说明和许可文本位于 `apps/mobile/android/app/src/capture/assets/third-party/`。其中SPAKE2按LGPL-3.0分发；源码构建保留可替换的Gradle依赖。

本地验证码模型由项目提供者交付并明确允许随开源项目公开分发，项目随附模型按GPL-3.0提供；训练数据、原始验证码图片与个人测试集不随项目分发。模型文件SHA-256：`b21da9cac2dc0f3b42b56f4eb6ffd519581a95fb0ba3afeec4330dc45f6718a0`。推理代码校验该摘要，输入为1×1×64×160灰度张量，输出为4×36字符分数。

模型识别结果不保证正确，更不代表预约成功率；预约成功仅依据学校本人订单的唯一匹配确认。学校接口的公共客户端协议参数不属于个人授权，实际用户授权不随源码分发。
