# codex.MD

Codex 向けのプロジェクトガイド。日本語で応答・コメントしてよい。

## プロジェクト概要

**灯火回廊 ― ASCENT**（旧名 Stellar Cascade）：Java（Swing / Java2D）製の縦スクロール弾幕シューティング（全6層）。

| 項目 | 内容 |
|------|------|
| **JDK** | OpenJDK 25（Temurin 25.0.3）想定。Java 17 以降で動作 |
| **ビルド** | Maven/Gradle **なし**。`javac` + `jar` で手動ビルド |
| **依存** | **JDK 標準のみ**（外部 jar なし） |
| **配布** | mac: `StellarCascade.app` / Win: `灯火回廊.jar` + `灯火回廊.bat` |

## ソース構成

```text
src/
  Main.java                    … エントリ（game.StellarCascade.main に委譲）
  game/StellarCascade.java     … 本体。ゲームループ・更新・描画・定数・ボス脚本
  entity/                      … POJO（自機・敵・ボス・パーティクル等）
  bullet/                      … POJO（Bullet, Pattern, Tmpl, Laser 等）
  render/Colors.java           … 色ユーティリティ（hsb/hsba/clamp01）
  util/                        … Diff, StageInfo, Ev, StageRunner, Star, Sound
```

### 設計の要点

- **`StellarCascade` が God クラス**：更新・描画・状態遷移・静的データ（`DESIGNS` / `DIFFS` / `BOSSES` / `STAGE_INFO` 等）を保持。
- **`entity` / `bullet` / `util`（Sound 除く）はデータ中心**：public フィールドの POJO。ロジックは原則 `StellarCascade` 側。
- **ボス攻撃脚本**：`StellarCascade#runBossScript(Boss)` に集約。攻撃 ID は `AIM3`, `VORTEX`, `C_LANTERN` 等の定数。
- **弾幕生成**：`Tmpl[] DESIGNS` / `ENEMY_DESIGNS` → `fromTmpl` → `makeUniquePattern` / `makeEnemyPattern`。
- **記録保存**：`~/.tomoshibi_records`（`recFile()`）。
- **クロスプラットフォーム**：Java 本体に OS 依存なし。mac 専用 JVM フラグは `.app` / `.command` のみに置く。

## ビルド・検証・起動

```bash
# コンパイル（out/ へ全パッケージ）
javac -d out $(find src -name '*.java')

# ヘッドレス自己テスト（"TEST_OK ..." で正常終了）
java -Djava.awt.headless=true -cp out Main selftest

# jar 化 → .app に配置（Main-Class=Main）
jar cfe 灯火回廊.jar Main -C out .
cp 灯火回廊.jar StellarCascade.app/Contents/Resources/灯火回廊.jar
rm -f 灯火回廊.jar

# mac 起動
open StellarCascade.app
```

Windows ビルド例：

```bat
dir /s /b src\*.java > sources.txt && javac -d out @sources.txt
```

## ゲーム状態・主要メソッド（検索の起点）

| 探したいもの | 場所 |
|-------------|------|
| ゲーム状態 `state` | `"menu"`, `"play"`, `"paused"`, `"dialogue"`, `"gameover"` 等 |
| ボス脚本 | `runBossScript`, `bossStartAtk`, 攻撃定数 `AIM3`〜`C_LANTERN` |
| ステージ進行 | `stageIndex`, `StageRunner`, 中ボス・雑魚 spawn 系 |
| 描画 | `renderGame`, `draw*` 系 |
| 入力 | `KEYS`, `act()`, `actJust()` |
| 残響リワインド | `doRewind`, `snaps`, `echoUsed` |
| 自己テスト | `runSelfTest()` |
| フォント | `pickFont`, `JPM`, `JPG` |
| サウンド | `util/Sound.java`（自前シンセ BGM/SFX） |

## Codex 作業ルール

1. **最小読み取り**：`StellarCascade.java` 全文は読まず、`rg` でメソッド・定数を特定して必要箇所だけ読む。
2. **最小 diff**：依頼範囲外のリファクタ・整形・コメント追加はしない。
3. **新規クラス**：追加先のパッケージを明示する。データクラスは既存パッケージ、ゲームロジックは原則 `StellarCascade` に置く。
4. **コメント**：日本語 OK。自明な説明は不要。
5. **変更後は必ず検証する**：

   ```bash
   javac -d out $(find src -name '*.java')
   java -Djava.awt.headless=true -cp out Main selftest
   ```

6. **生成物をコミットしない**：`*.class`, `out/`, ルートの `灯火回廊.jar` はコミット対象外。
7. **jar は `.app` 内の1個に集約する**：`StellarCascade.app/Contents/Resources/灯火回廊.jar`。
8. **既存変更を尊重する**：作業ツリーにある依頼外の変更を上書き・破棄しない。

## よく触る変更パターン

### ボス弾幕・新攻撃を追加

1. 攻撃 ID 定数を追加する。
2. `BOSSES` の `Atk[]` に `N(...)` / `S(...)` で登録する。
3. `runBossScript` に攻撃 ID の分岐を追加する（`bs()`, `ivl()`, `dcnt()`, `firePattern` 等を使用）。

### 難易度・弾幕密度

- `DIFFS` 配列（弾速 `bulletSpeed`, 密度 `density`, 間隔 `fireMul`）
- 補助：`bs()`, `ivl()`, `dcnt()`, `pf()`

### サウンド

- `util/Sound.java` の `tone` / `noise` / `startBGM`
- ゲーム側から `sound.shot()` 等を呼ぶ

### UI・メニュー

- `MODES`, `drawMenu`, `updateMenu` 付近
- 解像度定数：`W=720`, `H=960`, `SIDEW=280`, `VW=1000`

## 関連ドキュメント

- 共通の作業指示：`AGENTS.md`
- プレイヤー向け：`README.md`
