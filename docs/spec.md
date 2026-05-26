# kaft 設計・仕様規約

## リポジトリ規約設計（refs #1）

### 概要

kigawa-net/dilot を参考に、kaft リポジトリの開発規約・作業フロー・AI エージェント向けガイドラインを整備する。

### 作成・変更ファイル一覧（#1）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `CLAUDE.md` | 新規作成 | AI エージェント向けの作業フロー・ルール |
| `docs/dev.md` | 新規作成 | 開発規約（Git 運用・ブランチ戦略・コミット規約） |
| `docs/spec.md` | 新規作成（本ファイル） | 設計・仕様規約 |

### 規約内容の方針

dilot の規約をベースに、以下の内容を kaft 向けに整備する。

#### ブランチ戦略

| ブランチ | 用途 |
|---|---|
| `main` | リリース済みの安定版 |
| `develop` | 統合ブランチ。feature/fix ブランチのマージ先 |
| `plan/<issue番号>-<名前>` | 実装計画ドキュメント作成用 |
| `feature/<issue番号>-<名前>` | 機能追加 |
| `fix/<issue番号>-<名前>` | バグ修正 |

#### 作業フロー

1. **issue 確認** — 対象 issue が存在することを確認する
2. **計画 PR 作成・マージ** — `plan/<issue番号>-<名前>` ブランチで `docs/spec.md` に実装計画を記述し PR を作成する（マージ前に実装開始禁止）
3. **実装ブランチ作成** — 計画 PR マージ後に `feature/` または `fix/` ブランチを作成する
4. **コミット** — メッセージ末尾に `refs #<issue番号>` または `fix #<issue番号>` を含める
5. **PR 作成** — `develop` への PR を作成する

#### コミットメッセージ形式

```
<type>: <概要>

refs #<issue番号>
```

type 一覧: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`

#### PR 規約

| 状況 | キーワード |
|---|---|
| 実装完了 PR（issue を閉じる） | `Closes #<番号>` |
| 計画 PR・作業途中 PR | `refs #<番号>` |

#### コミュニケーション規約

- 会話・説明・レビューは日本語で行う
- コード識別子・コマンド・ログは原文のまま扱う

---

## 画像配信基盤の設計（refs #2）

### 概要

UUID で識別されたファイルを、JWT で認可制御しながら immutable に配信する基盤を設計・実装する。

### 設計方針

| 項目 | 方針 |
|---|---|
| ファイル識別子 | UUID v4（アップロード時に API Server が発行） |
| アクセス制御 | JWT（API Server が任意の方式で発行・有効期限あり） |
| サーバー間認証 | API Server と File Server 間も JWT で認証 |
| immutability | ファイル本体は変更不可。公開設定（public/private）のみ変更可 |
| 公開設定 | public: read token なしで取得可 / private: read token 必須 |

### コンポーネント構成

```mermaid
graph LR
    C[Client] -->|ユーザー操作| A[API Server]
    C -->|ファイルアップロード・取得| F[File Server]
    A -->|内部API（サーバー間JWT）| F
```

| コンポーネント | 役割 |
|---|---|
| Client | アップロード・取得・削除・設定変更を要求する |
| API Server | ユーザー認証・UUID 発行・JWT 発行（任意の方式）・File Server への委譲 |
| File Server | JWT 検証・ファイル保存・配信・公開設定管理 |

### エンドポイント一覧

**API Server（Client向け）**

| メソッド | パス | 説明 |
|---|---|---|
| `POST` | `/files/upload-token` | UUID 発行・アップロード用 JWT 発行 |
| `POST` | `/files/read-token` | 読み取り用 JWT 発行（指定した複数 UUID を含む） |
| `DELETE` | `/files/{uuid}` | ファイル削除（File Server へ委譲） |
| `PATCH` | `/files/{uuid}/visibility` | 公開設定変更（File Server へ委譲） |

**File Server（Client向け）**

| メソッド | パス | 説明 |
|---|---|---|
| `PUT` | `/files/{uuid}` | ファイルアップロード（Client から直接） |
| `GET` | `/files/{uuid}` | ファイル取得（public は token 不要、private は token 必須） |

**File Server（API Server向け内部API）**

| メソッド | パス | 説明 |
|---|---|---|
| `POST` | `/internal/files/{uuid}/confirm` | アップロード確定 |
| `DELETE` | `/internal/files/{uuid}` | ファイル削除 |
| `PATCH` | `/internal/files/{uuid}/visibility` | 公開設定変更 |

