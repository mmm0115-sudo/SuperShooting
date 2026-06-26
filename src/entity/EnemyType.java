package entity;

import bullet.Pattern;

/** 敵タイプ定義 */
public class EnemyType {
  public String shape; public double hue, size; public int hp; public String move;
  public double moveSpeed, amp, freq; public int fireCd, score, tier; public long detailSeed;
  public Pattern[] patterns; public String sig;
}
