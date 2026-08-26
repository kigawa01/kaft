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
File Server が JWT 発行・ファイル操作の全エンドポイントを持つ。API Server は任意のプログラムが担い、ユーザー認証と File Server への橋渡しを行う。

### 設計方針

| 項目 | 方針 |
|---|---|
| ファイル識別子 | UUID v4（API Server がアップロード要求時に発行） |
| JWT 発行 | File Server が発行。API Server は任意の方法で Client に渡す |
| サーバー間認証 | API Server → File Server 間も JWT で認証 |
| immutability | ファイル本体は変更不可。公開設定（public/private）のみ変更可 |
| 公開設定 | public: read token 不要 / private: read token 必須 |
| ファイル名指定 | GET リクエスト時にクエリパラメータでファイル名を指定可能 |

### コンポーネント構成

```mermaid
graph LR
    C[Client] -->|任意の方法| A[API Server<br/>任意のプログラム]
    C -->|PUT / GET| F[File Server]
    A -->|内部API + サーバー間JWT| F
```

| コンポーネント | 役割 |
|---|---|
| Client | File Server に対してファイル操作を行う |
| API Server | ユーザー認証・UUID 発行・File Server からの JWT 取得と Client への受け渡し（方法は任意） |
| File Server | JWT 発行・検証・ファイル保存・配信・公開設定管理 |

### File Server エンドポイント一覧

**Client 向け**

| メソッド | パス | 説明 |
|---|---|---|
| `PUT` | `/files/{uuid}` | ファイルアップロード（upload token 必須） |
| `GET` | `/files/{uuid}/{filename}` | ファイル取得。public は token 不要、private は read token 必須。パスのファイル名がそのまま Content-Disposition に使用される |

**API Server 向け内部 API**

| メソッド | パス | 説明 |
|---|---|---|
| `POST` | `/internal/token` | JWT 発行（upload token / read token） |
| `POST` | `/internal/files/{uuid}/confirm` | アップロード確定 |
| `DELETE` | `/internal/files/{uuid}` | ファイル削除 |
| `PATCH` | `/internal/files/{uuid}/visibility` | 公開設定変更 |

### アップロード処理フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    C->>A: アップロード要求（任意の方法）
    A->>A: ユーザー認証・UUID v4 発行
    A->>F: POST /internal/token（サーバー間JWT、uuid、scope=upload）
    F->>F: アップロード用JWT発行
    F-->>A: upload_token
    A-->>C: uuid と upload_token を任意の方法で渡す

    C->>F: PUT /files/{uuid}（upload_token）
    F->>F: JWT検証（scope=upload・sub={uuid}一致）
    F->>F: UUID既存チェック（重複なら409）
    F->>F: バイナリを pending 状態で保存
    F-->>C: 201 Created

    C->>A: 確定要求（任意の方法）
    A->>F: POST /internal/files/{uuid}/confirm（サーバー間JWT）
    F->>F: ファイルを confirmed 状態に確定
    F-->>A: 200 OK
    A-->>C: 完了通知（任意の方法）
```

### ファイル取得フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    alt private ファイルの場合
        C->>A: read token 要求（任意の方法、対象 UUID リストを渡す）
        A->>A: ユーザー認証
        A->>F: POST /internal/token（サーバー間JWT、uuids リスト、scope=read）
        F->>F: read token 発行（uuids リストを含む）
        F-->>A: read_token
        A-->>C: read_token を任意の方法で渡す
        C->>F: GET /files/{uuid}/photo.jpg（read_token）
        F->>F: JWT検証・token内のuuidsに{uuid}が含まれるか確認
    else public ファイルの場合
        C->>F: GET /files/{uuid}/photo.jpg
    end

    F->>F: ストレージからバイナリ取得
    F-->>C: 200 OK（バイナリ、Content-Disposition付き）
```

### ファイル削除フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    C->>A: 削除要求（任意の方法）
    A->>A: ユーザー認証
    A->>F: DELETE /internal/files/{uuid}（サーバー間JWT）
    F->>F: ストレージからファイル削除
    F-->>A: 204 No Content
    A-->>C: 完了通知（任意の方法）
