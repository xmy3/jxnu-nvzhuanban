# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project at a glance

**女专办 (Nvzhuanban)** — unofficial Android client for the JXNU (江西师范大学) academic affairs system, modeled after the "酱紫办" app. Single Gradle module (`:app`), Kotlin + Jetpack Compose + Material 3, namespace `cn.jxnu.nvzhuanban`. min SDK 26, target/compile SDK 36, Java 11, Compose BOM `2026.05.01`, Kotlin `2.1.21`, AGP `8.7.2`.

## Build & run

Gradle wrapper is pinned to 8.9 (`gradle/wrapper/gradle-wrapper.properties`); use the bundled `./gradlew` (Unix) or `gradlew.bat` (Windows). Useful tasks:
- `./gradlew :app:assembleDebug` — debug APK
- `./gradlew :app:compileDebugKotlin` — fastest compile-only smoke test
- `./gradlew :app:test` — JVM unit tests (parser regression suite, runs in seconds)
- `./gradlew :app:connectedAndroidTest` — instrumentation tests (needs a connected device/emulator)

Run a single test class: `./gradlew :app:testDebugUnitTest --tests "cn.jxnu.nvzhuanban.data.network.pages.SchedulePageTest"`.

Release signing reads its config from **outside the repo by default**: `$USERPROFILE/.android/nvzhuanban-signing/keystore.properties` (the `storeFile` path resolves relative to that file). Override with `-PnvzhuanbanKeystoreProperties=…` or the `NVZHUANBAN_KEYSTORE_PROPERTIES` env var; it also falls back to `keystore.properties` at the repo root. The repo only ships `keystore.properties.example`. With no keystore resolved, `hasReleaseSigning` is false and the release build stays **unsigned** (debug is unaffected).

**Releases** are cut locally (CI does not build them): bump `versionCode`/`versionName` in `app/build.gradle.kts` (encode as `major*1000 + minor*10 + patch`, e.g. 1.2.0 → 1020) → add a `CHANGELOG.md` section → `./gradlew :app:assembleRelease` (output under `app/build/outputs/apk/release/`) → push a `vX.Y.Z` tag → `gh release create vX.Y.Z` with the APK attached. In-app update detection (`GitHubUpdateClient` + `UpdateRepository`) polls GitHub `releases/latest` and compares `tag_name` (minus the `v` prefix) against the installed `versionName`, so the release must be **published** (not draft/prerelease) to be picked up.

CI is GitHub Actions (`.github/workflows/android.yml`): on push to `main`/`master` and on every PR it runs `:app:test` + `:app:compileDebugKotlin` + `:app:lintDebug` (it does **not** build or publish release artifacts — see "Releases" above). JVM unit tests cover **every Page parser**（one `*PageTest.kt` per scraper under `data/network/pages/`, ArticleDetail included）plus auth/session（`CasLoginClient.parseExecutionFromHtml`、`JwcResponseGuard`、`JwcError`、SessionRecovery）、cookie jar、update 链路（SemVer / GitHubRelease / UpdateRepository）和 widget snapshot/scheduler. When changing a parser, update or extend the corresponding `*PageTest.kt`. Instrumentation tests are minimal but real: `MainActivitySmokeTest` + `DeviceFingerprintInstrumentedTest`（`app/src/androidTest/`）. `app/build.gradle.kts` 里 `lint { disable += "NullSafeMutableLiveData" }` 是 AGP 8.7 + lifecycle 2.10 的 lint 崩溃规避——升 AGP 后再评估移除。

Startup uses a **hand-written Baseline Profile**（`app/src/main/baseline-prof.txt`，无 macrobenchmark 模块）+ `androidx.profileinstaller`. 重命名/移动启动链路上的类（Application / MainActivity / AppNav / theme / schedule 首屏 / data storage+network）时要同步 profile 规则。

Test HTML fixtures live in **`app/src/test/resources/samples/*.html`** and are loaded via classloader, not the filesystem — use the shared helper `sampleHtml("name.html")` from `TestSamples.kt` rather than `File("samples/...")`. The fixtures are **PII-redacted** (假学号 `2024050001` / 假教号 `020001` / 假姓名 张三/李四 等) so they can sit in the repo and run in any CI. If you add a new fixture by saving real HTML, scrub 姓名 / 学号 / 教号 / 邮箱 / base64 `UserNum` before committing — search for the original PII to confirm.

## Architecture

### Big picture

This is **a thin Compose UI wrapping HTML scrapers of the JXNU academic-affairs ASP.NET WebForms site**. There is no backend of our own; every screen ultimately calls a `Repository` that hits `jwc.jxnu.edu.cn` (or its CAS host `uis.jxnu.edu.cn`), parses the HTML with Jsoup, and returns plain Kotlin data classes.

Layering (`app/src/main/kotlin/cn/jxnu/nvzhuanban/`):

