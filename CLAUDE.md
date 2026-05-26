# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project at a glance

**女专办 (Nvzhuanban)** — unofficial Android client for the JXNU (江西师范大学) academic affairs system, modeled after the "酱紫办" app. Single Gradle module (`:app`), Kotlin + Jetpack Compose + Material 3, namespace `cn.jxnu.nvzhuanban`. min SDK 26, target/compile SDK 36, Java 11, Compose BOM `2024.12.01`, Kotlin `2.0.21`, AGP `8.7.2`.

## Build & run

Gradle wrapper is pinned to 8.9 (`gradle/wrapper/gradle-wrapper.properties`); use the bundled `./gradlew` (Unix) or `gradlew.bat` (Windows). Useful tasks:
- `./gradlew :app:assembleDebug` — debug APK
- `./gradlew :app:compileDebugKotlin` — fastest compile-only smoke test
- `./gradlew :app:test` — JVM unit tests (parser regression suite, runs in seconds)
- `./gradlew :app:connectedAndroidTest` — instrumentation tests (needs a connected device/emulator)

Run a single test class: `./gradlew :app:testDebugUnitTest --tests "cn.jxnu.nvzhuanban.data.network.pages.SchedulePageTest"`.

Release signing reads `keystore.properties` at the repo root (see `keystore.properties.example` for expected keys); the actual keystore is `release.jks` at the same level.

There is no CI configured. JVM unit tests under `app/src/test/kotlin/cn/jxnu/nvzhuanban/data/network/` cover every Page parser (Schedule / Grade / TestGrade / Exam / MakeupExam / Announcement / PictureNews / UserDefault / Calendar / GraduationAudit / StudentSearch / TeacherSearch / StudentDetail / TeacherDetail) plus `CasLoginClient.parseExecutionFromHtml`, `JwcResponseGuard`, `JwcError`, widget snapshot/scheduler. When changing a parser, update or extend the corresponding `*PageTest.kt`; instrumentation / Compose UI tests are still just the default scaffolding.

Test HTML fixtures live in **`app/src/test/resources/samples/*.html`** and are loaded via classloader, not the filesystem — use the shared helper `sampleHtml("name.html")` from `TestSamples.kt` rather than `File("samples/...")`. The fixtures are **PII-redacted** (假学号 `2024050001` / 假教号 `020001` / 假姓名 张三/李四 等) so they can sit in the repo and run in any CI. If you add a new fixture by saving real HTML, scrub 姓名 / 学号 / 教号 / 邮箱 / base64 `UserNum` before committing — search for the original PII to confirm.

## Architecture

### Big picture

This is **a thin Compose UI wrapping HTML scrapers of the JXNU academic-affairs ASP.NET WebForms site**. There is no backend of our own; every screen ultimately calls a `Repository` that hits `jwc.jxnu.edu.cn` (or its CAS host `uis.jxnu.edu.cn`), parses the HTML with Jsoup, and returns plain Kotlin data classes.

Layering (`app/src/main/kotlin/cn/jxnu/nvzhuanban/`):

