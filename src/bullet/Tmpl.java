package bullet;

/** 手で作り込んだ弾幕テンプレート（設計済み） */
public class Tmpl {
  public String type; public int count,arms; public double spd,spin,spreadDeg; public boolean aim; public int kind,interval,hueOff;
  public Tmpl(String t,int c,int a,double s,double sp,double spr,boolean ai,int k,int iv,int ho){
    type=t;count=c;arms=a;spd=s;spin=sp;spreadDeg=spr;aim=ai;kind=k;interval=iv;hueOff=ho;
  }
}
