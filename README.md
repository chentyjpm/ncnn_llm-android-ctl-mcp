# ncnn_llm_ctl（Android 9+）

这是一个 Android Studio 工程（Java），基于风佬的ncnn_llm 通过 **无障碍服务** 获取系统/其他应用的 UI 结构，并通过集成的 **ncnn_llm** 让大模型调用工具执行自动化操作。

同时验证了风佬的 ncnn_llm 在安卓平台运行的可行性

## 本项目的实现主要参考：
- ncnn_llm：https://github.com/futz12/ncnn_llm
- ncnn：https://github.com/Tencent/ncnn

## 功能概览

- 无障碍服务：抓取当前屏幕 UI 树（全局/其他应用界面）
- 模型服务：
  - 本地 OpenAI 风格接口：`http://127.0.0.1:18080/v1/chat/completions`
  - 内置网页入口：`http://127.0.0.1:18080/`（从设备本机访问）

## 工程信息

- 应用名：`ncnn_llm_ctl`
- 包名：`com.example.ncnn_llm_ctl`
- 最低系统：Android 9（minSdk 28）
- ABI：仅 64 位（`arm64-v8a`、`x86_64`）

## 构建（Windows / Android Studio 默认安装）

用 Android Studio 直接打开仓库根目录：`d:\Android\ncnn_llm-android-ctl-mcp`

## 运行与使用

### 1）开启无障碍服务

打开 App 后：
- 若未开启无障碍：按钮显示 **“开启无障碍”**，点击跳转系统无障碍设置
- 开启成功后：按钮会自动变为 **“无障碍已开启”**（并禁用）

### 2）启动模型服务

选择模型后点击 **“启动模型服务”**：
- 启动过程中：显示 **“启动中…”**，并显示下载进度/速度
- 服务启动成功后：按钮变为 **“模型服务已启动”**（并禁用）

### 3）访问测试网页（OpenAI demo）

在设备上用浏览器打开：
- `http://127.0.0.1:18080/`

若从电脑访问（ADB 端口转发）：

```bash
adb forward tcp:18080 tcp:18080
```

然后在电脑浏览器打开：
- `http://127.0.0.1:18080/`

### 4）app测试
![logo](img/test.jpg)


## 免责声明

本项目依赖无障碍服务对系统/其他应用界面进行操作，请确保仅在你有权限且合法合规的场景中使用。

## 致谢
- futz12/ncnn_llm
- Tencent/ncnn
- EdVince/Stable-Diffusion-NCNN