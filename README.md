# 作业板 · HomeworkBoard

> 把当天的作业，一页摆到客厅电视上。

孩子不用问「今天什么作业」，家长不用在群里翻。手机或电脑上记一遍，电视自己同步，
遥控器按 OK 就能看配图。数据存在**你自己的坚果云**里，不经过任何第三方服务器。

<br>

<p align="center">
  <img src="docs/screenshots/tv-home.webp" width="860" alt="电视端：一屏显示当天全部作业，行尾带配图缩略图">
</p>

<p align="center">
  <sub><b>电视端</b>　一屏放下当天全部作业，字号按内容自动缩放；行尾是配图缩略图，遥控器按 OK 全屏翻页</sub>
</p>

<br>

| 手机端 · 记作业 | 手机端 · 改这条 | 手机端 · 全屏看图 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/phone-home.webp" width="215"> | <img src="docs/screenshots/phone-edit.webp" width="215"> | <img src="docs/screenshots/phone-photo.webp" width="215"> |
| 选科目、写标题、加图，一发就到电视 | 改文字、补图、删条目 | 缩略图点开就是大图，可左右翻页 |

<br>

| 电脑网页端 · 新增一条 | 电脑网页端 · 编辑这条 |
|:---:|:---:|
| <img src="docs/screenshots/web-main.webp" width="390"> | <img src="docs/screenshots/web-edit.webp" width="390"> |
| 左边列表、右边表单，一屏连写五条 | 点左侧任一条就能改，配图带缩略图 |

> 截图用的是演示数据，不是任何人的真实作业。

<br>

## 这是什么

一个家庭自用的小工具，解决两个很具体的问题：

1. **作业信息散落**：老师在群里发、家长口头转述，孩子记不全，家长回头还得再问一遍。
2. **孩子看手机不合适**：想让孩子自己确认作业，又不想把手机递过去。

所以做成「**手机 / 电脑录入 → 云端中转 → 电视大屏只读**」。电视端刻意做成只读，
遥控器也操作不了任何东西，孩子只能看。

<br>

## 功能

**电视端**

- 一屏显示当天全部作业，字号按内容自动放大缩小，3 米外看得清
- 每条作业可带多张配图（作业照片、试卷、参考答案），缩略图在行尾，按 OK 全屏翻页看
- 有图的条目显示「3 张」角标
- 长时间无操作不黑屏；开机自启，打开电视就在
- 断网时显示本地缓存，顶部标注「离线 · 数据为 20:58」
- 跟随系统深浅色（Android TV 系统锁深色，盒子/平板装则走浅色）

**手机端**

- 记作业：选科目 → 写标题 → 补充说明 → 加图（相册最多 9 张，或拍照）
- 图片自动压缩到长边 1600px / JPEG 80，并按 EXIF 方向转正（竖拍照片到电视上不会躺倒）
- 点缩略图全屏预览，可左右翻页、当场删除
- 今日提醒：整天的备注，如「明天带跳绳」
- 目标设备没开机也能发：进待发队列，下次联网自动补
- 「电视端显示到哪天」：一键把作业延长到假期结束
- 配置可一键复制/导出成文件，省得在电视上敲密码
- 深色模式、沉浸式状态栏

**电脑网页端**（`webapp/`，Windows / macOS / Linux 都能跑）

- 一个 `index.html`，**不联网、不碰账号密码**：直接写本地文件，坚果云客户端负责上传
- 批量录入：一屏连写五条，还能切前一天/后一天，周末把一周排好
- `⌘V / Ctrl+V` 直接粘贴截图，也能一次拖入多张
- 写入前先读后合并、写完回读校验，不会静默覆盖手机端写的内容
- 保存后由本地服务替它广播一声（UDP 47823，和手机端同一套暗号），电视 1~2 秒内就更新

**三端共用**

- 数据存在**自己的坚果云**（标准 WebDAV），可随时用浏览器或其它客户端查看、备份
- 条目级合并：两端同时改不同条目不会互相覆盖；同一条都改过则按时间取新，真的撞了才留一份「冲突副本」
- 节假日规则：周五、周六布置的自动管到周日；长假手动延长

<br>

## 工作原理

