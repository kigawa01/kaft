# kaft 設計・仕様規約

## アップロード時の存在確認と作成をatomicにする（refs #20）

### 概要

現在のアップロード処理は `fileStorage.exists(id)` の後に `fileStorage.savePending(id, data)` を呼び出しており、
同一UUIDへの並行アップロードでTOCTOU raceが発生し得る。`FileStorage`に「存在確認＋作成」を一体化した
atomic create APIを導入する。

### 設計方針

| 項目 | 方針 |
|---|---|
| API | `FileStorage.savePending(id, data)` を `createPending(id, data): CreateResult` に置き換える |
| `CreateResult` | `sealed interface CreateResult { data object Created; data object AlreadyExists }`（issue記載のとおり） |
| route側 | `FileRoutes.kt`の`PUT /files/{uuid}`で`exists()`事前チェックを廃止し、`createPending()`の結果だけで201/409を判定する |
| 非同期化 | 本issueでは`suspend`化は行わない（#22でまとめて対応するスコープ）。既存の同期APIのまま、backend内部でOS/ストレージレベルのatomic操作を使う |
| Local backend | `Files.createDirectory(dir)`（複数形の`createDirectories`ではない）を使う。ディレクトリ作成はファイルシステムレベルでatomicなため、2つ目以降の呼び出しは`FileAlreadyExistsException`を受け取り`AlreadyExists`を返す。ディレクトリ作成に成功した呼び出しのみデータ・メタを書き込む |
| R2 backend | S3互換の条件付き書き込み（`PutObjectRequest.ifNoneMatch("*")`）を`meta.json`オブジェクトへのPUTに使う。R2は2024年からConditional Writesをサポート済み。precondition failed（HTTP 412）を`AlreadyExists`として扱う。meta書き込みが成功した場合のみdataを書き込む（Local同様、"claim"を先に取ってからデータを書く順序にする） |
| 既存の`exists()`メソッド | `confirm`/`delete`/`updateVisibility`の存在チェックとしてはそのまま維持する（本issueのスコープ外） |

### 変更内容

**`storage/FileStorage.kt`**

```kotlin
sealed interface CreateResult {
    data object Created : CreateResult
    data object AlreadyExists : CreateResult
}

interface FileStorage {
    fun exists(id: FileId): Boolean
    fun createPending(id: FileId, data: ByteArray): CreateResult
    fun confirm(id: FileId)
    fun getBytes(id: FileId): ByteArray?
    fun getMeta(id: FileId): FileMeta?
    fun delete(id: FileId)
    fun updateVisibility(id: FileId, visibility: Visibility)
}
```

**`storage/LocalFileStorage.kt`**

```kotlin
override fun createPending(id: FileId, data: ByteArray): CreateResult {
    try {
        Files.createDirectory(fileDir(id))
    } catch (e: FileAlreadyExistsException) {
        return CreateResult.AlreadyExists
    }
    Files.write(dataPath(id), data)
    writeMeta(id, FileMeta(state = FileState.PENDING, visibility = Visibility.PRIVATE))
    return CreateResult.Created
}
```

**`storage/R2FileStorage.kt`**

```kotlin
override fun createPending(id: FileId, data: ByteArray): CreateResult {
    try {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(metaKey(id)).ifNoneMatch("*").build(),
            RequestBody.fromString(Json.encodeToString(FileMeta(state = FileState.PENDING, visibility = Visibility.PRIVATE))),
        )
    } catch (e: S3Exception) {
        if (e.statusCode() == 412) return CreateResult.AlreadyExists else throw e
    }
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(dataKey(id)).build(),
        RequestBody.fromBytes(data),
    )
    return CreateResult.Created
}
```

**`routes/FileRoutes.kt`**

```kotlin
put("/files/{uuid}") {
    val fileId = ...
    val token = ...
    if (!jwtService.verifyUploadToken(token, fileId.toString())) return@put call.respond(Unauthorized)

    val data = call.receive<ByteArray>()
    when (fileStorage.createPending(fileId, data)) {
        is CreateResult.Created -> call.respond(HttpStatusCode.Created)
        is CreateResult.AlreadyExists -> call.respond(HttpStatusCode.Conflict)
    }
}
```

