package entity;

/** ボスの1攻撃定義（通常↔スペル交互。通常は名前非表示） */
public class Atk {
  public boolean spell; public String name; public int script; public double sec;
  public Atk(boolean sp,String n,int sc,double s){spell=sp;name=n;script=sc;sec=s;}
}