```

### 公開設定変更フロー

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Server
    participant F as File Server

    C->>A: 公開設定変更要求（任意の方法）
    A->>A: ユーザー認証
    A->>F: PATCH /internal/files/{uuid}/visibility（サーバー間JWT、public or private）
    F->>F: 公開設定を更新（ファイル本体は変更しない）
    F-->>A: 200 OK
    A-->>C: 完了通知（任意の方法）
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
- 削除は API Server 経由で可能
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
| `src/main/kotlin/routes/FileRoutes.kt` | 新規作成 | Client 向けエンドポイント（PUT / GET） |
| `src/main/kotlin/routes/InternalRoutes.kt` | 新規作成 | API Server 向け内部エンドポイント |
| `src/main/kotlin/storage/FileStorage.kt` | 新規作成 | ストレージ操作（UUID キー読み書き・削除・公開設定管理） |
| `src/main/kotlin/auth/JwtService.kt` | 新規作成 | JWT 発行・検証ロジック |
| `src/test/kotlin/` | 新規作成 | 各コンポーネントのテスト |

---

## R2ストレージバックエンドの追加（refs #12）

### 概要

現在の `FileStorage` はローカルファイルシステム専用の実装になっている。Cloudflare R2（S3互換オブジェクトストレージ）をバックエンドとして選択できるよう、ストレージ層を抽象化する。既存の PENDING/CONFIRMED 状態管理・可視性（public/private）管理の振る舞いは変更しない。

### 設計方針

| 項目 | 方針 |
|---|---|
| 抽象化 | `FileStorage` を interface 化し、`LocalFileStorage`（既存実装をリネーム）と `R2FileStorage`（新規）を用意する |
| 切り替え | 設定値 `kaft.storage.backend`（`local` または `r2`）で選択する。デフォルトは `local`（既存の挙動を壊さない） |
| R2クライアント | AWS SDK for Kotlin/Java の S3 クライアント（`software.amazon.awssdk:s3`）を使い、endpoint を R2 のエンドポイント（`https://<accountId>.r2.cloudflarestorage.com`）に差し替える（R2 は S3 互換 API を提供するため） |
| メタデータ | `meta.json` 相当の `FileMeta`（state, visibility）はオブジェクトのメタデータ（S3 Object Metadata）またはオブジェクトキー `{uuid}/meta.json` として別途保存する（後者を採用し、既存のローカル実装と対称的な構造を保つ） |
| オブジェクトキー | データ本体: `{uuid}/data`、メタデータ: `{uuid}/meta.json`（ローカル実装のディレクトリ構造と対応させる） |

### 設定項目（R2バックエンド利用時）

| 環境変数 | 説明 |
|---|---|
| `KAFT_STORAGE_BACKEND` | `local` または `r2` |
| `KAFT_R2_ACCOUNT_ID` | CloudflareアカウントID（エンドポイントURL組み立てに使用） |
| `KAFT_R2_BUCKET` | バケット名 |
| `KAFT_R2_ACCESS_KEY_ID` | R2 APIトークンのAccess Key ID |
| `KAFT_R2_SECRET_ACCESS_KEY` | R2 APIトークンのSecret Access Key |

### 作成・変更ファイル一覧（#12）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `build.gradle.kts` | 変更 | `software.amazon.awssdk:s3` 依存を追加 |
| `src/main/kotlin/net/kigawa/kaft/storage/FileStorage.kt` | 変更 | interface化。`FileMeta`/`FileState`/`Visibility` は共通のまま維持 |
| `src/main/kotlin/net/kigawa/kaft/storage/LocalFileStorage.kt` | 新規作成 | 既存実装を移動（クラス名変更のみ、ロジックは変更しない） |
| `src/main/kotlin/net/kigawa/kaft/storage/R2FileStorage.kt` | 新規作成 | S3互換クライアントによるR2実装 |
| `src/main/kotlin/net/kigawa/kaft/config/KaftConfig.kt` | 変更 | ストレージ関連設定を `StorageConfig`（backend種別 + local/r2それぞれの設定）に拡張 |
| `src/main/kotlin/net/kigawa/kaft/Application.kt` | 変更 | `StorageConfig` に応じて `LocalFileStorage`/`R2FileStorage` を選択して生成する |
| `src/test/kotlin/.../storage/R2FileStorageTest.kt` | 新規作成 | R2互換のモック/ローカルS3実装（例: 既存のS3互換テストダブル）を用いたテスト、または単体では検証できない部分は手動確認とする |