### テスト

- `LocalFileStorageTest.kt`（新規）
  - 新規IDへの`createPending` → `Created`、`exists()`が`true`になる
  - 既存IDへの`createPending`（逐次2回目） → `AlreadyExists`
  - 複数スレッドから同一IDへ同時に`createPending`を呼び出し、`Created`になるのはちょうど1件であることを確認
- `FileRoutesTest.kt`の既存`duplicate upload returns 409`テストは新しい`createPending`経由でも通ることを確認する

### 作成・変更ファイル一覧（#20）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `src/main/kotlin/net/kigawa/kaft/storage/FileStorage.kt` | 更新 | `CreateResult`追加、`savePending`を`createPending`に置き換え |
| `src/main/kotlin/net/kigawa/kaft/storage/LocalFileStorage.kt` | 更新 | `Files.createDirectory`によるatomic create |
| `src/main/kotlin/net/kigawa/kaft/storage/R2FileStorage.kt` | 更新 | `ifNoneMatch("*")`によるatomic create |
| `src/main/kotlin/net/kigawa/kaft/routes/FileRoutes.kt` | 更新 | `exists()`事前チェックを廃止し`createPending()`の結果で分岐 |
| `src/test/kotlin/net/kigawa/kaft/storage/LocalFileStorageTest.kt` | 新規作成 | 逐次・並行createPendingのテスト |

---

## ファイルIDをUUID型として検証・扱う（refs #19）

### 概要

現在、`/files/{uuid}` などで受け取ったファイルIDを `String` のまま扱っており、`LocalFileStorage` では
`baseDir.resolve(uuid)` にそのまま渡している。不正なUUID文字列を受理してしまい、Local backendでは
任意文字列がパス解決に使われてしまう。ファイルIDはアプリケーション境界でUUIDとして検証し、
`FileStorage` API全体で型として保証する。

### 設計方針

| 項目 | 方針 |
|---|---|
| 型 | `@JvmInline value class FileId(val value: UUID)` を `storage`パッケージに新規追加 |
| 検証タイミング | route parameter受領時（`FileRoutes.kt`・`InternalRoutes.kt`の両方） |
| 検証失敗時 | `400 Bad Request` |
| `FileStorage`のAPI | 全メソッドの引数を `String` → `FileId` に変更する |
| Local/R2 backend | ディレクトリ名・R2オブジェクトキーの構築を `FileId` 経由にする（`FileId.toString()`でUUID文字列を得る。既存のディレクトリ/キー命名と完全互換） |
| JWT検証との連携 | `JwtService.verifyUploadToken`/`verifyReadToken` は現状どおり `String` を受け取るAPIのまま変更しない（本issueのスコープ外）。呼び出し側で `fileId.toString()` を渡す |
| `/internal/token` の `TokenRequest.uuid`/`uuids`（リクエストボディ） | Storage層に渡らないため本issueのスコープ外とする（JWT発行ロジックの型強化は将来issueで検討） |

### 変更内容

**`storage/FileId.kt`（新規）**

```kotlin
@JvmInline
value class FileId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun parseOrNull(raw: String): FileId? = try {
            FileId(UUID.fromString(raw))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
```

**`storage/FileStorage.kt`**

- `exists`, `savePending`, `confirm`, `getBytes`, `getMeta`, `delete`, `updateVisibility` の引数を `uuid: String` → `id: FileId` に変更

**`storage/LocalFileStorage.kt` / `storage/R2FileStorage.kt`**

- 上記シグネチャ変更に追随。パス/キー構築は `id.toString()`（=UUID文字列）を使うため既存ファイルとの互換性を保つ

**`routes/FileRoutes.kt` / `routes/InternalRoutes.kt`**

- `call.parameters["uuid"]` 取得後、`FileId.parseOrNull(...)` で検証し、`null`なら`400 Bad Request`
- `FileStorage`呼び出しは`FileId`を渡す。`JwtService`呼び出しは`fileId.toString()`を渡す

### テスト