```
data/
  model/         pure data classes (Course, Grade, TestGrade, Exam, MakeupExam, Announcement,
                 ArticleDetail, UserProfile, AuthState, TrainingPlan, Student, Teacher,
                 CalendarEntry, SectionTimetable)
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
                 ScheduleHeightPrefs, AnnouncementReadAnchor, AnnouncementUnreadState)
  widget/        Snapshot store for the home-screen widget (file-backed JSON)
ui/
  navigation/    AppNav.kt — single NavHost + bottom-bar Scaffold + Routes object
  screens/<feature>/  one Compose screen + one ViewModel per feature; features include
                 schedule, announcement, profile, grades, exams, login, trainingplan,
                 calendar, peoplesearch, students, teachers, userschedule, classroom
  components/    shared Loading/Error/Empty/StateScaffold, RefreshIconButton, RemoteJwcImage
  widget/        Glance widget (TodayScheduleWidget) reading the snapshot store
  theme/         Color/Type/Theme + ThemeMode (system/light/dark)
NvzhuanbanApp.kt   Application: inits AuthRepository, kicks off sessionRestore Deferred,
                   warms up announcement first-page for the bottom-nav red dot
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

`CasLoginClient.Result.Failure` carries an `isAuth: Boolean` flag. **Only** failures with `isAuth=true` (CAS explicitly rejected credentials — wrong password, account locked, MFA required) cause `AuthRepository.tryRestoreSession` to clear the saved password. Network errors, TLS failures, jwc 5xx, "登录页结构异常" etc. leave `isAuth=false` so credentials survive a flaky-network startup. When you add a new `Result.Failure(...)` path, decide which bucket it belongs to.

`UserDefaultPage.parse(studentId, html)` will self-extract the student id from `<span id="lblUserInfor">欢迎您，(学号,Student) 姓名</span>` when the `studentId` argument is blank; this is how `tryRestoreSession` works when cookies are valid but the user had unchecked "remember me". Do **not** pass a non-empty-but-wrong id — that takes precedence over the HTML.

### Cookie persistence

`PersistentCookieJar` keeps everything in memory (`ConcurrentHashMap<domain, MutableList<Cookie>>`) and writes a TSV to `filesDir/jxnu_cookies.tsv` via a dedicated daemon thread coordinated by two booleans (`dirty`, `writing`). The protocol matters: any cookie mutation **must** go through `saveFromResponse` / `clearForHost` / `clearAll`, which call `persistAsync()` — bypassing these and mutating `byDomain` directly will drift memory away from disk. The two-flag design exists specifically to avoid losing writes that arrive while a flush is in flight (rapid cookie deltas during the CAS redirect chain).

### Data source map

Bottom bar has **3 main tabs**: 课表 / 通知 / 我的 (see `AppNav.kt`'s `TABS`). Every other screen — 成绩 / 考试 / 培养方案 / 师生查询 / 校历 / 空闲教室 / 学生详情 / 教师详情 / 看他人课表 — is reached from「我的」(ToolsBlock cards) or from a search result, and is **not** in `mainRouteSet` (so the bottom bar auto-hides and the screen owns its own back arrow). All these features still have independent ViewModel / Repository / parser layers.

| Entry | Endpoint / source | Auth needed? | Notes |
|---|---|---|---|
| 课表 (Schedule) | `User/default.aspx?…uctl=MyControl\xfz_kcb.ascx` (GET + POST for semester switch) | Yes | ASP.NET WebForms postback; we round-trip `__VIEWSTATE`/`__VIEWSTATEGENERATOR`/`__EVENTVALIDATION` to switch semester. **The visual grid has no week-of-semester info and no credits** — every Course defaults to `weeks = 1..18` and `credit = 0f`; `ScheduleRepository.enrichWithCredits()` later overlays credits from the grade page, and `CourseOverridesStore` overlays user-edited weeks (see "Local overrides" below). |
| 成绩 (Grades) | `MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx` | Yes | `GradeRepository` caches in memory; shared by `ScheduleRepository.enrichWithCredits` and `ProfileViewModel.enrichFromGrades`. `refresh()` bypasses the cache; plain `fetchAll()` reuses it. A parallel `TestGradeRepository` + `TestGradePage` powers the「考试出分」sub-tab from a separate UserControl. |
| 考试 (Exams) | `User/default.aspx?…uctl=MyControl\xfz_test_schedule.ascx` + 补缓考 from `MyControl\xfz_bukao_xx.ascx` | Yes | The `HH:mm` in the parsed date is **not reliable** (teaching office doesn't fill real exam times); `Exam.statusAt` and the UI deliberately ignore time-of-day and reason only about the date. 学期考试 + 补缓考 are merged in `ExamsViewModel` and shown as one list. |
| 通知 (Announcements) | 列表 `Portal/ArticlesList.aspx?type={Jwtz\|Jwgg}` + `Portal/ArticlesPictureNews.aspx?page=N`；详情 `Portal/ArticlesView.aspx?id=…` | 列表 **No** (public page)；详情通常需登录态 | **Three** sources merged by date: 通知 / 通告 / 图文新闻. **Page 1 is two-staged** via `AnnouncementRepository.firstPageFlow()` — partial emit = 通知 + 通告 合并 (fast), final emit = + 图文新闻 (slower接口 + 9 张缩略图). `_latestList` is written at partial *and* final (partial provides a degraded-but-usable feed for the red-dot subscriber if 图文 fails). `fetchAll(1)` waits for the final value; `fetchAll(page>=2)` is single-shot 3-way concurrent. `AnnouncementViewModel.load/refresh` collect the flow so partial 列表 renders 1~2s sooner; **anchor 只在终值时写** (don't migrate that to partial). 详情页改为**原生 Compose** 渲染：`ArticleDetailPage` 抽内层 `#main-content` 的 标题/时间/段落/表格/图片/附件，`ArticleDetailRepository` 走 `getHtmlAuth`（会话失效自动 reauth）+ 16 篇 LRU 缓存，`AnnouncementDetailScreen` 用 LazyColumn + AnnotatedString (`LinkAnnotation.Url` 受控走 `openExternalHttpUrl`) 渲染。图片走 `RemoteJwcImage` 复用 session cookie。 |
| 我的 (Profile) | `User/Default.aspx` for name/student-id only; **学院/专业/班级 come from the grades page** (`#_ctl6_lblMsg`) | Yes | `UserDefaultPage` parses `<span id="lblUserInfor">欢迎您，(学号,Student) 姓名</span>` from the home page (this is the **only** info that page exposes). `ProfileViewModel.enrichFromGrades()` then fills college/major/className/cumulativeCredits in the background. |
| 培养方案 (TrainingPlan) | `MyControl\Xfz_Xueji.ascx` (学籍页) | Yes | One scraper does both jobs: `GraduationAuditPage` extracts ① the「毕业最低学分」label/value (used by the Profile top stat card) and ② the full `TrainingPlan` (modules / required courses / optional sections / degree-course flags / minimum-credit thresholds), consumed by `TrainingPlanScreen`. Single network call serves both. |
| 师生查询 (PeopleSearch) | `Portal/Search.aspx` style POST → `StudentSearchPage` / `TeacherSearchPage` parse `#_ctl1_dgContent` 表格 | Yes | One screen (`PeopleSearchScreen`) with a top chip switching between 教工 / 学生 mode, backed by `StudentRepository` and `TeacherRepository`. A result row's `userNum` is **base64(教号 \| 学号)** — pass it intact into route args (URL-encode for `+/=`). |
| 学生详情 (StudentDetail) | `Portal/All_StudentInfor.aspx?UserNum=<base64>` | Yes | The detail HTML itself does **not** include 学院/班级, so `Routes.STUDENT_DETAIL` carries an extra `department` segment populated from the search-result row (use `"-"` as a placeholder when missing). |
| 教师详情 (TeacherDetail) | `Portal/All_TeacherInfor.aspx?UserNum=<base64>` | Yes | `TeacherDetailPage` matches spans by **id suffix** (e.g. `lblName`), tolerant of `_ctl1_` vs `_ctl6_` vs `_ctl9_` prefix the server picks at random. |
| 看他人课表 (UserSchedule) | `User/default.aspx?…uctl=MyControl\xfz_kcb.ascx&UserType={Teacher\|Student}&UserNum=<base64>` | Yes | Same parser as the user's own schedule, but the server renders it through `All_Display.aspx`, so `SchedulePage.controlPrefix` will be `_ctl6` (not `_ctl1`). The parser auto-detects via `ddlSterm` name attribute — don't hardcode. |
| 校历 (Calendar) | `Portal/jiaoxuerili.aspx` listing → entries link to **PDFs** hosted on jwc | **No** (public page) | `CalendarPage.parse(html, baseUri)` **must** be passed `JxnuUrls.PAGE_CALENDAR_INDEX` as baseUri so Jsoup's `abs:href` can resolve relative PDF paths (`Jxzl_xxx.pdf` → `https://jwc.jxnu.edu.cn/Jxzl_xxx.pdf`). Opening the PDF uses `Intent.ACTION_VIEW + CATEGORY_BROWSABLE` with `Intent.createChooser` fallback. Requires the `<queries>` manifest block (see URL quirks). |
| 桌面 widget | reads `WidgetSnapshotStore` only (no network) | n/a | `ScheduleViewModel.refreshWidgetSnapshot` writes today's courses after every successful schedule load and calls `TodayScheduleWidget().updateAll(ctx)`. The widget never reaches the network itself. |

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
- **HTTP entry-point choice**: `JwcClient` exposes plain `getHtml` / `postHtml` / `getBytes` (raw, fire-once) *and* `getHtmlAuth` / `postHtmlAuth` / `getBytesAuth` (wraps the call in `runWithSessionRecovery` — on `JwcError.SessionExpired` it silently re-auths via `SessionRecovery`, replays the request once, and broadcasts `SessionEvents.notifyExpired()` only if reauth also fails so `AppNav` kicks the user to login). **Default to the `*Auth` variants** for any page behind login — that's what 13/15 current repos do. Only the public-page repos (`AnnouncementRepository` for the list, `CalendarRepository` for the 校历 index) and `AuthRepository` itself skip them. When adding a new repo, picking `*Auth` is almost always the safer default.
- In-memory cache is guarded by `kotlinx.coroutines.sync.Mutex` + `@Volatile` fields. The pattern is `mutex.withLock { cached ?: fetchNow().also { cached = it } }`.
- `refresh()` clears the cache and re-fetches; everything else returns cached data after the first call. `ScheduleRepository` additionally keeps a per-semester map (`cachedBySemester`) so re-selecting a previously-visited semester is instant. `ArticleDetailRepository` uses an LRU (cap 16) instead of a single cache slot because each article is keyed by id.
- Pages whose state needs to survive POSTs (currently just `SchedulePage`) extract the three ASP.NET hidden fields into the parsed model so the repository can use them as the donor for the next POST.

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
- AppNav uses an **outer Scaffold** with the bottom navigation bar; each screen has its **own inner Scaffold** with a `TopAppBar`. Edge-to-edge is enabled in `MainActivity.onCreate` before `super.onCreate`; respect the `innerPadding` from both Scaffolds.
- Bottom-bar visibility is gated by `currentRoute in mainRouteSet` (= the routes in `TABS`). Sub-routes like `Routes.ANNOUNCEMENT_DETAIL` and `Routes.CLASSROOM` are **not** in that set and hence auto-hide the bottom bar; they own a back arrow via their own `onBack` callback. `ClassroomScreen`'s `BackHandler` consumes WebView internal history first and only delegates to the route-level back once exhausted.
- The "今天" / `jumpToToday()` button on the Schedule tab snaps back to the current semester + current week (it crosses-semester if needed). Historical-semester views never highlight a column as "today".
- **`RemoteJwcImage`** (in `ui/components/`) is a session-cookie-aware image loader for jwc-hosted images (student avatar etc.) — it goes through `JxnuHttpClient` so the auth cookie attaches, and falls back to a provided composable on failure. Opt-in via `AvatarPrefs.showAvatar`.
- **`AndroidViewModel` constructor**: subclasses must take **only** `application: Application` in the primary constructor. Any `Repository` / dep goes in the class body as `private val xxx = XxxRepository.instance`, **never** as an extra ctor param (even with a default value). Kotlin doesn't synthesize a `(Application)` single-param overload for ctors with default args — `SavedStateViewModelFactory` reflects on that exact signature and throws `NoSuchMethodException` → instant crash on `viewModel<XxxViewModel>()`. `ScheduleViewModel.kt:41-43` is the canonical template; `ProfileViewModel.kt:42-47` carries the same warning comment. Bitten twice (2026-05-25 on ProfileViewModel — the bug looks like "我一点进我的就闪退").
- **Glance `LazyColumn.items(itemId = ...)` reserved range**: Glance reserves `[Long.MIN_VALUE, Long.MIN_VALUE / 2]` for internal use; passing an itemId in that range throws `IllegalArgumentException` and the widget renders "Can't show content" forever. Polynomial hashes routinely produce negative Longs that land there. Always end the hash with `and Long.MAX_VALUE` to clear the sign bit. `snapshotCourseItemId` in `ui/widget/TodayScheduleWidget.kt` is the canonical pattern.

