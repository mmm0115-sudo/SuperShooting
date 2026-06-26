package entity;

/** ボス状態 */
public class Boss {
  public int idx; public String name; public double hue,size; public double x,y,ty;
  public int totalHp, hp, segHp; public Atk[] atks; public int atkIdx, atkT;
  public boolean spell; public String spellName=""; public boolean captured=true;
  public int timeLimit, declTimer, invuln; public double mtx,mty; public int moveT;
  public boolean entering=true, dead; public int deathTimer, t, hitFlash;
  public double spinAng, f1, f2; public int s1, s2;            // スクリプト用スクラッチ
  public boolean invincible; public int luxPhase, luxTimer;     // ルナ専用L1/L2
  public Atk cur(){ return atks[atkIdx]; }
}