- `FileRoutesTest.kt` に以下を追加:
  - 不正なUUID形式で `PUT /files/{malformed}` → `400`
  - 不正なUUID形式で `GET /files/{malformed}/file.bin` → `400`
- 既存テストは `UUID.randomUUID().toString()` を使用しており無変更で通ることを確認する

### 作成・変更ファイル一覧（#19）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `src/main/kotlin/net/kigawa/kaft/storage/FileId.kt` | 新規作成 | `FileId` value class |
| `src/main/kotlin/net/kigawa/kaft/storage/FileStorage.kt` | 更新 | 引数を`FileId`に変更 |
| `src/main/kotlin/net/kigawa/kaft/storage/LocalFileStorage.kt` | 更新 | `FileId`対応 |
| `src/main/kotlin/net/kigawa/kaft/storage/R2FileStorage.kt` | 更新 | `FileId`対応 |
| `src/main/kotlin/net/kigawa/kaft/routes/FileRoutes.kt` | 更新 | UUID検証・400応答を追加 |
| `src/main/kotlin/net/kigawa/kaft/routes/InternalRoutes.kt` | 更新 | UUID検証・400応答を追加 |
| `src/test/kotlin/net/kigawa/kaft/FileRoutesTest.kt` | 更新 | malformed UUIDのテストを追加 |

---

## Internal JWTのissuer・audience検証（refs #23）

### 概要

現在の Internal JWT verifier（`JwtService.internalVerifier`）は署名と `scope=internal` claimのみを検証しており、
`iss`（issuer）・`aud`（audience）を検証していない。API Server → kaft のサーバー間認証の境界を明確にするため、
issuer・audienceの検証を必須化する。

### 設計方針

| 項目 | 方針 |
|---|---|
| issuer検証 | `InternalConfig.issuer` に設定された値と一致することを要求する |
| audience検証 | `InternalConfig.audience` に設定された値と一致することを要求する |
| 設定方法 | 既存の`kaft.internal.*`設定と同様、`application.conf` + 環境変数で設定可能にする（ハードコード禁止というdev.mdの規約に従う） |
| デフォルト値 | issuer: `api-server` / audience: `kaft`（`docs/spec.md`のJWTペイロード設計例に合わせる） |
| scope検証 | 既存の`withClaim("scope", "internal")`をそのまま維持 |
| 有効期限検証 | auth0 java-jwtライブラリの標準検証（`verify()`時に自動でexpiredを拒否）をそのまま利用。追加実装は不要 |

### 影響範囲・注意点

- **既存のInternal JWT発行者（API Server側）は `iss`・`aud` claimを新たに含める必要がある。** 現時点でkaft自身はInternal JWTを発行していない（テストコードのみ）ため、実際にAPI Serverを運用している場合はこの変更に合わせて発行ロジックの追随が必要になる
- k8sのdev/stg/main環境では新しい環境変数を明示的に設定しない限りデフォルト値（`api-server` / `kaft`）が使われる

### 変更内容

**`KaftConfig.kt`**

```kotlin
data class InternalConfig(
    val jwtSecret: String,
    val issuer: String,
    val audience: String,
)
```

- `kaft.internal.issuer`（環境変数 `KAFT_INTERNAL_JWT_ISSUER`、デフォルト `api-server`）
- `kaft.internal.audience`（環境変数 `KAFT_INTERNAL_JWT_AUDIENCE`、デフォルト `kaft`）

**`application.conf`**

```hocon
internal {
    jwtSecret = "change-this-internal-secret-in-production"
    jwtSecret = ${?KAFT_INTERNAL_JWT_SECRET}
    issuer = "api-server"
    issuer = ${?KAFT_INTERNAL_JWT_ISSUER}
    audience = "kaft"
    audience = ${?KAFT_INTERNAL_JWT_AUDIENCE}
}
```

**`JwtService.kt`**

```kotlin
private val internalVerifier = JWT.require(internalAlgorithm)
    .withIssuer(internalConfig.issuer)
    .withAudience(internalConfig.audience)
    .withClaim("scope", "internal")
    .build()
```