```
  ┌──────────┐   写入    ┌─────────────────┐   拉取   ┌──────────┐
  │  手机 App │ ───────► │  你的坚果云      │ ◄─────── │ 电视 App  │
  └──────────┘  WebDAV   │  (WebDAV 目录)   │  WebDAV  └──────────┘
                        └─────────────────┘
                                   ▲
                                   │ 写本地文件（坚果云客户端同步）
                        ┌──────────┴─────────┐
                        │ 电脑网页（浏览器）  │
                        └────────────────────┘

   局域网 UDP 广播（47823）──── 手机存完喊一嗓子 ────► 电视 1~2 秒内拉取
```

- 没有账号体系、没有后端服务、没有数据库。**就是一个 WebDAV 目录**。
- 手机存完会在局域网里发一个 UDP 广播，电视听到就立即来拉，正常 1~2 秒出画面。
  不需要填对方 IP，也不需要电视跑服务。路由器开了 AP 隔离（常见于访客网络）广播穿不过去时，
  可退回定时轮询（见下方配置）。
- 云端目录结构：

```
作业板/
├── manifest.json          索引：有哪些天、各多少条、各自管到哪天
├── day/
│   └── 2026-11-04.json    当天全部条目 + 备注
└── img/
    └── 2026-11-04/        配图，按 JPEG 原样存
        └── a1b2c3d4-1-1730000000.jpg
```

字段定义见 [`Model.kt`](core/src/main/kotlin/cn/yiweiqiu/homework/core/Model.kt)，
契约 v1，只增字段不改名，未知字段忽略。

<br>

## 快速开始

