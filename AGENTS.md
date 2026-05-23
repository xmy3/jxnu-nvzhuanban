# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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

There is no CI configured. JVM unit tests under `app/src/test/kotlin/cn/jxnu/nvzhuanban/data/network/` cover every Page parser (Schedule / Grade / TestGrade / Exam / Announcement / UserDefault) plus `CasLoginClient.parseExecutionFromHtml`. When changing a parser, update or extend the corresponding `*PageTest.kt`; instrumentation / Compose UI tests are still just the default scaffolding.

## Architecture

### Big picture

This is **a thin Compose UI wrapping HTML scrapers of the JXNU academic-affairs ASP.NET WebForms site**. There is no backend of our own; every screen ultimately calls a `Repository` that hits `jwc.jxnu.edu.cn` (or its CAS host `uis.jxnu.edu.cn`), parses the HTML with Jsoup, and returns plain Kotlin data classes.

Layering (`app/src/main/kotlin/cn/jxnu/nvzhuanban/`):

```
data/
  model/         pure data classes (Course, Grade, Exam, Announcement, UserProfile, AuthState)
  network/       OkHttp client + CAS login + cookie/credential persistence
                 (AuthStorage, SecureCredentialStore, PersistentCookieJar all live here, not in storage/)
    pages/       Jsoup-based parsers (SchedulePage, GradePage, TestGradePage, ExamPage, AnnouncementPage, UserDefaultPage)
  repository/    in-memory cache + Mutex + suspend API on top of pages/
  storage/       SharedPreferences wrappers (ThemePrefs, AvatarPrefs, CourseOverridesStore)
  widget/        Snapshot store for the home-screen widget (file-backed JSON)
ui/
  navigation/    AppNav.kt — single NavHost + bottom-bar Scaffold
  screens/<feature>/  one Compose screen + one ViewModel per feature
  components/    shared Loading/Error/Empty/StateScaffold, RefreshIconButton, RemoteJwcImage
  widget/        Glance widget (TodayScheduleWidget) reading the snapshot store
  theme/         Color/Type/Theme + ThemeMode (system/light/dark)
NvzhuanbanApp.kt   Application: inits AuthRepository, kicks off sessionRestore Deferred
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

Bottom bar (5 main tabs) + the home-screen widget. **空闲教室 is no longer a bottom-bar tab** — see secondary entries below.

| Entry | Endpoint / source | Auth needed? | Notes |
|---|---|---|---|
| 课表 (Schedule) | `User/default.aspx?…uctl=MyControl\xfz_kcb.ascx` (GET + POST for semester switch) | Yes | ASP.NET WebForms postback; we round-trip `__VIEWSTATE`/`__VIEWSTATEGENERATOR`/`__EVENTVALIDATION` to switch semester. **The visual grid has no week-of-semester info and no credits** — every Course defaults to `weeks = 1..18` and `credit = 0f`; `ScheduleRepository.enrichWithCredits()` later overlays credits from the grade page, and `CourseOverridesStore` overlays user-edited weeks (see "Local overrides" below). |
| 成绩 (Grades) | `MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx` | Yes | `GradeRepository` caches in memory; shared by `ScheduleRepository.enrichWithCredits` and `ProfileViewModel.enrichFromGrades`. `refresh()` bypasses the cache; plain `fetchAll()` reuses it. A parallel `TestGradeRepository` + `TestGradePage` powers the「考试出分」sub-tab from a separate UserControl. |
| 考试 (Exams) | `User/default.aspx?…uctl=MyControl\xfz_test_schedule.ascx` | Yes | The `HH:mm` in the parsed date is **not reliable** (teaching office doesn't fill real exam times); `Exam.statusAt` and the UI deliberately ignore time-of-day and reason only about the date. |
| 通知 (Announcements) | `Portal/ArticlesList.aspx?type={Jwtz\|Jwgg}` | **No** (public page) | Two types fetched concurrently per page and merged by date. `AnnouncementRepository.fetchAll(page)` supports paging; `AnnouncementViewModel.loadMore` triggers near-bottom. Detail view is a WebView at `Portal/ArticlesView.aspx?id=…`. |
| 我的 (Profile) | `User/Default.aspx` for name/student-id only; **学院/专业/班级 come from the grades page** (`#_ctl6_lblMsg`) | Yes | `UserDefaultPage` parses `<span id="lblUserInfor">欢迎您，(学号,Student) 姓名</span>` from the home page (this is the **only** info that page exposes). `ProfileViewModel.enrichFromGrades()` then fills college/major/className/cumulativeCredits in the background. |
| 桌面 widget | reads `WidgetSnapshotStore` only (no network) | n/a | `ScheduleViewModel.refreshWidgetSnapshot` writes today's courses after every successful schedule load and calls `TodayScheduleWidget().updateAll(ctx)`. The widget never reaches the network itself. |

