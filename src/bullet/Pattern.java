package bullet;

/** 弾幕記述子（発射1回分のパラメータ） */
public class Pattern {
  public String type; public int count, arms; public double speed, spin, spread; public boolean aim;
  public double hue, size, accel, curve; public int interval; public double angle; public String sig; public int bulletKind;
  public int fired;   // 発射回数（壁の隙間スイープ等に使用）
}
