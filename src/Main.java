/**
 * 灯火回廊 ― ASCENT エントリポイント。
 * 実処理は game.StellarCascade（JPanel本体）に委譲する。
 * 引数 "selftest" でヘッドレス自己テストを実行。
 */
public class Main {
  public static void main(String[] args){
    game.StellarCascade.main(args);
  }
}