**Secondary entries (not in bottom bar):**

| Entry | Source | Notes |
|---|---|---|
| 空闲教室 | WebView embedding `https://xmy3.github.io/jxnu-classroom/#/` | Reached via 「我的 → ToolsBlock 卡片 → 空闲教室」 as a sub-route (`Routes.CLASSROOM`). App is just a container; **business changes go to the xmy3/jxnu-classroom repo**, not here. The native classroom stack (model / repo / vm / json client) was removed — only `ClassroomScreen.kt` (the WebView wrapper) remains, with an `onBack` parameter for the back arrow. |

### Local overrides

- **`CourseOverridesStore`** (SharedPreferences) — the teaching-office grid has no real week-of-semester info, so every `Course` arrives with `weeks = 1..18`. Users can correct this per-course via the "编辑周次" button in the course detail Sheet; the result is persisted as `课程名 → List<Int>` here. `ScheduleRepository` applies the overrides on every emit. Clearing all weeks (0 selected) is *not* "delete this course" — the save button is disabled in that state.
- **`AvatarPrefs.showAvatar`** — defaults to `false`. Until the user toggles "显示学生头像" in Profile, no avatar HTTP is ever issued (privacy/data-saving). When on, `RemoteJwcImage` is what actually loads it (see UI gotchas).
- **`SectionTimetable`** (`data/model/SectionTimetable.kt`) — single source of truth for the 江师大瑶湖 12-section daily schedule (start times + 40 min duration). The Compose schedule grid, the "下一节 / 进行中" highlight, the widget renderer, and `WidgetUpdateScheduler` all consume it. Don't hardcode `"14:00"` etc. anywhere; if the school changes the timetable, this file is the only thing to edit.
- **`AnnouncementUnreadState.hasUnread`** + **`AnnouncementReadAnchor`** — derive the red dot on the bottom-nav "通知" tab. `AnnouncementRepository.latestList` is the first page result (populated by `NvzhuanbanApp` warmup + each user-driven `load`/`refresh`); `anchor` is the `uniqueKey` of the latest item the user actually saw. Unread = top `uniqueKey` ≠ anchor. **Only** `AnnouncementViewModel.load` / `refresh` write the anchor — `loadMore` must not (it returns older pages and would silently retreat the anchor).

### Widget update self-loop

`TodayScheduleWidget.provideGlance` ends each render with `WidgetUpdateScheduler.scheduleNext`, which uses inexact `AlarmManager.set` to schedule one `ACTION_TICK` broadcast at the next significant minute-of-day (a section's start, a section's end, or 00:01 the next day). `TodayScheduleWidgetReceiver` catches the tick and calls `updateAll`, which re-enters `provideGlance`, which schedules the next tick — forming a self-perpetuating loop without needing `SCHEDULE_EXACT_ALARM` permission. The 30-minute `updatePeriodMillis` in `today_schedule_widget_info.xml` + system broadcasts (`ACTION_DATE_CHANGED`, `TIMEZONE_CHANGED`, …) are belt-and-suspenders. Any new widget renderer must keep calling `scheduleNext` — drop it and the alarm chain dies the next time the device reboots or the receiver is replaced.

### Repository conventions