> **不想编译？** 直接去 [Releases](https://github.com/dujunily/homework-board/releases) 下打包好的：
> `homework-board-tv-*.apk`（电视）、`homework-board-phone-*.apk`（手机）、
> `homework-board-web-*.zip`（网页端，解压后双击启动脚本即可）。
>
> 资源文件名用英文，是因为 GitHub 上传 release 资产时会把中文名整段丢掉（会变成 `-.zip` 这种），
> 仓库里和本地 `dist/` 下仍然是中文名。

### 0. 你需要准备

| 项 | 要求 |
|---|---|
| 坚果云账号 | 免费版即可（WebDAV 可用），[注册](https://www.jianguoyun.com/) |
| 应用专用密码 | 坚果云网页 → 账户信息 → 安全选项 → 添加应用 → 生成。**用登录密码一定 403** |
| 编译环境 | JDK 17+（本项目用 21 验证）、Android SDK 35。Windows / macOS / Linux 都可以 |
| 网页端 | 只要一个现代浏览器（Chrome / Edge）；本地服务需要 Python 3.7+，Windows 没有 Python 也行 |
| 电视 | Android 6.0+（minSdk 23）。小米电视、Android TV、投影、电视盒子均可 |
| 手机 | Android 7.0+（minSdk 24） |

### 1. 编译

三个平台都有脚本，**Windows / macOS / Linux 行为一致**：自动找 JDK、自动找 Android SDK、
自动写 `local.properties`、打完按版本号改名放进 `dist/`。

```bash
git clone https://github.com/dujunily/homework-board.git
cd homework-board
```

| 你的系统 | 一条命令 | 或者双击 |
|---|---|---|
| macOS / Linux | `./scripts/build.sh` | — |
| Windows | `.\scripts\build.ps1` | `scripts\build.cmd` |

```bash
./scripts/build.sh           # 电视端 + 手机端 + 网页端，全打
./scripts/build.sh tv        # 只打电视端
./scripts/build.sh phone     # 只打手机端
./scripts/build.sh apk       # 两个 APK，不碰网页端
./scripts/build.sh web       # 只打包网页端（不需要 JDK / Android SDK）
./scripts/build.sh --help
```

产物：

```
dist/作业板-TV-0.2.16.apk          约 1.4 MB
dist/作业板-手机-0.2.18.apk        约 1.7 MB
dist/网页版/                       可直接托管 / 拷给别人用的网页端
dist/作业板-网页版.zip             同上，带了 UTF-8 文件名和可执行位
```

**脚本会替你做的三件容易踩的事**：

1. **挑对 JDK**。`JAVA_HOME` 常年还指着 JDK 8，而 Gradle 8.10 又跑不动 JDK 24+，
   所以脚本按 `21 → 17 → 22 → 23 → 18~20` 的顺序挑，挑不到才报错并把安装命令打出来。
2. **找 Android SDK**。依次看 `ANDROID_HOME`、`ANDROID_SDK_ROOT`、`local.properties`、
   各平台常见安装路径；认定标准是目录里有 `platforms/`。
3. **写 `local.properties`**。Windows 上会自动转义成 `sdk.dir=C\:\\Users\\…\\Sdk`，
   省得手抄反斜杠抄错。

<details>
<summary>不想用脚本，手动编译（点开）</summary>

```bash
# 告诉 Gradle SDK 在哪（这个文件不进仓库，每人本地一份）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
# echo "sdk.dir=$HOME/Android/Sdk" > local.properties         # Linux
# Windows 写 sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk

./gradlew :tvapp:assembleRelease      # 电视端
./gradlew :phoneapp:assembleRelease   # 手机端
./gradlew :core:test                  # 单元测试
```

产物在 `tvapp/build/outputs/apk/release/` 与 `phoneapp/build/outputs/apk/release/`。

</details>

> **关于镜像**：`gradle/wrapper/gradle-wrapper.properties` 里默认用腾讯云镜像下载 Gradle，
> 国内快。海外网络如遇卡住，把 `distributionUrl` 换回
> `https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip`。
>
> **关于签名**：release 复用 debug 签名，`clone` 下来直接能打包装机，不需要密钥。
> 代价是**不能覆盖安装别人打的包**（签名不同），换包要先卸载。
> 自己要发版就配一份正式 keystore，别提交到仓库。

### 2. 装到电视

```bash
# 电视打开「开发者选项 → USB 调试 / 网络调试」后用 adb 连，或直接 U 盘拷 APK 用电视文件管理器装
adb install -r tvapp-release.apk
```

### 3. 配置你自己的坚果云

电视端没有输入法，所以配置靠一个 JSON 文件。放这两个位置之一即可（优先第一个）：

```
/sdcard/Android/data/cn.yiweiqiu.homeworkboard/files/config.json
/sdcard/HomeBoard/config.json
```

内容就两项：

```json
{
  "username": "你的坚果云登录邮箱",
  "password": "应用专用密码"
}
```

- **服务器地址不用填**：缺省 `https://dav.jianguoyun.com/dav/作业板/`，云端没有这个目录会自己建。
  要用别的目录再写 `"webdavUrl"`。
- 可选：`"refreshIntervalMinutes": 0`（默认不轮询）、`"childName": "小朋友"`（电视左上角称呼）。

推上去：

```bash
adb push config.json /sdcard/Android/data/cn.yiweiqiu.homeworkboard/files/config.json
```

推不进去时，先让电视上的 App 启动一次（它会自己建目录），或改用 `/sdcard/HomeBoard/config.json`。

> ⚠️ **`config.json` 里是明文密码**，它等于一把能读写你坚果云指定目录的钥匙。
> 别提交到任何仓库、别发到群里。`refreshIntervalMinutes` 之外的字段都可以随手删。
> 本项目已在 `.gitignore` 里忽略 `config.json`，仓库里只提供不含密码的 `config.sample.json`。

**不想手敲？** 手机 App 的「设置 → 电视端」有「复制 / 导出文件」两个按钮，
把同一份 `config.json` 发到电脑再 push，两端账号天然一致。

### 4. 装手机端

```bash
adb install -r phoneapp-release.apk
```

首次打开在「设置」里填同一个坚果云账号即可（手机端可以正常打字，不用传文件）。

两端都连着同一个坚果云目录后，手机点「发到电视」，电视 1~2 秒内就会更新。

### 5. 电脑网页端（可选）

不需要编译，也不需要装任何东西。**Windows / macOS / Linux 三套入口逻辑一致**：
起一个只监听 `127.0.0.1` 的静态服务，然后自动开浏览器。

| 系统 | 双击 | 实际跑的是 |
|---|---|---|
| Windows | `webapp\启动-网页版.bat` | 有 Python 就 `py -3 serve.py`；没有就用 `serve.ps1`（PowerShell 内置，零依赖） |
| macOS | `webapp/启动-网页版.command` | `start.sh` → `python3 serve.py` |
| Linux | `webapp/start.sh` | 同上 |

命令行也行：

```bash
python3 webapp/serve.py            # 自动挑空端口（8123 起）
python3 webapp/serve.py 8123       # 指定端口
python3 webapp/serve.py --no-open  # 只起服务
```

启动后点「选择文件夹」，选中坚果云同步文件夹里的 `作业板` 目录即可（只需选一次）。

**必须用 Chrome 或 Edge**（Safari / Firefox 没有 File System Access API）。
**也必须是 `http://127.0.0.1`，不能直接双击 `index.html`**——`file://` 不是安全上下文，
「选择文件夹」拿不到写入授权。

> 细节和自测说明见 [`webapp/README.md`](webapp/README.md)。
> 网页端完全不联网、不碰账号密码，只写本地文件，由坚果云客户端负责上传。

### 6. 开机自启（可选，但建议）

App 会监听开机广播，但 **Android 10+ 会拦「后台启动界面」**，需要授权一次，任选一种：

```bash
# 方式 A：adb，一次即可，重启后仍有效
adb shell appops set cn.yiweiqiu.homeworkboard SYSTEM_ALERT_WINDOW allow
```

方式 B：电视上 设置 → 应用 → 作业板 → 权限 → 悬浮窗 / 显示在其他应用上层 → 允许

> 申请这个权限不是为了画悬浮窗，而是它属于安卓官方允许「后台启动界面」的豁免条件。
> 不授权也能用，只是开电视后要自己去桌面点一下图标。Android 9 及以下不受此限制。

小米电视另需确认两处（机型不同名称略有差异）：

- 设置 → 通用 → 开机启动项 / 自启动管理 → 允许「作业板」
- 设置 → 应用 → 作业板 → 电池优化 / 后台限制 → 不限制

<br>

## 使用

### 遥控器

| 键 | 作用 |
|---|---|
| ↑ ↓ | 上下移动焦点，超出一屏自动跟随滚动 |
| OK | 该条有图则打开全屏看图；无图不响应 |
| ← → | 看图时切换图片 |
| 返回 / OK | 看图时退出看图；列表页需**连按两次**才退出 App（防误触） |
| 菜单键 | 立即同步一次 |

- 打开 App 时列表**没有焦点框**，是一张干净清单；按一下方向键才开始显示焦点框。
  焦点其实一直在第一条上，所以第一下 ↓ 就是到第二条，不会「第一下被吃掉」。
- 有图的条目，缩略图在行尾，右下角标「3 张」。
- 顶栏右侧「↻ 刷新」：按 ↑ 从第一行移上去即可聚焦，按 OK 立即同步。今天没作业时焦点默认落在这里。
- 列表页连按两次返回才退出，是为了防止孩子乱按退出后不知怎么回去。

### 手机端

- 首页右下角 ＋ → 选科目 → 写标题 → 补充说明 → 加图 →「发到电视」
- 点作业条目进入编辑，可改可删
- 点缩略图全屏看，顶部有页码和「删除这张」
- 首页列表下方的「今日提醒」写整天的备注，写的是和网页端、电视端同一个 `notes` 字段

### 电脑网页端

- 左边是当天列表，右边是编辑表单；点列表项即可改，改完「保存并同步」
- 右上角 `‹` `›` 切前一天/后一天（**只有电脑端能改日期**，适合周末把一周的作业排好），
  不在今天时会出现「回到今天」
- 图片可以 `⌘V / Ctrl+V` 直接粘贴截图，也可以一次拖入多张
- 同一个「今日提醒」入口，写的是和手机端、电视端同一个 `notes` 字段
- 「显示到哪天」三档：只今天 / 到这周日 / 再连休 7 天（和手机端一致）

### 节假日与「管到哪天」

作业按天存，但**看的动作不按天发生**：周五布置的作业，周六周日还在做；国庆布置的要管七天。
所以每份作业带一个 `validUntil`（管到哪天，含当天）：

- **默认规则**：周五、周六布置的 → 自动管到周日；其余 → 只管当天。不需要任何操作。
- **电视端怎么挑**：今天有自己的一份就显示今天；没有就回看最近 14 天，挑仍有效的最晚一天，
  并标出这是哪天。新作业一布置，旧的自动翻篇。最多回看 14 天，防止一条陈年作业永远挂在电视上。
- **长假要手动延**，因为调休没有规律可推：手机端首页日期旁的「只今天」标签 → 三档选择
  （只今天 / 到这周日 / 再连休 7 天）。保存后提示「电视端会显示到 X月X日」。

举例：9 月 30 日（周三）布置国庆作业，默认只管当天；选「再连休 7 天」→ 管到 10 月 7 日，
整段假期电视上都是这份；10 月 8 日布置新作业，自动切到新的。

### 同步时机

电视**不主动定时问坚果云**（`refreshIntervalMinutes` 默认 `0`），只在下面几种情况联网：

| 触发 | 说明 |
|---|---|
| 手机保存成功 | 局域网 UDP 广播，电视听到就来拉，1~2 秒 |
| 顶栏「↻ 刷新」/ 菜单键 | 随时手动拉一次 |
| 冷启动、回到前台 | 各拉一次，60 秒内去重 |
| 跨零点 | 00:00:05 切到新的一天并拉一次 |
| WorkManager | 每 6 小时兜底 |

这样做是因为固定轮询一天上百次请求，绝大多数时候云端根本没变化。数据未变更时用
`If-None-Match` 走 304，所以就算开了轮询也不费流量。

<br>

## 隐私

这一点值得单独说清楚：

- **没有服务器**。项目里没有任何自建后端、埋点、统计、广告、第三方 SDK 上报。
  代码里除了坚果云 WebDAV 地址，没有别的外连目标。
- **凭据只存在本机**。手机端存 SharedPreferences，电视端读你 push 的 `config.json`。
  不会上传到除你自己坚果云之外的任何地方。
- **仓库里没有凭据**。`.gitignore` 已忽略 `config.json`、`*.jks`、`*.keystore`、`local.properties`。
  提交前请自行再确认一遍，不要用 `git add -f` 强行加。
- **图片存在你自己的云盘**，不经过第三方图床或压缩服务。
- 电视端是**只读**的：遥控器无法修改、删除任何内容。

### 已知的安全取舍（请知悉）

- `config.json` 里是**明文密码**。这是电视端无输入法的妥协。请务必使用**应用专用密码**
  （不是登录密码，且只能访问你指定的目录、可随时在坚果云后台吊销）。
- 手机端的密码存放在普通 SharedPreferences 中，**未使用** `EncryptedSharedPreferences`。
  手机已 root 的情况下可被读取。这是已知待改进项。
- 电视端的 `config.json` 用 adb push 写入应用私有目录，需要 USB 调试权限才能读到。

<br>

## 已知限制

- **电视端只有一个「今天」，没有历史浏览**。做完的作业自动翻篇，要看旧的得去云端看 json。
- **照片不做 OCR**，作业内容是自己打进去的文字，配图只是附件。
- **没有多孩子支持**：一个坚果云目录对应一份作业，多孩请用两个目录（两份配置）。
- **网页端只支持 Chrome / Edge**，且必须通过本地服务打开（`file://` 拿不到文件夹写入权限）。
- **同一天别在手机和网页同时编辑**：两边都是「读-改-写」，几乎同时保存时后写的一方会覆盖前一方。
- 局域网广播穿不过 AP 隔离（访客网络常见），退回轮询即可。
- 电视端界面里没有深浅色开关，跟随系统。Android TV 系统锁定深色，所以电视上就是深色。
- 手机端的「发到电视」依赖两端在同一局域网，或者靠 WorkManager 的定时兜底。

<br>

## 开发

```
homework-board/
├── core/       共享逻辑（Android Library）：WebDAV 客户端、数据模型、同步引擎、缓存
├── tvapp/      电视端（Compose for TV 风格手写焦点）
├── phoneapp/   手机端（Compose Material3）
├── webapp/     电脑网页端（单文件 index.html + 本地静态服务，三平台启动器）
└── scripts/    构建脚本（build.sh / build.ps1 / build.cmd）
```

- Kotlin + Jetpack Compose，`kotlinx.serialization` 做数据契约
- 两端共用 `core`，数据格式与合并算法只有一份实现
- 单元测试：`./gradlew :core:test`（`DayRule` 的节假日推算等纯逻辑）
- 网页端自测：`node webapp/test-logic.js`（不需要浏览器，30 条断言）

**改代码时请留意**（都是踩过的坑）：

1. **坚果云 WebDAV 的根段 `/dav` 永远不要 MKCOL**，会对已存在目录返回 403，容易被误判成「认证失败」。
   建目录只建业务目录，且 403/405 都当作「已存在」跳过。
2. **ETag 必须原样透传**，坚果云返回的是弱校验器 `W/"..."`，去掉引号再拼回去会导致每次都 412。
3. **深色主题下不要写死颜色**。用 `TvColors.X` / `phoneInk` 这类跟随主题的属性；
   写死浅色值在夜间模式就是黑底黑字。
4. **电视端字号自适应**依赖 `contentHeight()` 的高度估算，改布局要同步改它，否则「算得下、其实溢出」。
5. **手机端「删除这张」这类底部按钮**，真机上会被输入法挡住（模拟器不弹键盘，看不出来），
   重要操作放顶部工具栏更稳。
6. release 必须开 `isMinifyEnabled` + `isShrinkResources`，否则 `material-icons-extended`
   会把包体从 1.9 MB 撑到 12 MB。
7. **脚本的换行与编码不要随手动**。`.bat` 必须 CRLF（cmd 的 `goto`/标签对 LF 不可靠）；
   `.ps1` 必须带 UTF-8 BOM（Windows PowerShell 5.1 没 BOM 会按 ANSI 解，中文注释直接变乱码）；
   `.sh` / `.command` 必须 LF 且可执行。这些都在 `.gitattributes` 里固化了，改脚本时别绕过。
8. **改了网页端就跑一遍** `node webapp/test-logic.js`。CI 会在 ubuntu / macos / windows
   三个系统上各跑一次同样的流程（自测 → 打包 → 校验 zip 里的中文名和可执行位）。

<br>

## 常见问题

**Q：电视上显示「认证失败」？**
A：八成是用了登录密码。必须到坚果云网页 → 账户信息 → 安全选项 → 添加应用，生成**应用专用密码**。

**Q：手机能发，电视没反应？**
A：① 两端是否填的同一个目录（对比「设置」里的地址）；② 路由器是否开了 AP 隔离；
③ 把 `refreshIntervalMinutes` 设成 `5` 试试定时轮询；④ 按电视顶栏「↻ 刷新」手动拉一次。

**Q：电视打开是空白的？**
A：今天没布置作业，或者上一份已过期（超过 `validUntil` 且距今 14 天以上）。
在手机上把有效期延长，或直接记一条新作业。

**Q：装新版提示「应用未安装」？**
A：签名不同（你装的是别人打的包）。先卸载再装。**同包名不能降级安装**，回退旧版也要先卸载。

**Q：能不用坚果云吗？**
A：任何标准 WebDAV 服务都行，改 `webdavUrl` 即可。但注意：本项目只针对坚果云验证过
（路径建目录的 403 行为、弱 ETag 这两处的兼容处理就是为它写的）。

**Q：会一直联网偷传数据吗？**
A：不会。全项目外连目标只有你配置的 WebDAV 地址。可以看 `core/.../WebDavClient.kt` 自己核对。

**Q：Windows 上双击 `启动-网页版.bat` 一闪就没了？**
A：先看它有没有报「没找到可用的 Python」。正常情况下没装 Python 也会自动退回 PowerShell
版服务（`serve.ps1`），不需要装任何东西。若确实闪退，在该目录按住 Shift 右键 →
「在此处打开 PowerShell 窗口」，手动跑 `powershell -ExecutionPolicy Bypass -File serve.ps1`，
报错信息会留在窗口里。

**Q：macOS 双击 `启动-网页版.command` 提示「无法打开」？**
A：右键 → 打开 → 再点一次「打开」（只需一次）。若提示找不到 Python，先跑 `xcode-select --install`。

**Q：网页端「选择文件夹」点了没反应 / 提示不支持？**
A：① 必须用 Chrome 或 Edge；② 必须通过 `http://127.0.0.1:端口` 打开，
直接双击 `index.html`（`file://`）拿不到文件夹写入权限；③ Safari / Firefox 目前没实现这个 API。

<br>

## 许可

[MIT](LICENSE)

随意使用、修改、再分发，包括商用。出问题不担责。

<br>

---

如果这个项目帮你省下了每天在群里翻作业的时间，欢迎提 Issue 或 PR。
