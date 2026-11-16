# Drone Telemetry POC — Architecture

Android POC that mirrors Mithril’s incident-response flow: invite/OAuth login, ongoing incidents list, then a live map session with mosaic floor plans, drone telemetry, threats, and color pathways over WebSocket.

**UI architecture:** **MVVM** (Model–View–ViewModel)  
**Layering:** lightweight clean architecture (presentation → domain → data)

**Package:** `com.guardianangel.dronetelementry`  
**DI:** Koin · **HTTP:** Retrofit + OkHttp · **Realtime:** OkHttp WebSocket · **UI:** ViewBinding + Navigation Component · **Async:** Kotlin Coroutines / Flow

---

## Table of contents

1. [Architecture pattern (MVVM)](#1-architecture-pattern-mvvm)
2. [High-level layer diagram](#2-high-level-layer-diagram)
3. [Package layout](#3-package-layout)
4. [User journey & navigation](#4-user-journey--navigation)
5. [Layer responsibilities & file usage](#5-layer-responsibilities--file-usage)
6. [Auth & session](#6-auth--session)
7. [Home / incidents](#7-home--incidents)
8. [Map session & live telemetry](#8-map-session--live-telemetry)
9. [Threading model](#9-threading-model)
10. [Dependency injection](#10-dependency-injection)
11. [Key API & socket channels](#11-key-api--socket-channels)

---

## 1. Architecture pattern (MVVM)

This app uses **MVVM**, not MVI or MVC.

```text
┌──────────────────────┐  user actions / collect flows  ┌──────────────────────┐
│        VIEW          │ ◄─────────────────────────────► │     VIEWMODEL         │
│  Activity / Fragment │   StateFlow / SharedFlow        │  Splash / Onboarding  │
│  Adapters / custom   │   method calls (join, toggle…)  │  Home / MapSession    │
│  MosaicMapView       │                                 │                       │
└──────────────────────┘                                 └──────────┬───────────┘
                                                                    │ calls
                                                                    ▼
                                                         ┌──────────────────────┐
                                                         │       MODEL          │
                                                         │  *Interact (use      │
                                                         │   cases)             │
                                                         │  ApiService /        │
                                                         │  SocketHelper /      │
                                                         │  SessionStore / DTOs │
                                                         └──────────────────────┘
```

| MVVM piece | In this project |
|------------|-----------------|
| **View** | `*Fragment`, `MainActivity`, `MapSessionActivity`, adapters, `MosaicMapView` — render UI, forward clicks, collect flows |
| **ViewModel** | `SplashViewModel`, `OnboardingViewModel`, `HomeViewModel`, `MapSessionViewModel` — hold UI state, no Android View references |
| **Model** | `AuthInteract` / `HomeInteract` / `MapInteract`, `ApiService`, `SocketHelper`, `SessionStore`, DTOs / domain models |

**How data flows (typical screen):**

1. View calls a ViewModel function (e.g. `homeViewModel.join(incident)`).
2. ViewModel calls domain (`HomeInteract`) / data (API or socket).
3. ViewModel updates `StateFlow` / emits on `SharedFlow`.
4. View collects those flows and updates the UI.

**Why not MVI?** There is no Intent → Reducer → single immutable store loop. Screens use multiple flows and direct ViewModel methods. One-shot nav events (e.g. `HomeNav`) are still MVVM-style events, not full MVI.

**Why not MVC?** There is no Controller layer; UI logic lives in ViewModels, not Activities/Fragments as controllers.

**Clean architecture (on top of MVVM):** packages are split presentation / domain (`interact`) / data so networking and persistence stay out of the View.

---

## 2. High-level layer diagram

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                         PRESENTATION  (View + ViewModel)                │
│  Splash / Login / Home (Nav Host in MainActivity)                       │
│  MapSessionActivity  →  MosaicMapView + drone list + threat banner      │
│  ViewModels expose StateFlow / SharedFlow                               │
└───────────────┬─────────────────────────────┬───────────────────────────┘
                │                             │
                ▼                             ▼
┌───────────────────────────┐   ┌─────────────────────────────────────────┐
│     DOMAIN (interact)     │   │              MAP ENGINE                 │
│  AuthInteract             │   │  MosaicMapView, GeoProjector,           │
│  HomeInteract             │   │  IncidentOverlays, MapLayerHelpers,     │
│  MapInteract              │   │  DroneMapVisibility                     │
└───────────┬───────────────┘   └─────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         DATA  (Model)                                   │
│  ApiService (REST)  ·  SocketHelper (WS)  ·  SessionStore (DataStore)   │
│  DTOs  ·  AuthInterceptor / UnauthorizedInterceptor / OAuthHelper       │
└───────────┬─────────────────────────────┬───────────────────────────────┘
            │                             │
            ▼                             ▼
     Backend REST API              Incident WebSocket
     (+ S3 mosaic tiles)           (telemetry / threats / pathways)
```

| Layer | Role |
|--------|------|
| **Presentation** | Views + ViewModels — UI state only (MVVM) |
| **Domain (`interact`)** | Use-case style API orchestration + session side effects |
| **Data** | Network, socket, persistence, DTOs |
| **Map** | Floor-plan rendering & Unreal→pixel math (shared by ViewModel + View) |

Config lives in `config/AppConfig.kt` (base URL, OAuth, poll / sample intervals).

---

## 3. Package layout

```text
com.guardianangel.dronetelementry/
├── DroneTelemetryApp.kt          # Application + startKoin
├── MainActivity.kt               # Nav host + global 401 → login
├── config/
│   └── AppConfig.kt
├── di/
│   └── AppModule.kt              # All Koin bindings
├── data/
│   ├── local/SessionStore.kt
│   ├── model/                    # kotlinx.serialization DTOs
│   ├── remote/                   # ApiService, interceptors, OAuth, SessionEvents
│   └── socket/SocketHelper.kt
├── domain/
│   ├── interact/                 # AuthInteract, HomeInteract, MapInteract
│   └── model/DroneTelemetry.kt   # App-facing telemetry / map pose models
├── map/                          # Mosaic view + geo / overlay helpers
├── presentation/
│   ├── splash/
│   ├── onboarding/               # Login + OAuth WebView
│   ├── home/
│   └── map/                      # MapSessionActivity / ViewModel / adapters
├── ui/MithrilBackground.kt
└── utils/                        # AppLog, TimeFormat
```

---

## 4. User journey & navigation

```text
┌────────────┐   valid session    ┌────────────┐   join incident   ┌──────────────────┐
│  Splash    │ ─────────────────► │   Home     │ ────────────────► │ MapSessionActivity│
└─────┬──────┘                    └─────┬──────┘                   └────────┬─────────┘
      │ no token / 401                  │ logout / 401                      │ leave / 401
      ▼                                 ▼                                   ▼
┌────────────┐                    ┌────────────┐                      finish()
│   Login    │ ── OAuth OK ─────► │   Home     │
└────────────┘                    └────────────┘
```

| Screen | Entry | Files |
|--------|--------|--------|
| Splash | App launch (`nav_graph` start) | `SplashFragment`, `SplashViewModel` |
| Login | Splash / 401 / logout | `LoginFragment`, `OnboardingViewModel`, `OAuthWebViewActivity` |
| Home | After auth | `HomeFragment`, `HomeViewModel`, `IncidentAdapter` |
| Map session | Home join → separate Activity | `MapSessionActivity`, `MapSessionViewModel`, `MosaicMapView` |

Navigation graph: `app/src/main/res/navigation/nav_graph.xml`  
Map is **not** in the nav graph; `HomeFragment` starts `MapSessionActivity` with extras (`incidentId`, `campusId`, `wsTicket`).

Global 401: `UnauthorizedInterceptor` → `SessionEvents.unauthorized` → `MainActivity` / `MapSessionActivity` navigate away or finish.

---

## 5. Layer responsibilities & file usage

### Application & shell

| File | Used by | Purpose |
|------|---------|---------|
| `DroneTelemetryApp.kt` | Manifest `android:name` | Starts Koin with `appModule` |
| `MainActivity.kt` | Launcher | Hosts `NavHostFragment`; collects unauthorized events |
| `di/AppModule.kt` | Koin | Wires Json, OkHttp (API / tiles / WS), Retrofit, interacts, ViewModels |
| `config/AppConfig.kt` | Everywhere needing env | API base URL, OAuth URL/redirect, `TELEMETRY_UI_SAMPLE_MS`, poll interval |

### Data — remote

| File | Used by | Purpose |
|------|---------|---------|
| `ApiService.kt` | All `*Interact` | Retrofit endpoints (auth, campuses, incidents, maps, annotations, ws-ticket) |
| `AuthInterceptor.kt` | API OkHttp client | Attaches JWT only for API host (never S3 tiles) |
| `UnauthorizedInterceptor.kt` | API OkHttp client | On HTTP 401: clear session + emit `SessionEvents` |
| `SessionEvents.kt` | Interceptor, Activities | SharedFlow of unauthorized events |
| `OAuthHelper.kt` | `OnboardingViewModel` | Builds authorize URL; parses redirect `code` |

### Data — local & socket

| File | Used by | Purpose |
|------|---------|---------|
| `SessionStore.kt` | Auth / Home / interceptors | DataStore: token blob, district/campus, invite code |
| `SocketHelper.kt` | `MapSessionViewModel` | WebSocket connect/reconnect, JSON parse on IO, StateFlows for drones/threats/pathways |
| `data/model/*Dto.kt` | API + socket | Serialized request/response and WS payloads |

### Domain

| File | Used by | Purpose |
|------|---------|---------|
| `AuthInteract.kt` | Splash, Onboarding, Home VMs | Invite → district, token exchange, restore session, campuses, logout |
| `HomeInteract.kt` | `HomeViewModel` | Ongoing incidents + join (returns `ws_ticket`) |
| `MapInteract.kt` | `MapSessionViewModel` | Map config, paginated annotations, refresh WS ticket |
| `domain/model/DroneTelemetry.kt` | Socket → map UI | `DroneTelemetry` (raw) and `DroneMapPose` (pixel + UI fields) |

### Map engine

| File | Used by | Purpose |
|------|---------|---------|
| `MosaicMapView.kt` | `MapSessionActivity` | Custom View: overview + hi-res tiles, drones, threats, pathways, pan/zoom |
| `GeoProjector.kt` | ViewModel / overlays | Unreal / geo → floor-plan pixel |
| `IncidentOverlays.kt` | `MapSessionViewModel` | DTO → `MapThreat` / `ColorPathway` / render models |
| `MapLayerHelpers.kt` | ViewModel, overlays | Outdoor/building/floor id normalization, mosaic layer selection |
| `DroneMapVisibility.kt` | ViewModel | Which drones belong on current layer |
| `AnnotationIcons.kt` | Map UI | Annotation type → drawable |

### Presentation

| File | Used by | Purpose |
|------|---------|---------|
| `SplashViewModel` / `SplashFragment` | Nav | Restore session → Home or Login |
| `OnboardingViewModel` / `LoginFragment` | Nav | Invite code, campus pick, OAuth |
| `OAuthWebViewActivity` | Login | In-app browser for IdP |
| `HomeViewModel` / `HomeFragment` | Nav | Poll incidents, join → launch map |
| `IncidentAdapter` | Home | Incident list rows |
| `MapSessionViewModel` | Map Activity | Bootstrap map, socket, follow drone, overlays, banners |
| `MapSessionActivity` | Intent from Home | Binds state/flows to mosaic + list |
| `DroneListAdapter` / `DroneStatusUi` / `BuildingAdapter` | Map Activity | Side panel UI |

---

## 6. Auth & session

```text
Invite code
    │
    ▼
GET districts/invite-code/{code}     AuthInteract.getDistrict
    │
    ▼
OAuth WebView (Microsoft AD / Cognito URL from AppConfig)
    │  redirect with ?code=
    ▼
POST auth/token                      AuthInteract.exchangeCode
    │
    ▼
SessionStore (DataStore)             access + refresh + user_detail
    │
    ▼
Optional: campuses + selectCampus
    │
    ▼
Home

Cold start:
  SplashViewModel → AuthInteract.restoreSession()
    → GET auth/access-token-login (refresh profile)
    → 401 clears store → Login
    → network errors keep cached session
```

Every authenticated REST call goes through `AuthInterceptor` (raw access token as `Authorization`).  
Tile downloads use a separate OkHttp client (`named("tileClient")`) with **no** JWT.

---

## 7. Home / incidents

```text
HomeViewModel
  ├── startIncidentPolling() every ONGOING_INCIDENTS_POLL_MS (5s)
  │     └── HomeInteract.fetchOngoingIncidents()
  │           └── GET incidents/ongoing?district_id=…
  │
  └── join(incident)
        └── HomeInteract.joinIncident()
              └── POST incidents/join  →  ws_ticket + campus
                    └── emit HomeNav.MapSession → MapSessionActivity
```

Campus used for the map is taken from join response (or incident / session fallback) and written back to `SessionStore`.

---

## 8. Map session & live telemetry

### Bootstrap

```text
MapSessionActivity
    │ extras: incidentId, campusId, wsTicket
    ▼
MapSessionViewModel.bootstrap()
    ├── MapInteract.loadMapConfig(campusId)
    ├── MapInteract.loadAnnotations(campusId)   (paginated)
    ├── resolve outdoor / building / floor → mosaic layer
    └── connectSocket()
```

### WebSocket pipeline

```text
OkHttp WebSocket (wsClient: no read timeout, 20s ping)
        │ onMessage (OkHttp reader thread)
        ▼
SocketHelper scope (Dispatchers.IO)
        │ json.decodeFromString / decodeFromJsonElement
        ▼
StateFlows: drones · threats · colorPathways · connected
SharedFlow: events (Connected, Failed, ThreatIdentified, …)
        │
        ▼
MapSessionViewModel (viewModelScope = Main)
        │ drones.sample(TELEMETRY_UI_SAMPLE_MS ≈ 33ms)
        │ → publishDrones() / publishOverlays()
        ▼
StateFlow: _drones (DroneMapPose), _overlays, _state
        │
        ▼
MapSessionActivity
        ├── MosaicMapView.updateDrones / updateOverlays
        └── DroneListAdapter (rebuild only when stable fields change)
```

### Socket channels handled in `SocketHelper`

| Channel | Behavior |
|---------|----------|
| `connection_success` / `connection_failed` | Connection status; failed triggers reconnect with fresh ticket |
| `on-drone-telemetry` | Merge drone poses into `_drones` |
| `on-session-detail` | Full snapshot of threats + pathways; send `ack` |
| `threat-added` / `threat-removed` | Update `_threats`; new id → `ThreatIdentified` event |
| `color-pathway-added` / `color-pathway-removed` | Update `_colorPathways` |
| other | Emitted as `SocketEvent.Channel` |

**Reconnect:** tickets are single-use (~30s). `urlProvider` uses the join ticket once, then `MapInteract.refreshWsTicket` on each reconnect. Backoff ramps to `SOCKET_RECONNECT_CAP_MS` (30s).

### Map rendering (`MosaicMapView`)

1. Stretch low-res **overview** across mosaic world  
2. After zoom (or if no overview), load **hi-res tiles** from S3 via `tileClient`  
3. Draw pathways → annotations → threats → drones  
4. Drone updates call `postInvalidateOnAnimation()` (coalesced frames)

Coordinate path: Unreal / map config → `GeoProjector` / `unrealToFloorPlanPixel` → pixel markers on the mosaic.

---

## 9. Threading model

```text
┌──────────────────────┐     ┌──────────────────────┐     ┌─────────────────────┐
│  OkHttp / Socket IO  │ ──► │  Dispatchers.IO      │ ──► │  Main (ViewModel /  │
│  network + WS read   │     │  SocketHelper parse  │     │  Activity / View)   │
└──────────────────────┘     │  merge StateFlows    │     │  UI state + draw    │
                             └──────────────────────┘     └─────────────────────┘
```

| Work | Thread |
|------|--------|
| WS JSON parse & map merges | `SocketHelper` → `Dispatchers.IO` |
| REST via Retrofit suspend | OkHttp / coroutine (not Main for network) |
| Collect flows, update UI state | `viewModelScope` (Main) |
| `MosaicMapView.onDraw` | Main |
| Tile bitmap download | `Dispatchers.IO`, then Main `invalidate` |

At ~20 drones @ 1 Hz this path is expected to stay smooth; telemetry UI sampling caps how often Main publishes map poses.

---

## 10. Dependency injection

Defined in `di/AppModule.kt`, started from `DroneTelemetryApp`.

```text
singles
  Json, SessionStore, SessionEvents
  AuthInterceptor, UnauthorizedInterceptor, OAuthHelper
  OkHttpClient (API) · named("tileClient") · named("wsClient")
  Retrofit → ApiService
  SocketHelper(wsClient, Json)
  AuthInteract, HomeInteract, MapInteract

viewModels
  SplashViewModel(AuthInteract)
  OnboardingViewModel(AuthInteract, OAuthHelper)
  HomeViewModel(HomeInteract, AuthInteract, SessionStore)
  MapSessionViewModel(MapInteract, SocketHelper, …)
```

`MapSessionViewModel` is created with Koin parameters for `incidentId`, `campusId`, and initial `wsTicket` from the Activity intent.

---

## 11. Key API & socket channels

### REST (`ApiService`)

| Method | Path | Used in |
|--------|------|---------|
| GET | `districts/invite-code/{code}` | Auth |
| POST | `auth/token` | Auth |
| POST | `auth/refresh-token` | (available; session restore prefers access-token-login) |
| GET | `auth/access-token-login` | Splash restore |
| GET | `campuses` | Onboarding campus list |
| GET | `incidents/ongoing` | Home poll |
| POST | `incidents/join` | Home join → map |
| POST | `incidents/ws-ticket` | Map reconnect |
| GET | `campuses/maps/{campusId}` | Map bootstrap |
| GET | `annotations` | Map annotations (paged) |

### WebSocket URL

Built by `SocketHelper.buildWsUrl`:

```text
wss://{host}/incidents/ws?ticket=…&incident_id=…&client_type=mobile
```

---

## Quick “where do I change X?”

| Goal | Start here |
|------|------------|
| API base URL / OAuth | `AppConfig` + `app/build.gradle.kts` `BuildConfig` |
| New REST endpoint | `ApiService` → matching `*Interact` → ViewModel |
| New WS channel | `SocketHelper.handleMessage` → expose Flow → `MapSessionViewModel` |
| Marker look / draw cost | `MosaicMapView.drawDrone` / `onDraw` |
| Layer / floor rules | `MapLayerHelpers`, `DroneMapVisibility` |
| Threat / pathway placement | `IncidentOverlays` |
| Login / session bugs | `AuthInteract`, `SessionStore`, interceptors |
| Incident list / join | `HomeInteract`, `HomeViewModel` |

---

## Related tests

- `app/src/test/.../map/IncidentOverlaysTest.kt` — overlay / coordinate helpers  
- Default unit / instrumented placeholders under `com.guardianangel.dronetelementry`

---

*Generated for the DroneTelementryPOC codebase. Behavior intentionally aligns with Mithril desktop/web incident map patterns where noted in code comments.*