### アップロード処理フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    C->>A: POST /files/upload-token<br/>Authorization: Bearer <ユーザー認証JWT>
    A->>A: ユーザー認証JWT検証・UUID v4 発行
    A->>A: アップロード用JWT発行（sub=uuid, scope=upload）
    A-->>C: { uuid, upload_token }

    C->>F: PUT /files/{uuid}<br/>Authorization: Bearer <upload_token><br/>body: バイナリ
    F->>F: JWT検証（scope=upload・sub={uuid}一致）
    F->>F: UUID既存チェック（重複なら409）
    F->>F: バイナリを pending 状態で保存
    F-->>C: 201 Created

    C->>A: POST /files/{uuid}/confirm<br/>Authorization: Bearer <ユーザー認証JWT>
    A->>F: POST /internal/files/{uuid}/confirm<br/>Authorization: Bearer <サーバー間JWT>
    F->>F: ファイルを confirmed 状態に確定
    F-->>A: 200 OK
    A-->>C: 200 OK
```

### ファイル取得フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    alt private ファイルの場合
        C->>A: POST /files/read-token<br/>Authorization: Bearer <ユーザー認証JWT><br/>body: { uuids: ["uuid1", "uuid2", ...] }
        A->>A: ユーザー認証JWT検証
        A->>A: read token 発行（uuids リストを含む）
        A-->>C: { read_token }
        C->>F: GET /files/{uuid}<br/>Authorization: Bearer <read_token>
        F->>F: JWT検証・token内のuuidsに{uuid}が含まれるか確認
    else public ファイルの場合
        C->>F: GET /files/{uuid}
    end

    F->>F: ストレージからバイナリ取得
    F-->>C: 200 OK バイナリ<br/>Cache-Control: public, max-age=31536000, immutable
```

### ファイル削除フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    C->>A: DELETE /files/{uuid}<br/>Authorization: Bearer <ユーザー認証JWT>
    A->>A: ユーザー認証JWT検証
    A->>F: DELETE /internal/files/{uuid}<br/>Authorization: Bearer <サーバー間JWT>
    F->>F: ストレージからファイル削除
    F-->>A: 204 No Content
    A-->>C: 204 No Content
```

### 公開設定変更フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    C->>A: PATCH /files/{uuid}/visibility<br/>Authorization: Bearer <ユーザー認証JWT><br/>body: { visibility: "public" | "private" }
    A->>A: ユーザー認証JWT検証
    A->>F: PATCH /internal/files/{uuid}/visibility<br/>Authorization: Bearer <サーバー間JWT><br/>body: { visibility: "public" | "private" }
    F->>F: 公開設定を更新（ファイル本体は変更しない）
    F-->>A: 200 OK
    A-->>C: 200 OK
```

### JWT ペイロード設計

**アップロード用 JWT**

```json
{
  "sub": "<uuid>",
  "exp": 1234567890,
  "iat": 1234567000,
  "scope": "upload"
}
```

**読み取り用 JWT**

```json
{
  "uuids": ["<uuid1>", "<uuid2>"],
  "exp": 1234567890,
  "iat": 1234567000,
  "scope": "read"
}
```

**サーバー間 JWT（API Server → File Server）**

```json
{
  "iss": "api-server",
  "exp": 1234567890,
  "iat": 1234567000,
  "scope": "internal"
}
```

### immutability の保証

- ファイル本体はアップロード後に変更不可（上書きリクエストは 409 Conflict）
- 公開設定（public/private）はファイル本体と分離して管理し変更可能
- **削除は可能**（API Server 経由）
- public ファイルのキャッシュヘッダ: `Cache-Control: public, max-age=31536000, immutable`

### 技術スタック

| 項目 | 採用技術 |
|---|---|
| 言語 | Kotlin |
| フレームワーク | Ktor |
| JWT ライブラリ | `io.ktor:ktor-server-auth-jwt` |
| ビルドツール | Gradle (Kotlin DSL) |

### 作成・変更ファイル一覧（#2）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `build.gradle.kts` | 新規作成 | Gradle ビルド設定（Ktor・JWT 依存を含む） |
| `src/main/kotlin/Application.kt` | 新規作成 | Ktor アプリケーションエントリポイント |
| `src/main/kotlin/routes/FileRoutes.kt` | 新規作成 | `/files` エンドポイント定義 |
| `src/main/kotlin/storage/FileStorage.kt` | 新規作成 | ストレージ操作（UUID キー読み書き・削除・公開設定管理） |
| `src/main/kotlin/auth/JwtService.kt` | 新規作成 | JWT 発行・検証ロジック |
| `src/test/kotlin/` | 新規作成 | 各コンポーネントのテスト |
