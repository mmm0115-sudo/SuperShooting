package util;

/** 難易度パラメータ */
public class Diff {
  public String name; public double bulletSpeed, density, fireMul, stageScale, aimErr; public int lives, bombs;
  public Diff(String n,double bs,double d,double f,double err,double ss,int l,int b){
    name=n;bulletSpeed=bs;density=d;fireMul=f;aimErr=err;stageScale=ss;lives=l;bombs=b;
  }
}
