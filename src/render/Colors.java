package render;

import java.awt.Color;

/** 描画用カラーユーティリティ（HSL風指定で色を作る） */
public class Colors {
  public static Color hsb(double h,double s,double b){
    float hh=(float)((((h%360)+360)%360)/360.0);
    return Color.getHSBColor(hh,(float)clamp01(s),(float)clamp01(b));
  }
  public static Color hsba(double h,double s,double b,int a){
    Color c=hsb(h,s,b); return new Color(c.getRed(),c.getGreen(),c.getBlue(),Math.max(0,Math.min(255,a)));
  }
  public static double clamp01(double v){ return v<0?0:v>1?1:v; }
}