```
data/
  model/         pure data classes (Course, Grade, TestGrade, Exam, MakeupExam, Announcement,
                 ArticleDetail, UserProfile, AuthState, TrainingPlan, Student, Teacher,
                 CalendarEntry, SectionTimetable, AppRelease)
  network/       OkHttp client + CAS login + cookie/credential persistence
                 (AuthStorage, SecureCredentialStore, PersistentCookieJar, JxnuUrls,
                  JwcResponseGuard, SessionRecovery all live here, not in storage/)
    pages/       Jsoup-based parsers — one per scraped ASP.NET UserControl:
                 Schedule, Grade, TestGrade, Exam, MakeupExam, Announcement, ArticleDetail,
                 PictureNews, UserDefault, Calendar, GraduationAudit,
                 Student/TeacherSearch, Student/TeacherDetail
  repository/    in-memory cache + Mutex + suspend API on top of pages/ (one repo per page,
                 plus AuthRepository which orchestrates login/restore/logout)
  storage/       SharedPreferences wrappers (ThemePrefs, AvatarPrefs, CourseOverridesStore,
                 ScheduleHeightPrefs, AnnouncementReadAnchor, AnnouncementUnreadState, UpdatePrefs,
                 PeopleSearchHistoryStore)
  widget/        Snapshot store for the home-screen widget (file-backed JSON)
ui/
  navigation/    AppNav.kt — single NavHost + bottom-bar Scaffold + Routes object
  screens/<feature>/  one directory per feature (screen + ViewModel; 大页面拆多文件——
                 schedule/ 有 ScheduleScreen/ScheduleGrid/CourseDetailSheet/WeekEditorSheet/
                 UpcomingExamBanner 五个); features include schedule, announcement, profile,
                 grades, exams, login, trainingplan, calendar, peoplesearch, students,
                 teachers, userschedule, classroom
  components/    shared Loading/Error/Empty/StateScaffold, RefreshIconButton, RemoteJwcImage,
                 FullScreenImageViewer, BackNavigationIcon, PersonDetailHeader/LabeledInfoCard,
                 UiStateViewModel（load/refresh 型 VM 的基类，见 Repository conventions）
  widget/        Glance widget (TodayScheduleWidget) reading the snapshot store
  theme/         Color/Type/Theme + ThemeMode (system/light/dark)
NvzhuanbanApp.kt   Application: 并行预载启动 prefs 文件（STARTUP_PREFS_FILES——新增/改名 prefs
                   文件要同步这份清单，漏配只退化为串行），inits AuthRepository, kicks off
                   sessionRestore Deferred, warms up announcement first-page for the red dot
MainActivity.kt    SplashScreen + edge-to-edge + theme; awaits sessionRestore before showing AppNav
```

### Auth flow (CAS SSO + RSA)

Login is **not** a direct POST to the academic-affairs site — it must go through CAS:
1. `GET uis.jxnu.edu.cn/cas/jwt/publicKey` for the RSA public key.
2. `GET uis.jxnu.edu.cn/cas/login?service=…` to get the `execution` token (e1s1).
3. RSA-encrypt the password, prepend the literal string `__RSA__`, base64 it as the body's `password` field.
4. POST credentials with `fpVisitorId` (device fingerprint), `failN=-1`, `_eventId=submit`, etc. No captcha by default.
5. CAS issues a service ticket and 302s back to `jwc.jxnu.edu.cn/sso/login.aspx`, which then drops a session cookie and lands on `/User/Default.aspx`.

`CasLoginClient` performs this dance; `AuthRepository` orchestrates retry-from-saved-credentials in `tryRestoreSession()`. Saved credentials live in `SecureCredentialStore` (EncryptedSharedPreferences + Android Keystore master key). `jwc.jxnu.edu.cn` is publicly reachable — **no campus network or VPN required**; do not surface such hints in error messages or help text.

**会话恢复阶梯（v1.5.0 起）**：任何静默重登都先走 `CasLoginClient.tryRefreshViaSso()`（不清 cookie、GET `casLoginEntry()`，本地 CAS TGC 仍有效时免密换新 jwc session，再以 `/User/Default.aspx` 解出学号确认），失败才退回完整账密 `login()`。`login()` 起手 `clearForHost(CAS_HOST)` 的语义保持不变（强制全新登录）。**只有账密这一步**受 `AutoLoginThrottle` 指数退避保护；SSO 续票有本地 `hasCasTgc()` 前置检查，TGC 不在就直接跳过不发请求。

`CasLoginClient.Result` 是**三态**（v1.5.0 起，替代旧 `Failure(isAuth)`）：`Success` / `InvalidCredentials`（CAS 明确拒绝凭证 → 上层清掉保存的密码）/ `Transient`（网络异常、TLS 失败、jwc 5xx、登录页结构异常、落点未识别、以及**验证码/风控/系统繁忙类拒绝** → 保留凭证）。落回 `/cas/login` 的分类由 pure function `classifyCasRejection` 决定：**白名单降级**——默认 `InvalidCredentials`（错误文案抓不到也算，防「改密码后旧密码无限重试」的 fail-open），仅命中「验证码/滑块/系统繁忙/频繁/稍后」这类对密码正确性中立的标记才降 `Transient`。新增失败路径时先想清楚归哪一态。

`AuthRepository.tryReauthSilently()` 返回 `ReauthOutcome` 三态（`Success`/`Transient`/`AuthRejected`），由 `JwcClient.withSessionRecovery` 消费：`Success` → 重放原请求；`AuthRejected`（无凭证/密码被拒） → `notifyExpired()` 踢登录页；`Transient` → **不踢**，转抛 `JwcError.Network`（绝不透传「登录已过期」文案误导用户），UI 走可重试错误态。`getBytesAuth`（图片）传 `notifyOnAuthRejected=false`——装饰性请求失败永不踢人。**并发合并**：`SessionRecovery.reauthMutex` 串行化并发 reauth，每个进锁者先 `probeSession()` double-check——会话已被前一个修好就直接 `Success`，不再连环登录。`expireSession()` 同样有 probe 守卫：踢人清 cookie 前先复验，会话其实活着（被并发 reauth 刚续上）就直接放行。

