# AutoClicker - 自动连点器

一款 Android 自动连点器应用，支持坐标模式、OCR 文字识别模式和抢购模式。

## 功能概览

### 1. 首页 - 权限管理
- **无障碍服务** Switch：检测并引导开启无障碍服务
- **悬浮窗权限** Switch：检测并引导授权悬浮窗
- **底部工具栏**：连点器 / 抢购 入口

### 2. 连点器页面
支持两种模式切换：

#### 坐标模式（默认）
- 设置点击间隔（1~60000ms）
- 设置点击次数（无限/有限）
- 点击"开始连点"后弹出悬浮球，拖动到目标位置单击开始连点
- 单击悬浮球停止连点

#### OCR 模式
- **目标文字**：输入需要匹配的文字
- **匹配方式**：精确匹配（完全相同）或包含匹配（文字中包含目标）
- **扫描间隔**：多久截屏识别一次（200~10000ms，默认500ms）
- **点击次数**：匹配后点击的次数（默认1次）
- **点击间隔**：匹配后连续点击的间隔（50~60000ms）
- 工作流程：
  1. 输入目标文字 → 点击"开始OCR识别"
  2. 授权屏幕录制权限
  3. 切到目标页面，应用自动截屏识别
  4. 识别到匹配文字后，自动计算文字区域中心坐标并点击
  5. 实时显示扫描次数、匹配状态和点击计数

### 3. 抢购页面
- 目标坐标设置（手动输入/悬浮窗定位）
- 点击间隔、次数、提前量设置
- 支持定时抢购模式

## 使用步骤

### 安装
1. 用 Android Studio 打开项目 → Sync Gradle → Run
2. 如遇 `INSTALL_FAILED_USER_RESTRICTED`，在手机 **设置→开发者选项** 中开启 **USB安装**

### 权限授权
1. 打开 APP → 开启「无障碍服务」Switch → 跳转系统设置开启
2. 开启「悬浮窗权限」Switch → 跳转系统设置授权

### 坐标模式连点
1. 点击底部「连点器」→ 设置间隔和次数
2. 点击"开始连点" → 悬浮球出现
3. 拖动悬浮球到目标位置 → 单击开始 → 再单击停止

### OCR 模式
1. 点击底部「连点器」→ 切换到"OCR模式"
2. 输入目标文字，选择匹配方式
3. 设置扫描间隔和点击参数
4. 点击"开始OCR识别" → 授权屏幕录制
5. 切到目标页面，等待自动识别和点击

## 技术栈

- Kotlin / Android SDK 24~33
- ML Kit Text Recognition (Chinese) - OCR 文字识别
- MediaProjection API - 屏幕截屏
- AccessibilityService - 模拟点击手势
- Foreground Service - 后台截屏保活

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/autoclicker/
│   ├── MainActivity.kt              # 首页 - 权限管理
│   ├── AutoClickerActivity.kt       # 连点器（坐标模式 + OCR模式）
│   ├── RushBuyActivity.kt           # 抢购页面
│   ├── ClickAccessibilityService.kt # 无障碍服务（悬浮球+模拟点击）
│   ├── ScreenCaptureService.kt      # 截屏前台服务（OCR专用）
│   └── OcrClickEngine.kt           # OCR识别+文字匹配+坐标计算
└── res/
    ├── layout/activity_main.xml
    ├── layout/activity_auto_clicker.xml
    ├── layout/activity_rush_buy.xml
    ├── xml/accessibility_service_config.xml
    ├── drawable/...
    └── values/...
```