**`TestHelpers.kt`**

- `TEST_INTERNAL_ISSUER` / `TEST_INTERNAL_AUDIENCE` 定数を追加
- `createTestConfig()` に `kaft.internal.issuer` / `kaft.internal.audience` を追加
- `issueInternalToken()` に `.withIssuer(TEST_INTERNAL_ISSUER)` / `.withAudience(TEST_INTERNAL_AUDIENCE)` を追加

### テスト

新規 `JwtServiceTest.kt` を作成し、`JwtService.verifyInternalToken()` を直接検証する。

- issuer不一致のトークン → 拒否（false）
- audience不一致のトークン → 拒否（false）
- scope不一致のトークン → 拒否（false、既存動作の回帰確認）
- 期限切れのトークン → 拒否（false）
- issuer・audience・scope・期限すべて正しいトークン → 許可（true）

既存の `FileRoutesTest.kt`（`issueInternalToken()`経由）は `TestHelpers.kt` 更新後も無変更で通ることを確認する。

### 作成・変更ファイル一覧（#23）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `src/main/kotlin/net/kigawa/kaft/config/KaftConfig.kt` | 更新 | `InternalConfig`に`issuer`・`audience`を追加 |
| `src/main/resources/application.conf` | 更新 | `kaft.internal.issuer`・`kaft.internal.audience`を追加 |
| `src/main/kotlin/net/kigawa/kaft/auth/JwtService.kt` | 更新 | `internalVerifier`にissuer・audience検証を追加 |
| `src/test/kotlin/net/kigawa/kaft/TestHelpers.kt` | 更新 | テスト用issuer・audience定数とトークン発行の追随 |
| `src/test/kotlin/net/kigawa/kaft/auth/JwtServiceTest.kt` | 新規作成 | issuer/audience/scope/期限の検証テスト |

---

## デフォルトJWT secretによる起動禁止（refs #27）

### 概要

`application.conf` の `kaft.jwt.secret` / `kaft.internal.jwtSecret` にはデフォルト値として
`change-this-secret-in-production` / `change-this-internal-secret-in-production` が設定されている。
環境変数 `KAFT_JWT_SECRET` / `KAFT_INTERNAL_JWT_SECRET` の設定漏れがあってもアプリケーションが
起動できてしまうため、既知のデフォルト値のままでは起動を拒否できるようにする。

### 設計方針

| 項目 | 方針 |
|---|---|
| チェック対象 | `kaft.jwt.secret`、`kaft.internal.jwtSecret` |
| チェック内容 | 空文字列、または既知のデフォルト値と一致する場合は起動失敗させる |
| チェック範囲 | 環境フラグ（production/dev等）を新設せず、常に一律で検証する |
| チェックタイミング | `KaftConfig.fromApplication()` 内、Application module起動時（最も早い段階） |
| dev/testでの扱い | `TestHelpers.createTestConfig()` は既に固有の値（`test-jwt-secret` 等）を設定しており影響なし。ローカル`./gradlew run`時も環境変数で明示的な値の設定が必須になる |
| R2 credential等の必須設定チェック | 本issueのスコープ外とする（完了条件に含まれないため見送り、必要なら別issueで対応） |

環境フラグを新設しない理由: 現状コードベースに production/dev を判定する仕組みが存在せず、
k8s上のdev/stg/main環境はいずれも `Secret` リソース経由で明示的な値を注入する運用のため、
「デフォルト値を許可しない」を全環境で一律に適用しても実運用への影響がない。

### 変更内容

`KaftConfig.fromApplication()` に以下の検証を追加する。

```kotlin
private const val DEFAULT_JWT_SECRET = "change-this-secret-in-production"
private const val DEFAULT_INTERNAL_JWT_SECRET = "change-this-internal-secret-in-production"

private fun requireSecureSecret(value: String, envVarName: String): String {
    check(value.isNotBlank() && value != DEFAULT_JWT_SECRET && value != DEFAULT_INTERNAL_JWT_SECRET) {
        "環境変数 $envVarName が未設定、またはデフォルト値のままです。安全な値を設定してください。"
    }
    return value
}
```

