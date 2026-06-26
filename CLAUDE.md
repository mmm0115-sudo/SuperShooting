# CLAUDE.md

このリポジトリで作業する際のガイドです。日本語でOK。

## プロジェクト概要
**灯火回廊 ― ASCENT**：Java（Swing / Java2D）製の縦スクロール弾幕シューティング（全6層）。
外部ライブラリ・ビルドツールなしの**単一ソースファイル**プロジェクト。

## 環境・ビルド

| 項目 | 内容 |
|------|------|
| **JDK** | OpenJDK 25（Temurin 25.0.3）／`javac 25.0.3`。Java 17 以降で動作想定 |
| **ビルドツール** | **なし**（Maven/Gradle 不使用。`pom.xml`・`build.gradle` は存在しない。`javac` + `jar` で手動ビルド） |
| **依存ライブラリ** | **JDK標準のみ**：`javax.swing`・`java.awt`(geom/event/image)・`javax.sound.sampled`・`java.io`・`java.util`。外部jar依存なし |
| **パッケージ** | `Main`（デフォルト）＋ `game` / `entity` / `bullet` / `render` / `util` |

### ビルド・実行コマンド
```bash
# コンパイル（全パッケージをまとめて out/ へ）
javac -d out $(find src -name '*.java')

# 動作検証（ウィンドウを開かずロジック＋描画を検証。"TEST_OK ..." が出れば正常）
java -Djava.awt.headless=true -cp out Main selftest

# jar 化 → .app に配置（Main-Class=Main）
jar cfe 灯火回廊.jar Main -C out .
cp 灯火回廊.jar StellarCascade.app/Contents/Resources/灯火回廊.jar
rm -f 灯火回廊.jar          # ルートは生成物（.gitignore済み）。jarは .app 内の1個に集約

# 起動
open StellarCascade.app    # mac（または 灯火回廊を起動.command）
# Windows: 灯火回廊.jar と 灯火回廊.bat をコピーして .bat をダブルクリック（または java -jar 灯火回廊.jar）
```

## ソース構成（`src/` 配下のパッケージ）

```
src/
  Main.java                  … エントリポイント（game.StellarCascade.main に委譲）
  game/StellarCascade.java   … 本体（JPanel）。ゲームループ・状態遷移・更新・描画・定数・全ロジック（約2,200行）
  entity/                    … 自機・敵・ボス系のデータ保持クラス
    PChar Enemy EnemyType Boss BossInfo Atk Item Particle Floater Snapshot
  bullet/                    … 弾・弾幕パターンのデータ保持クラス
    Bullet PBullet Pattern Tmpl Laser
  render/                    … 描画ユーティリティ
    Colors（hsb/hsba/clamp01。game では import static で利用）
  util/                      … 定数的データ・ユーティリティ
    Diff StageInfo Ev StageRunner Star Sound（自前シンセSFX/BGM）
```

### エントリポイント
- **`Main.main`（`src/Main.java`、デフォルトパッケージ）** → `game.StellarCascade.main` を呼ぶだけ。
- jar の `Main-Class: Main`。引数 `selftest` でヘッドレス自己テスト。
- 実体のゲームループ・`main`/`runSelfTest` は `game.StellarCascade`（public）。

### 設計メモ
- `game.StellarCascade` は依然として大きな統括クラス（God クラス）で、**更新・描画ロジックと定数・静的データ配列（DESIGNS / DIFFS / BOSSES / STAGE_INFO 等）を保持**。
  これらは `entity` / `bullet` / `util` の **public データクラス（public フィールド）** を参照する。
- `entity` / `bullet` / `util` の各クラスは**データ保持のみ**（ロジックを持たない POJO）。`Sound` のみ util にある機能クラス。
- ボスの全攻撃スクリプトは `game.StellarCascade#runBossScript` に集約。
- **クロスプラットフォーム**：コードは純Java（OS依存処理なし）。mac専用フラグ（`-Xdock:name` / `-Dsun.java2d.metal`）は **`.command`・`.app` のみ**に置き、Windows用 `灯火回廊.bat`（`javaw -jar`）や `java -jar` には付けない。日本語フォントは `pickFont` で mac/Win/Linux を順に探索し、無ければ論理フォントへフォールバック。

## 作業ルール
- **変更するファイル（＝実質 `灯火回廊.java`）の必要箇所のみ提示する。全ファイル・全行は読まない**。
  該当メソッド／セクションを `grep` で特定してから最小限を読む。
- **新規クラスを追加する場合はパッケージを明示する**（本プロジェクトはデフォルトパッケージ。
  原則 `StellarCascade` 内の `static` ネストクラスとして追加し、その旨を記載する）。
- **コメントは日本語でOK**（既存コードも日本語コメント）。
- 変更後は必ず `javac 灯火回廊.java` でコンパイルし、`StellarCascade selftest` で例外ゼロを確認する。
- ビルド生成物（`*.class`・ルートの `灯火回廊.jar`）は `.gitignore` 済み。コミットしない。
- jar は **`.app` 内の1個に集約**（重複を作らない）。