## Samples & memory

- **Test fixtures live in `app/src/test/resources/samples/*.html`** and are read via classloader (`sampleHtml("name.html")` from `TestSamples.kt`) so the JVM test task is portable / CI-friendly. The fixtures are **redacted** — real names / 学号 / 教号 / 邮箱 / base64 `UserNum` are replaced with stable fakes (张三/李四/2024050001/020001/...). The top-level `samples/` directory that used to exist for parser-development scratch was removed; if you need a real-page diff, re-scrape locally into a gitignored path and **don't** commit it.
- When adding a new fixture: open the redacted file before saving, search for the original PII keys (姓名 / 学号 / 邮箱 / base64 of those), and replace. The pattern `MjAyNDA1MDAwMQ==` is the canonical fake-student base64; `MDIwMDAx` is the canonical fake-teacher base64.
- `jwc.jxnu.edu.cn.har` was the original HAR capture used to reverse-engineer the CAS flow; **not in the repo** (never committed; gitignored). If you re-capture, scrub real cookies / `__RSA__` payloads before keeping it anywhere shared.
- Long-lived project knowledge (auth flow, data sources, terminology, WebView TLS quirks) lives under `C:\Users\XMY\.claude\projects\C--Users-XMY-Desktop-proj\memory\` — read it before touching anything in `data/network/` or grade/schedule UI strings.