- `jwt.secret` 読み込み時に `requireSecureSecret(..., "KAFT_JWT_SECRET")` を通す
- `internal.jwtSecret` 読み込み時に `requireSecureSecret(..., "KAFT_INTERNAL_JWT_SECRET")` を通す
- 検証失敗時は `IllegalStateException` を送出し、アプリケーション起動が失敗する（Ktorはmodule内の例外でstartupを中断する）

### テスト

`KaftConfigTest.kt` を新規作成し、以下を検証する。

- `kaft.jwt.secret` が空文字列 → 起動時（`KaftConfig.fromApplication`呼び出し時）に例外
- `kaft.jwt.secret` がデフォルト値のまま → 例外
- `kaft.internal.jwtSecret` がデフォルト値のまま → 例外
- 有効な値が設定されている場合は正常に `KaftConfig` を構築できる

### 作成・変更ファイル一覧（#27）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `src/main/kotlin/net/kigawa/kaft/config/KaftConfig.kt` | 更新 | デフォルト値・空文字列の場合に起動失敗させる検証を追加 |
| `src/test/kotlin/net/kigawa/kaft/config/KaftConfigTest.kt` | 新規作成 | 設定検証のテスト |

---

## 実装完了PRのCloses規約強化（refs #8）

### 概要

PR #6 で実装完了PRのbodyに `refs #2` を使用したため issue #2 が自動クローズされなかった。
`Closes #<番号>` の使用を CLAUDE.md・docs/dev.md でより強く明示し、見落としを防ぐ。

### 問題

- 規約自体は CLAUDE.md・docs/dev.md に既に記載済み
- 強調が不足していたため AI エージェントが見落とした

### 変更方針

| ファイル | 変更内容 |
|---|---|
| `CLAUDE.md` | ステップ5（PR作成）に CRITICAL 警告を追加し、`Closes` の使用を強制する |
| `docs/dev.md` | PRの規約セクションに注意書きを追加する |

### 変更詳細

**CLAUDE.md ステップ5 PR作成**

- 現在: `PR本文に必ず含めること:` として箇条書き
- 変更後: `> **CRITICAL（必須）**` ブロックを追加し、実装完了PRに `Closes #<番号>` を使わないと issue が自動クローズされないことを明示する

**docs/dev.md PRの規約セクション**

- 現在: テーブルで `Closes` と `refs` の使い分けを記載
- 変更後: テーブルの下に「実装完了PRで `Closes` を省略すると issue が自動クローズされない」旨の警告を追加する

### 作成・変更ファイル一覧（#8）

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `CLAUDE.md` | 更新 | ステップ5にCRITICAL警告を追加 |
| `docs/dev.md` | 更新 | PRの規約セクションに警告を追加 |

---

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

---

## k8s デプロイ設計（refs #7）

### 概要

kaft を Kubernetes 上に、kigawa-net/lipl と同様の3環境構成（dev/stg/main）でデプロイする。マニフェストは本リポジトリ（kaft）内に作成し、ArgoCD登録は `kigawa01/k8s-system` から行う。

### デプロイトリガー

| トリガー | 環境 | Namespace | イメージタグ |
|---|---|---|---|
| PRに `deploy-preview` ラベルを付与 | **dev**（PRごとに独立） | `kaft-dev-pr-<PR番号>` | `develop-<commit-sha>` |
| `develop` リポジトリの `main` へマージ | **stg** | `kaft-stg` | `main-<commit-sha>` |
| `deploy-prod.yml` を手動実行（workflow_dispatch） | **main（本番相当）** | `kaft-main` | stgに現在デプロイされているものと同一（再ビルドしない） |

- kaft はpublicリポジトリのため、ArgoCD ApplicationSetのPull Request Generatorに `github.labels: [deploy-preview]` フィルタを設定し、外部の任意PRでdev環境が自動生成されないようにする（kigawa-net/lipl と同じ対策）
- stgはKtorのテスト（`./gradlew test`）通過後、Docker build & push、`kigawa01/k8s-system` 側マニフェスト更新まで自動で行う
- main（本番相当）はstgで検証済みイメージをそのまま手動プロモートする（再ビルドしない）

