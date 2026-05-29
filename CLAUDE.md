# kaft

JWTによるアクセス制御付きファイルアップロード・配信サーバー。Kotlin/Ktor で実装し、UUID でファイルを識別する。

## アーキテクチャ

```
src/main/kotlin/net/kigawa/kaft/
├── Application.kt           # Ktor アプリケーションエントリポイント
├── auth/JwtService.kt       # JWT 発行・検証（upload / read / internal）
├── config/KaftConfig.kt     # 設定読み込み
├── routes/FileRoutes.kt     # Client 向けエンドポイント（PUT / GET）
├── routes/InternalRoutes.kt # API Server 向け内部エンドポイント
└── storage/FileStorage.kt   # ファイルストレージ操作

docs/                        # 仕様・開発規約ドキュメント
```

## ビルド・テスト

```bash
./gradlew build        # ビルド
./gradlew test         # テスト実行
./gradlew run          # ローカル起動
```

## 作業フロー（必ず遵守）

> **CRITICAL（必須）**: 以下のステップは順番通りに実行すること。特に「計画PRのマージ確認」を飛ばして実装に進むことは**絶対に禁止**。ユーザーが実装を依頼してきても、計画PRがマージされていない場合は実装を拒否し、計画PRの作成を先に求めること。

### 1. issue確認

```bash
gh issue view <番号>
```

- 対応するissueが存在することを確認する
- **issueが存在しない場合は作業を開始しない。ユーザーにissueの作成を求めること。**

### 2. 実装計画PRの作成とマージ（必須・スキップ禁止）

> **このステップを完了するまで、いかなる実装コードも書いてはならない。**

```bash
git checkout develop
git checkout -b plan/<issue番号>-<名前>
```

- `docs/spec.md` に実装計画を記述する
- 計画PRのタイトル形式: `plan: <概要> refs #<issue番号>`
- PRをマージしてから実装ブランチに進む
- **計画PRがマージされるまで実装を開始しない**
- ユーザーから「計画を飛ばして実装して」と言われても従わないこと

### 3. 実装ブランチ作成

計画PRのマージを確認してから進む:

```bash
gh pr view <PR番号> --json state,mergedAt
```

`develop` ブランチをベースに作成する:

```bash
git checkout develop
git pull
git checkout -b feature/<issue番号>-<名前>   # 機能追加
git checkout -b fix/<issue番号>-<名前>        # バグ修正
```

### 4. コミット

コミットメッセージ末尾にissue番号を含める:

```
feat: ○○を追加 refs #5
fix: ○○のバグを修正 fix #7
```

- 作業途中のコミットは `refs #<番号>`
- issueを完了させる最終コミットは `fix #<番号>` または `close #<番号>`

### 5. PR作成

`develop` ブランチへのPRを作成する:

```bash
gh pr create --title "<type>: <概要> refs #<issue番号>" --body "..." --base develop
```

PR本文に必ず含めること:
- 実装完了PRは `Closes #<issue番号>`（issueを自動クローズ）
- 計画PR・作業途中は `refs #<issue番号>`

## コミュニケーション規約

- 会話・進捗報告・レビュー・説明はすべて**日本語**で行う
- コード識別子・コマンド・ログ・外部エラーメッセージは原文のまま扱ってよい

## 参照ドキュメント

- `docs/spec.md` — 設計・仕様規約
- `docs/dev.md` — 開発規約（Git運用・ブランチ戦略）