`CasLoginClient.probeSession()` 是**三态**（替代旧 `validateSession(): Boolean`）：`Valid(html)`（携带首页 HTML，`tryRestoreSession` 直接解析省一次 GET）/ `Invalid`（无 jwc cookie、被踢登录页、jwc 5xx——**确证失效**）/ `Unreachable`（纯 IOException——网络够不着，状态未知）。映射纪律：`JwcException` 一律 `Invalid`，只有非 Jwc 的 `IOException` 才 `Unreachable`——弄反会把确定已死的会话乐观放行，用户永远进不去也不被提示重登（注意 `JwcException` 继承 `IOException`，catch 顺序必须 JwcException 在前）。

**冷启动乐观放行**：`tryRestoreSession()` 在 `Unreachable`（或 `Invalid` 且重登 `Transient` 失败）且 `creds.load() != null` 时，用降级 profile（学号来自保存凭证、姓名占位）直接置 `LoggedIn` 进主界面——课表走 widget 快照离线兜底、`ProfileViewModel` 姓名自愈、网络恢复后业务请求自然 reauth。身份门槛**只认 `creds`**（logout 会清），绝不用 `lastUsername`/`lastLoggedUser`（会把已登出用户复活）；乐观放行路径**不写** `storage.lastLoggedUser`（没发生过验证登录，无权动换号锚点）。

登录页有「使用已保存的密码登录」一键重登（`AuthRepository.loginWithSavedCredentials()`）——它走**手动登录通道**（`login()`，驱动 Loading/LoggedIn、不受 throttle 冷却阻挡、成功重置计数），不要改走 `tryReauthSilently`（自动通道受节流，用户点了会「没反应」）。

`UserDefaultPage.parse(studentId, html)` will self-extract the student id from `<span id="lblUserInfor">欢迎您，(学号,Student) 姓名</span>` when the `studentId` argument is blank; this is how `tryRestoreSession` works when cookies are valid but the user had unchecked "remember me". Do **not** pass a non-empty-but-wrong id — that takes precedence over the HTML.

`CasLoginClient.probeSession()` reuses `JwcResponseGuard`'s sniffing — a 200 that is actually a login page (or a redirect to CAS / SSO re-login) counts as **Invalid**, so a half-dead session is forced to re-auth instead of being let through. A let-through session silently degrades later fetches (e.g. the Profile name falling back to the placeholder 「同学」, college info stuck "加载中").

### Cookie persistence

`PersistentCookieJar` keeps everything in memory (`ConcurrentHashMap<domain, MutableList<Cookie>>`) and writes a TSV to `filesDir/jxnu_cookies.tsv` via a dedicated daemon thread coordinated by two booleans (`dirty`, `writing`). The protocol matters: any cookie mutation **must** go through `saveFromResponse` / `clearForHost` / `clearAll`, which call `persistAsync()` — bypassing these and mutating `byDomain` directly will drift memory away from disk. The two-flag design exists specifically to avoid losing writes that arrive while a flush is in flight (rapid cookie deltas during the CAS redirect chain).

### Data source map

