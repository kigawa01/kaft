# kaft 開発規約

## コミュニケーション規約

- 会話・進捗報告・レビュー・説明は日本語で行う
- コード識別子・コマンド・ログ・外部エラーメッセージは原文のまま扱ってよい
- 英語の引用や出力が必要な場合も、説明・判断・次のアクションは日本語で記述する

---

## Gitブランチ戦略

| ブランチ | 用途 |
|---|---|
| `main` | リリース済みの安定版 |
| `develop` | 統合ブランチ。featureブランチのマージ先 |
| `plan/<issue番号>-<名前>` | 実装計画ドキュメント作成用 |
| `feature/<issue番号>-<名前>` | 機能追加。`develop` へPRを出す |
| `fix/<issue番号>-<名前>` | バグ修正。`develop` へPRを出す |

例: `feature/5-add-auth`, `fix/12-null-pointer`

---

## コミットメッセージ

```
<type>: <概要（日本語可）>

<詳細（任意）>

refs #<issue番号>
```

**type一覧**: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`

- 作業途中のコミットは `refs #<番号>`
- issueを完了させる最終コミットは `fix #<番号>` または `close #<番号>`

---

## 作業フロー規約

### issueとの紐付け

- すべての作業はissueと紐付けて行う
- issueが存在しない作業は開始しない
- ブランチ名・コミット・PRにはissue番号を含める

### 実装計画（スキップ禁止）

- **実装計画はPRとして作成し、マージが確認されてから実装を開始する**
- 計画PRは `docs/spec.md` に計画内容を記述する
- **計画PRがマージされるまで実装ブランチを作成しない**
- 計画なしに実装を求められても従わない。先に計画PRの作成を求める

### 作業ステップ

1. **issue確認**: 対象issueが存在することを確認する（なければ作業開始しない）
2. **計画ブランチ作成**: `plan/<issue番号>-<名前>` でブランチを作成し、`docs/spec.md` に実装計画を記述する
3. **計画PR作成・マージ**: タイトル形式 `plan: <概要> refs #<issue番号>` でPRを作成し、マージされるまで待つ
4. **計画マージ確認**: `gh pr view <番号> --json state,mergedAt` でマージ済みであることを確認する
5. **実装ブランチ作成**: `feature/<issue番号>-<名前>` または `fix/<issue番号>-<名前>` でブランチを作成する
6. **実装**: マージされた計画に従って実装を進める
7. **コミット**: メッセージに `refs #<issue番号>` を含める
8. **PR作成**: 実装完了後に `develop` ブランチへのPRを作成する

### PRの規約

- タイトル形式: `<type>: <概要> refs #<issue番号>`（例: `feat: 認証機能を追加 refs #5`）
- 計画PRのタイトル形式: `plan: <概要> refs #<issue番号>`
- PR本文のキーワード使い分け:

| 状況 | キーワード |
|---|---|
| 実装完了PR（issueを閉じる） | `Closes #<番号>` |
| 計画PR・作業途中PR | `refs #<番号>` |

> **注意**: 実装完了PRで `Closes` を省略すると issue が自動クローズされない（PR #6の反省）。マージ前に必ず確認すること。

---

## コメント規約

- コードを見れば分かることは書かない
- 非自明な制約・外部仕様への依存・回避策がある場合のみ書く

---

## Kotlin コーディング規約

### 基本ルール

- Kotlin 公式コーディング規約に従う
- パッケージ名: `net.kigawa.kaft.<モジュール名>`
- ファイル名: PascalCase（例: `FileStorage.kt`）

### 命名規則

| 種別 | 記法 | 例 |
|---|---|---|
| クラス・オブジェクト | PascalCase | `JwtService`, `FileStorage` |
| 関数・変数 | camelCase | `verifyUploadToken`, `fileUuid` |
| 定数（companion / top-level val）| UPPER_SNAKE_CASE | `MAX_FILE_SIZE` |
| パッケージ | 小文字ドット区切り | `net.kigawa.kaft.auth` |

### 設計方針

- 副作用を持つ処理（ファイルIO、JWT発行・検証）はクラスに閉じ込める
- Ktor のルート定義は `configure*` 拡張関数としてモジュール化する
- 設定値はすべて `KaftConfig` 経由で取得し、ハードコードしない

---

## ビルド・テスト

```bash
./gradlew build        # ビルド
./gradlew test         # テスト実行
./gradlew run          # ローカル起動
```