### ドメイン

| 環境 | ホスト |
|---|---|
| main | `kaft.kigawa.net` |
| stg | `kaft-stg.kigawa.net` |
| dev | 割り当てなし（`kubectl port-forward` で確認） |

### R2バケット

環境ごとにデータを分離するため、バケットを分ける。

| 環境 | バケット名 |
|---|---|
| main | `kaft`（作成済み、kigawa-net/infra#85） |
| stg | `kaft-stg`（新規作成） |
| dev | `kaft-dev`（新規作成、PRごとの共有バケット） |

R2アカウント認証情報（access key/secret key/account id）は既存の `r2-access`/`r2-secret`/`r2-account` Bitwarden シークレットを流用する。

### Dockerfile

```dockerfile
FROM gradle:8-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S kaft && adduser -S kaft -G kaft
WORKDIR /app
COPY --from=builder --chown=kaft:kaft /app/build/libs/*-all.jar app.jar
USER kaft
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

（lipl backendと同様、非rootユーザーで実行する）

### k8s リソース設計（kaft/k8s/{dev,stg,main}/）

lipl/platform と同様のKustomize構成:

| リソース | 内容 |
|---|---|
| Deployment + Service | `kaft` コンテナ、ポート8080 |
| 環境変数 | `PORT`, `KAFT_STORAGE_BACKEND=r2`, `KAFT_STORAGE_R2_ACCOUNT_ID`, `KAFT_STORAGE_R2_BUCKET`, `KAFT_STORAGE_R2_ACCESS_KEY_ID`（Secret）, `KAFT_STORAGE_R2_SECRET_ACCESS_KEY`（Secret）, `KAFT_JWT_SECRET`（Secret）, `KAFT_JWT_ISSUER`, `KAFT_JWT_EXPIRATION_SECONDS`, `KAFT_INTERNAL_JWT_SECRET`（Secret） |
| BitwardenSecret | JWT系シークレット（新規生成・Bitwarden登録）、R2認証情報（既存流用） |
| Ingress（stg/mainのみ） | ホスト名は上記ドメイン表参照 |

### ArgoCD登録（kigawa01/k8s-system側で対応）

- `apps/kaft-dev-appset.yml`: ApplicationSet（Pull Request Generator、`deploy-preview`ラベルフィルタ）、sourceは本リポジトリ（kaft）の `k8s/dev`
- `apps/kaft-stg-app.yml`, `apps/kaft-main-app.yml`: 静的Application、sourceはそれぞれ `k8s/stg`, `k8s/main`

### CI/CD（本リポジトリ側）

lipl と同様に `.github/workflows/deploy-dev.yml`（PR、テスト+ビルド+push、マニフェスト更新なし）、`deploy-stg.yml`（`develop`ブランチへのpush → テスト+ビルド+push+`kigawa01/k8s-system`へのマニフェスト更新）、`deploy-prod.yml`（`workflow_dispatch`、stgの現在のイメージタグをmainへコピー）の3ワークフローを作成する。

※ 本リポジトリのブランチ戦略では `develop` が統合ブランチのため、「mainへのマージでstg」ではなく「**developへのマージでstg**」と読み替える点に注意（kaft独自の運用）。

### 作成・変更ファイル一覧（#7）

| ファイル | 変更種別 |
|---|---|
| `Dockerfile` | 新規作成 |
| `k8s/dev/`, `k8s/stg/`, `k8s/main/`（各: deployment.yaml, service.yaml, kustomization.yaml。stg/mainのみingress.yaml） | 新規作成 |
| `.github/workflows/deploy-dev.yml`, `deploy-stg.yml`, `deploy-prod.yml` | 新規作成 |
| `kigawa01/k8s-system` の `apps/kaft-dev-appset.yml`, `apps/kaft-stg-app.yml`, `apps/kaft-main-app.yml` | 新規作成（別リポジトリ） |
| `kigawa-net/infra` の `hardware/cloudflare/r2.tf` | 変更（`kaft-dev`, `kaft-stg`バケット追加） |
