package entity;

/** 敵インスタンス */
public class Enemy {
  public EnemyType g; public double x,y,sx,sy; public int t; public int hp,maxhp; public double fireT; public int patIdx;
  public double targetY; public int hitFlash; public boolean dead; public int dir; public int fireBudget=999;  // 残り発射回数
  public boolean drone; public double ox, oy;   // ボス随伴ドローン（ボス位置＋オフセットで移動）
}
