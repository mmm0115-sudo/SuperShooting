package entity;

/** ボス定義（攻撃列を持つ） */
public class BossInfo {
  public String name; public double hue,size; public int hp; public Atk[] atks;
  public BossInfo(String n,double hu,double sz,int hp,Atk[] a){name=n;hue=hu;size=sz;this.hp=hp;atks=a;}
}