- All repos expose `suspend` functions and an `instance` singleton via `by lazy {}` (these are app-scoped; do not new them up per screen).
- In-memory cache is guarded by `kotlinx.coroutines.sync.Mutex` + `@Volatile` fields. The pattern is `mutex.withLock { cached ?: fetchNow().also { cached = it } }`.
- `refresh()` clears the cache and re-fetches; everything else returns cached data after the first call. `ScheduleRepository` additionally keeps a per-semester map (`cachedBySemester`) so re-selecting a previously-visited semester is instant.
- Pages whose state needs to survive POSTs (currently just `SchedulePage`) extract the three ASP.NET hidden fields into the parsed model so the repository can use them as the donor for the next POST.

## URL quirks (read before touching network code)

- ASP.NET UserControl paths use a literal **backslash**: `MyControl\xfz_kcb.ascx`. Keep it as `\` (`"…\\xfz_…"` in a Kotlin string) — **do not URL-encode it to `%5C`**, the server refuses the request. Browser-form `action` attributes do contain `%5C`; that's the browser's choice, not a requirement.
- Semester `option value` looks like `2026\3\1 0:00:00` (date uses `\`). `SchedulePage.SemesterOption.startDate` normalises both `\` and `/` before parsing.
- Student photo URL has the student id **base64-encoded** into the `UserNum` query param.

## Domain conventions

- The grades column is officially called **「标准分」** (Z-score-like, can be negative). The `Grade.gpa` field name is historical — never surface "GPA" or "绩点" in UI strings. Semester aggregate is **「加权平均标准分」** (see `strings.xml`'s `grades_gpa_label`). See `memory/project_grades_terminology.md`.
- 校园卡 was removed; do not re-add `cardBalance` to `UserProfile` or similar.
- 课表 semester switching uses real network round-trips per new semester; switching back is cached. `selectedSemesterValue` is only non-null after the first successful load.

## UI / Compose gotchas

- **`StateScaffold` does not forward its `modifier` to the `Success` branch's content** — it only applies it to the Loading/Error placeholders. Callers must wrap their content (e.g. `LazyColumn`/`PullToRefreshBox`) with `Modifier.padding(padding)` themselves. Profile had this bug once: don't repeat it.
- AppNav uses an **outer Scaffold** with the bottom navigation bar; each screen has its **own inner Scaffold** with a `TopAppBar`. Edge-to-edge is enabled in `MainActivity.onCreate` before `super.onCreate`; respect the `innerPadding` from both Scaffolds.
- Bottom-bar visibility is gated by `currentRoute in mainRouteSet` (= the routes in `TABS`). Sub-routes like `Routes.ANNOUNCEMENT_DETAIL` and `Routes.CLASSROOM` are **not** in that set and hence auto-hide the bottom bar; they own a back arrow via their own `onBack` callback. `ClassroomScreen`'s `BackHandler` consumes WebView internal history first and only delegates to the route-level back once exhausted.
- The "今天" / `jumpToToday()` button on the Schedule tab snaps back to the current semester + current week (it crosses-semester if needed). Historical-semester views never highlight a column as "today".
- **`RemoteJwcImage`** (in `ui/components/`) is a session-cookie-aware image loader for jwc-hosted images (student avatar etc.) — it goes through `JxnuHttpClient` so the auth cookie attaches, and falls back to a provided composable on failure. Opt-in via `AvatarPrefs.showAvatar`.

## Samples & memory

- `samples/` holds saved HTML for the four scraped pages (`schedule.html`, `grades.html`, `exams.html`, `page_user_default.html`). When changing a parser, diff against these first instead of re-scraping. Note that `page_user_default.html` was saved via the browser's "view source" feature, so all HTML is escaped inside `<span class="html-tag">`; use it for **content** reference (the `lblUserInfor` text, etc.), not as direct Jsoup input.
- `jwc.jxnu.edu.cn.har` at the repo root is the original HAR capture used to reverse-engineer the CAS flow.
- Long-lived project knowledge (auth flow, data sources, terminology) lives under `C:\Users\XMY\.Codex\projects\C--Users-XMY-Desktop-proj\memory\` — read it before touching anything in `data/network/` or grade/schedule UI strings.