Bottom bar has **3 main tabs**: 课表 / 通知 / 我的 (see `AppNav.kt`'s `TABS`). Every other screen — 成绩 / 考试 / 培养方案 / 师生查询 / 校历 / 开课查询 / 空闲教室 / 学生详情 / 教师详情 / 看他人课表 — is reached from「我的」(ToolsBlock cards) or from a search result, and is **not** in `mainRouteSet` (so the bottom bar auto-hides and the screen owns its own back arrow). All these features still have independent ViewModel / Repository / parser layers.

| Entry | Endpoint / source | Auth needed? | Notes |
|---|---|---|---|
| 课表 (Schedule) | `User/default.aspx?…uctl=MyControl\xfz_kcb.ascx` (GET + POST for semester switch) | Yes | ASP.NET WebForms postback; we round-trip `__VIEWSTATE`/`__VIEWSTATEGENERATOR`/`__EVENTVALIDATION` to switch semester. **The visual grid has no week-of-semester info and no credits** — every Course defaults to `weeks = 1..18` and `credit = 0f`; `ScheduleRepository.enrichWithCredits()` later overlays credits from the grade page, and `CourseOverridesStore` overlays user-edited weeks (see "Local overrides" below). |
| 成绩 (Grades) | `MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx` | Yes | `GradeRepository` caches in memory; shared by `ScheduleRepository.enrichWithCredits` and `ProfileViewModel.enrichFromGrades`. `refresh()` bypasses the cache; plain `fetchAll()` reuses it. A parallel `TestGradeRepository` + `TestGradePage` powers the「考试出分」sub-tab from a separate UserControl. |
| 考试 (Exams) | `User/default.aspx?…uctl=MyControl\xfz_test_schedule.ascx` + 补缓考 from `MyControl\xfz_bukao_xx.ascx` | Yes | The `HH:mm` in the parsed date is **not reliable** (teaching office doesn't fill real exam times); `Exam.statusAt` and the UI deliberately ignore time-of-day and reason only about the date. 学期考试 + 补缓考 are merged in `ExamsViewModel` and shown as one list. |
| 通知 (Announcements) | 列表 `Portal/ArticlesList.aspx?type={Jwtz\|Jwgg\|Jxfc}` + `Portal/ArticlesPictureNews.aspx?page=N`；详情 `Portal/ArticlesView.aspx?id=…` | 列表 **No** (public page)；详情通常需登录态 | **Four** sources merged by date: 通知 / 通告 / **教务风采(Jxfc)** / 图文新闻. **Page 1 is two-staged** via `AnnouncementRepository.firstPageFlow()` — partial emit = 通知 + 通告 + 风采 合并 (三者同 `ArticlesList` 端点), final emit = + 图文新闻 (slower接口 + 9 张缩略图). 风采在 partial 里是 **best-effort**（`runCatching{show.await()}` 失败当空列表）——它虽与通知/通告同端点但独立 socket，单路抖动不该拖垮整张 feed；base=通知+通告 才是 partial 硬依赖. `_latestList` is written at partial *and* final (partial provides a degraded-but-usable feed for the red-dot subscriber if 图文 fails). `fetchAll(1)` waits for the final value; `fetchAll(page>=2)` is single-shot 4-way concurrent (风采同样 best-effort). `AnnouncementViewModel.load/refresh` collect the flow so partial 列表 renders 1~2s sooner; **anchor 只在终值时写** (don't migrate that to partial). **无效 type 陷阱**：`ArticlesList.aspx` 对无效/未知 type **静默回退 Jwtz 全量**（不报错），若 Jxfc 栏目将来被撤，风采路会返回与通知重复的条目——`dedupeShowcase(showcase, base)` 丢掉与通知/通告同 id 的风采项防列表成双. 详情页改为**原生 Compose** 渲染：`ArticleDetailPage` 抽内层 `#main-content` 的 标题/时间/段落/表格/图片/附件，`ArticleDetailRepository` 走 `getHtmlAuth`（会话失效自动 reauth）+ 16 篇 LRU 缓存，`AnnouncementDetailScreen` 用 LazyColumn + AnnotatedString (`LinkAnnotation.Url` 受控走 `openExternalHttpUrl`) 渲染。图片走 `RemoteJwcImage` 复用 session cookie。**未登录**时 jwc 对详情返回的是 HTTP 200「该文档需要登录后再查看」占位页（同域、不重定向，不被 `JwcResponseGuard` 判过期）：`ArticleDetailPage` 按标题特征置 `ArticleDetail.requiresLogin`，`ArticleDetailRepository` 先 `SessionRecovery.tryReauthSilently()` 静默重登重取、仍占位则**不缓存**，`AnnouncementDetailScreen` 渲染「去登录」引导（导向 **app 内** `Routes.LOGIN`，不再跳网页教务处）。正文 `LazyColumn` 用 `SelectionContainer` 包裹支持长按选中/复制（可见区域内，跨回收边界会断）。正文**内联色**（jwc 通知是 Word 粘贴出身，文字常带按**白底**配的 `color:` / `<font color>`）由 `AnnouncementDetailScreen.toSpanStyle` 按 WCAG 对比度过滤：内联色在当前主题背景上对比度 <3.0（如黑/深灰字落在暗色背景）时回退主题 `onSurface`，避免暗黑模式「黑字黑底」；红色等强调色对比度够则原样保留。**列表分页（v1.3.0 重写）**：加载更多由 UI 侧 `derivedStateOf` 距底 3 项触发——它**只读 `listState.layoutInfo`**（快照状态），不要在 lambda 里捕获 list 参数（旧实现因捕获过期引用导致连锁抓全所有页）；loadMore 失败有 5s 冷却；搜索态禁用自动翻页（footer 按钮 opt-in）。正文 `<img>` 的 width/height 属性会解析进 `ArticleBlock.Image`（widthPx/heightPx/aspectRatio），UI 用它做零跳变预占位——属性缺失退回 `heightIn(min=160.dp)`。 |
| 我的 (Profile) | `User/Default.aspx` for name/student-id only; **学院/专业/班级 come from the grades page** (`#_ctl6_lblMsg`) | Yes | `UserDefaultPage` parses `<span id="lblUserInfor">欢迎您，(学号,Student) 姓名</span>` from the home page (this is the **only** info that page exposes). `ProfileViewModel.enrichFromGrades()` then fills college/major/className/cumulativeCredits in the background, with state tracked by `EnrichStatus` (Loading/Failed/Idle) — on failure the UserCard shows「加载失败，点击重试」rather than spinning「加载中」forever. A failed home-page fetch degrades the name to the placeholder `PLACEHOLDER_USER_NAME`（「同学」）; on entering the screen, if `hasPlaceholderName`, `AuthRepository.refreshProfile()` re-fetches the home page to self-heal, and the grades-page meta is also allowed to backfill the name. |
| 培养方案 (TrainingPlan) | `MyControl\Xfz_Xueji.ascx` (学籍页) | Yes | One scraper does both jobs: `GraduationAuditPage` extracts ① the「毕业最低学分」label/value (used by the Profile top stat card) and ② the full `TrainingPlan` (modules / required courses / optional sections / degree-course flags / minimum-credit thresholds), consumed by `TrainingPlanScreen`. Single network call serves both. |
| 师生查询 (PeopleSearch) | `Portal/Search.aspx` style POST → `StudentSearchPage` / `TeacherSearchPage` parse `#_ctl1_dgContent` 表格 | Yes | One screen (`PeopleSearchScreen`) with a top chip switching between 教工 / 学生 mode, backed by `StudentRepository` and `TeacherRepository`. A result row's `userNum` is **base64(教号 \| 学号)** — pass it intact into route args (URL-encode for `+/=`). |
| 学生详情 (StudentDetail) | `Portal/All_StudentInfor.aspx?UserNum=<base64>` | Yes | The detail HTML itself does **not** include 学院/班级, so `Routes.STUDENT_DETAIL` carries an extra `department` segment populated from the search-result row (use `"-"` as a placeholder when missing). |
| 教师详情 (TeacherDetail) | `Portal/All_TeacherInfor.aspx?UserNum=<base64>` | Yes | `TeacherDetailPage` matches spans by **id suffix** (e.g. `lblName`), tolerant of `_ctl1_` vs `_ctl6_` vs `_ctl9_` prefix the server picks at random. |
| 看他人课表 (UserSchedule) | `User/default.aspx?…uctl=MyControl\xfz_kcb.ascx&UserType={Teacher\|Student}&UserNum=<base64>` | Yes | Same parser as the user's own schedule, but the server renders it through `All_Display.aspx`, so `SchedulePage.controlPrefix` will be `_ctl6` (not `_ctl1`). The parser auto-detects via `ddlSterm` name attribute — don't hardcode. |
| 校历 (Calendar) | `Portal/jiaoxuerili.aspx` listing → entries link to **PDFs** hosted on jwc | **No** (public page) | `CalendarPage.parse(html, baseUri)` **must** be passed `JxnuUrls.PAGE_CALENDAR_INDEX` as baseUri so Jsoup's `abs:href` can resolve relative PDF paths (`Jxzl_xxx.pdf` → `https://jwc.jxnu.edu.cn/Jxzl_xxx.pdf`). Opening the PDF uses `Intent.ACTION_VIEW + CATEGORY_BROWSABLE` with `Intent.createChooser` fallback. Requires the `<queries>` manifest block (see URL quirks). |
| 开课查询 (CourseOffering) | `MyControl/Public_Kkap.aspx`（GET 表单页 → POST 查询） | Yes | 按 学期/学院/星期/节次/教室号/课程名/教师 组合检索全校开课。**GET 表单公开，但查询 POST 必须带登录会话**——匿名（或 cookie 不全）POST 恒返 51 字节纯文本「Error:系统错误，请与系统管理员联系！」(HTTP 200、同域、不重定向，`JwcResponseGuard` 拦不到；`CourseOfferingPage.isSystemError` 识别裸 `Error:` 前缀)。`CourseOfferingRepository` 用 donor 模式（同 `StudentRepository`）：`fetchForm` GET 拿三件套+下拉选项，`search` POST 复用/刷新三件套，两步都走 `*Auth`. 结果表 `table#gvContent` 列由服务端决定，`CourseOfferingPage.parseResult` **表头驱动**（第一行 `<th>` 作列名，之后 `<td>` 对齐；无表头/单格提示行/空态都兜底），实测真实 10 列：序号/单位名称/**课程名称标识**/班级名称/任课教师/教室/星期/节次/授课人数/课程讨论区. `ResultCard` 标题用 `TITLE_HINTS` **子串**匹配「课程名称标识」等（非精确相等，因真实列名是「课程名称标识」不是「课程名称」）. 学院代码是 8 位定宽带尾随空格 value（如 `51000   `），`FormOption.value` 原样回传不 trim. 除「我的」入口外，课表课程详情 Sheet 的「教师」「地点」行可点，经 `Routes.courseOffering(teacher/classroom/semesterIsoDate)`（`course_offering/{field}/{value}/{semester}` **path 参数**，勿改回 query 参数——其自动解码行为不可靠）带条件跳入并自动查询（`CourseOfferingScreen` 的 `prefill*` 参数）；多教师取首名（`primaryTeacherName`），学期按**开学日**对齐（`semesterStartDateOf`——两边 value 分隔符不同不能直接比，且本页默认学期是「最新」而非「当前」，学期末会岔开）. |
| 桌面 widget | reads `WidgetSnapshotStore` only (no network) | n/a | `ScheduleViewModel.refreshWidgetSnapshot` writes today's courses after every successful schedule load and calls `TodayScheduleWidget().updateAll(ctx)`. The widget never reaches the network itself. 这份快照还兼作课表页的**离线兜底**：`ScheduleViewModel` 拉取失败时 `fallbackToSnapshotOrError` 读 `WidgetSnapshotStore` → `ScheduleSnapshot.toCourses()` 还原上次本学期课表并置 `isOffline=true`（顶部「无网络」提示、隐藏学期切换，因快照不含学期列表）；下次成功加载复位。 |

**Pure WebView sub-routes (no scraper):**

| Entry | Source | Notes |
|---|---|---|
| 空闲教室 | WebView embedding `https://xmy3.github.io/jxnu-classroom/#/` | Reached via 「我的 → ToolsBlock 卡片 → 空闲教室」 as a sub-route (`Routes.CLASSROOM`). App is just a container; **business changes go to the xmy3/jxnu-classroom repo**, not here. The native classroom stack (model / repo / vm / json client) was removed — only `ClassroomScreen.kt` (the WebView wrapper) remains, with an `onBack` parameter for the back arrow. |

通知详情曾经走 WebView (loadDataWithBaseURL)，现在已替换为原生 Compose 渲染 (`ArticleDetailPage` parser + `AnnouncementDetailScreen` 用 LazyColumn + AnnotatedString + `RemoteJwcImage`)。WebView TLS 兼容那条 memory 仍然适用——任何**未来**需要嵌入 jwc 详情页的场景都要走 `JwcClient.getHtml*` + `loadDataWithBaseURL`，绝不能 `WebView.loadUrl(jwc URL)`。

### Local overrides

- **`CourseOverridesStore`** (SharedPreferences) — the teaching-office grid has no real week-of-semester info, so every `Course` arrives with `weeks = 1..18`. Users can correct this per-course via the "编辑周次" button in the course detail Sheet; the result is persisted as `课程名 → List<Int>` here. `ScheduleRepository` applies the overrides on every emit. Clearing all weeks (0 selected) is *not* "delete this course" — the save button is disabled in that state.
- **`AvatarPrefs.showAvatar`** — defaults to `false`. Until the user toggles "显示学生头像" in Profile, no avatar HTTP is ever issued (privacy/data-saving). When on, `RemoteJwcImage` is what actually loads it (see UI gotchas).
- **`SectionTimetable`** (`data/model/SectionTimetable.kt`) — single source of truth for the 江师大瑶湖 12-section daily schedule (start times + 40 min duration). The Compose schedule grid, the "下一节 / 进行中" highlight, the widget renderer, and `WidgetUpdateScheduler` all consume it. Don't hardcode `"14:00"` etc. anywhere; if the school changes the timetable, this file is the only thing to edit.
- **`AnnouncementUnreadState.hasUnread`** + **`AnnouncementReadAnchor`** — derive the red dot on the bottom-nav "通知" tab. `AnnouncementRepository.latestList` is the first page result (populated by `NvzhuanbanApp` warmup + each user-driven `load`/`refresh`); `anchor` is the `uniqueKey` of the latest item the user actually saw. Unread = top `uniqueKey` ≠ anchor. **Only** `AnnouncementViewModel.load` / `refresh` write the anchor, and **only on the final emit** (含图文) — not on the partial emit (通知+通告 only), otherwise an arriving 图文 with a newer date would silently fail to trigger the red dot. `loadMore` must also never write anchor (older pages would silently retreat it).

### Widget update self-loop

`TodayScheduleWidget.provideGlance` ends each render with `WidgetUpdateScheduler.scheduleNext`, which uses inexact `AlarmManager.set` to schedule one `ACTION_TICK` broadcast at the next significant minute-of-day (a section's start, a section's end, or 00:01 the next day). `TodayScheduleWidgetReceiver` catches the tick and calls `updateAll`, which re-enters `provideGlance`, which schedules the next tick — forming a self-perpetuating loop without needing `SCHEDULE_EXACT_ALARM` permission. The 30-minute `updatePeriodMillis` in `today_schedule_widget_info.xml` + system broadcasts (`ACTION_DATE_CHANGED`, `TIMEZONE_CHANGED`, …) are belt-and-suspenders. Any new widget renderer must keep calling `scheduleNext` — drop it and the alarm chain dies the next time the device reboots or the receiver is replaced.

### Repository conventions

- All repos expose `suspend` functions and an `instance` singleton via `by lazy {}` (these are app-scoped; do not new them up per screen).
- **HTTP entry-point choice**: `JwcClient` exposes plain `getHtml` / `postHtml` / `getBytes` (raw, fire-once) *and* `getHtmlAuth` / `postHtmlAuth` / `getBytesAuth` (wraps the call in `runWithSessionRecovery` — on `JwcError.SessionExpired` it silently re-auths via `SessionRecovery`, replays the request once, and broadcasts `SessionEvents.notifyExpired()` only if reauth also fails so `AppNav` kicks the user to login). **Default to the `*Auth` variants** for any page behind login — every repo behind login does; only the public-page repos (`AnnouncementRepository` for the list, `CalendarRepository` for the 校历 index), `AuthRepository` itself and `UpdateRepository`（走 GitHub API）skip them. When adding a new repo, picking `*Auth` is almost always the safer default.
- In-memory cache is guarded by `kotlinx.coroutines.sync.Mutex` + `@Volatile` fields. The pattern is `mutex.withLock { cached ?: fetchNow().also { cached = it } }`.
- **`clearCache()` 必须无锁、非 suspend**（`@Volatile cached = null`；map 缓存用 `synchronized(cache)` 或 `ConcurrentHashMap`）。**绝不能** `mutex.withLock`：`clearCache` 经 `AuthRepository.clearRepositoryCaches` 在**持有 authMutex 时**调用，而业务 `fetch` 持有 repo mutex 期间会经 `getHtmlAuth → reauth` 去抢 authMutex —— 两者一旦都加锁就是 `repo.mutex ⇄ authMutex` 跨锁死锁（会话失效、多屏刷新、需重登时应用整个卡死）。这是 1.2.0 的原始设计；v1.3.0 的前端审查一度把它们改成 `mutex.withLock` 引入死锁，v1.5.0 已复原。新增 repo 照此办理。
- `refresh()` clears the cache and re-fetches; everything else returns cached data after the first call. `ScheduleRepository` additionally keeps a per-semester map (`cachedBySemester`) so re-selecting a previously-visited semester is instant. `ArticleDetailRepository` uses an LRU (cap 16) instead of a single cache slot because each article is keyed by id.
- Pages whose state needs to survive POSTs (currently just `SchedulePage`) extract the three ASP.NET hidden fields into the parsed model so the repository can use them as the donor for the next POST.
- **`UiStateViewModel<T>`**（`ui/components/UiStateViewModel.kt`）是 load/refresh 型 ViewModel 的基类（9 个 VM 已迁入）：子类只实现 `fetch(refresh)`；`refresh()` **失败静默保留旧数据**、只复位转圈——这是全 app 下拉刷新的统一语义，子类不得绕开；基类不在 init 自动 load，需要即载的子类自己写 `init { load() }`。带路由参数的 VM（Student/TeacherDetail、AnnouncementDetail、UserSchedule）用 companion `viewModelFactory { initializer { ... } }`，不要再写匿名 `ViewModelProvider.Factory`。
- **新增任何用户派生的缓存/prefs 都必须挂进 `AuthRepository.clearAllUserDataOnSignOut`**（现有清单：各 repo 内存缓存、CourseOverrides、通知已读锚点、师生查询浏览历史（PeopleSearchHistoryStore）、widget 快照、`clearDecodedImageCache()`、`JxnuHttpClient.clearHttpCache()`），否则换账号后上一用户数据残留。注意两级语义：`expireSession` 只清 repo 缓存（同账号重登可自愈），登出/换号才全清——新数据要想清楚属于哪一级。

## URL quirks (read before touching network code)

- ASP.NET UserControl paths use a literal **backslash**: `MyControl\xfz_kcb.ascx`. Keep it as `\` (`"…\\xfz_…"` in a Kotlin string) — **do not URL-encode it to `%5C`**, the server refuses the request. Browser-form `action` attributes do contain `%5C`; that's the browser's choice, not a requirement.
- Semester `option value` looks like `2026\3\1 0:00:00` (date uses `\`). `SchedulePage.SemesterOption.startDate` normalises both `\` and `/` before parsing.
- Student / teacher / 他人课表 URLs have the id **base64-encoded** into the `UserNum` query param (e.g. `MjAyNDA1MDAwMQ==`). Pass this through `Uri.encode` when stuffing into nav route args because base64 can contain `+/=`.
- `CalendarPage.parse(html, baseUri)` requires the baseUri argument — Jsoup's `abs:href` returns empty otherwise, and the PDF Intent gets handed a scheme-less path. The repository passes `JxnuUrls.PAGE_CALENDAR_INDEX`.
- **Android 11+ package visibility**: opening external http(s) URLs (校历 PDF, 通知「在浏览器中打开」, 空闲教室外链) requires the `<queries>` block in `AndroidManifest.xml` declaring `ACTION_VIEW + CATEGORY_BROWSABLE` for both http and https schemes. Without `BROWSABLE`, the chooser returns empty even though Chrome is installed — symptom is "No apps can perform this action". Don't drop those entries.

## Domain conventions

- The grades column is officially called **「标准分」** (Z-score-like, can be negative). The `Grade.gpa` field name is historical — never surface "GPA" or "绩点" in UI strings. Semester aggregate is **「加权平均标准分」** (see `strings.xml`'s `grades_gpa_label`). See `memory/project_grades_terminology.md`.
- 校园卡 was removed; do not re-add `cardBalance` to `UserProfile` or similar.
- 课表 semester switching uses real network round-trips per new semester; switching back is cached. `selectedSemesterValue` is only non-null after the first successful load.

## UI / Compose gotchas

- **`StateScaffold`** forwards its `modifier` to *all three* branches (Loading / Error / `Box` wrapping Success), so callers usually don't need to re-apply `Modifier.padding(padding)` inside the Success content — pass it once at the `StateScaffold(modifier = …)` call site. (Earlier versions only forwarded to placeholders; a few legacy screens still double-pad — fine, but don't copy that pattern in new code.)
- AppNav uses an **outer Scaffold** with the bottom navigation bar; each screen has its **own inner Scaffold** with a `TopAppBar`（返回箭头用共享的 `BackNavigationIcon`）. `installSplashScreen()` runs **before** `super.onCreate`, `enableEdgeToEdge()` after it; respect the `innerPadding` from both Scaffolds.
- Bottom-bar visibility is gated by `currentRoute in mainRouteSet` (= the routes in `TABS`). Sub-routes like `Routes.ANNOUNCEMENT_DETAIL` and `Routes.CLASSROOM` are **not** in that set and hence auto-hide the bottom bar; they own a back arrow via their own `onBack` callback. `ClassroomScreen`'s `BackHandler` consumes WebView internal history first and only delegates to the route-level back once exhausted.
- The "今天" / `jumpToToday()` button on the Schedule tab snaps back to the current semester + current week (it crosses-semester if needed). Historical-semester views never highlight a column as "today".「今」列由 `ScheduleScreenState.currentWeek`（VM 在每个 emit 点赋值的可观察字段）+ 跨零点自动刷新的 `today` 状态派生——不要退回读非快照 var 的写法（会跨天定格在昨天）。
- **课表节次高度只能在布局期读取**：捏合缩放逐帧写 `liveDp`，`ScheduleGrid` 通过 `() -> Float` lambda 把高度下发到 `Modifier.layout` / lambda 版 `offset` 里现取（见 `ScheduleGrid.kt` 顶部注释）。新增任何消费节次高度的 UI 必须沿用此模式；在组合期读取会回归「缩放每帧整张网格全量重组」。
- **课表配色方案**（v1.4.0 起多套可选）：枚举与持久化在 `ThemePrefs.SchedulePalette`（外观偏好，登出**不**清），色板与取色逻辑在 `ui/screens/schedule/SchedulePalettes.kt`。所有方案统一 12 色且刻意压深，保证其上白色 9sp 小字对比度 ≥4.5:1——该约束由 JVM 单测 `SchedulePaletteContrastTest` 强制（CI 跑），调整/新增配色必须过它（浅色 300 系 + 白字曾低至 1.7:1）。各方案色板长度必须一致（同测试断言），否则切换方案会打乱同名课程的撞色关系。
- **`RemoteJwcImage`** (in `ui/components/`) is **the** image loader for all jwc-hosted images（通知正文图、图文新闻缩略图、学生头像、师生详情照片、全屏查看器）：走 `JwcClient.getBytesAuth`（自动带 session cookie + 会话失效重登重放），fetch+decode 全在 IO 线程，进程级 **20MB LruCache** 缓存解码位图（磁盘层由 `JxnuHttpClient` 的 OkHttp Cache 兜底），内部是 Loading/Success/Failed 三态——`fallback` 是 loading 槽，`error` 槽默认回退 fallback，`retryKey` 变化可重触发下载，`placeholderModifier` 允许占位与成品用不同布局约束（通知详情的 aspectRatio 预占位靠它）。头像仍由 `AvatarPrefs.showAvatar` 开关（默认 false，隐私考虑），其他用途不受该开关控制。两级缓存都挂在 `clearAllUserDataOnSignOut` 里登出清空。
- **`AndroidViewModel` constructor**: subclasses must take **only** `application: Application` in the primary constructor. Any `Repository` / dep goes in the class body as `private val xxx = XxxRepository.instance`, **never** as an extra ctor param (even with a default value). Kotlin doesn't synthesize a `(Application)` single-param overload for ctors with default args — `SavedStateViewModelFactory` reflects on that exact signature and throws `NoSuchMethodException` → instant crash on `viewModel<XxxViewModel>()`. 现在只剩 `ScheduleViewModel` 和 `ProfileViewModel` 两个 AndroidViewModel（其余已迁 `UiStateViewModel`/plain ViewModel），警示注释在各自主构造器上方（`ScheduleViewModel.kt:53-55`、`ProfileViewModel.kt:53-58`）. Bitten twice (2026-05-25 on ProfileViewModel — the bug looks like "我一点进我的就闪退").
- **Glance `LazyColumn.items(itemId = ...)` reserved range**: Glance reserves `[Long.MIN_VALUE, Long.MIN_VALUE / 2]` for internal use; passing an itemId in that range throws `IllegalArgumentException` and the widget renders "Can't show content" forever. Polynomial hashes routinely produce negative Longs that land there. Always end the hash with `and Long.MAX_VALUE` to clear the sign bit. `snapshotCourseItemId` in `ui/widget/TodayScheduleWidget.kt` is the canonical pattern.
- **Foundation 1.8+ 文本 context menu「弹两次」**：composeBom `2026.05.01`（foundation 1.11）默认 `ComposeFoundationFlags.isNewContextMenuEnabled = true`，会给 `SelectionContainer`（本 app 仅通知详情用到）同时装配「系统平台浮动工具栏 + Compose 自绘 dropdown」两个 provider 并都渲染，长按选中正文时**两个复制菜单一起弹**。`NvzhuanbanApp.onCreate` 在首个 Composition 之前把它设为 `false`，回到旧的单一系统 context menu。该 flag 是 `@ExperimentalFoundationApi` 全局开关，将来某个 BOM 移除它会**编译期报错**（不会静默失效）。
- **预测性返回（Predictive Back）**：targetSdk 36 下系统默认开启「边缘滑动预览上一界面」动画。`AndroidManifest.xml` 的 `<application android:enableOnBackInvokedCallback="false">` 显式 opt-out，回到无预览的即时返回；不影响 Compose `BackHandler` / 导航返回（androidx 的 `OnBackPressedDispatcher` 会回退到传统 `onBackPressed` 分发，`ClassroomScreen` 的 WebView 返回照常）。

## Samples & memory

- **Test fixtures live in `app/src/test/resources/samples/*.html`** and are read via classloader (`sampleHtml("name.html")` from `TestSamples.kt`) so the JVM test task is portable / CI-friendly. The fixtures are **redacted** — real names / 学号 / 教号 / 邮箱 / base64 `UserNum` are replaced with stable fakes (张三/李四/2024050001/020001/...). The top-level `samples/` directory that used to exist for parser-development scratch was removed; if you need a real-page diff, re-scrape locally into a gitignored path and **don't** commit it.
- When adding a new fixture: open the redacted file before saving, search for the original PII keys (姓名 / 学号 / 邮箱 / base64 of those), and replace. The pattern `MjAyNDA1MDAwMQ==` is the canonical fake-student base64; `MDIwMDAx` is the canonical fake-teacher base64.
- `jwc.jxnu.edu.cn.har` was the original HAR capture used to reverse-engineer the CAS flow; **not in the repo** (never committed; gitignored). If you re-capture, scrub real cookies / `__RSA__` payloads before keeping it anywhere shared.
- Long-lived project knowledge (auth flow, data sources, terminology, WebView TLS quirks) lives in the Claude Code per-machine project memory（`~/.claude/projects/<project-dir>/memory/`，路径随开发机不同）— read it before touching anything in `data/network/` or grade/schedule UI strings.
