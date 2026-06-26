package bullet;

/** 敵弾（mode: 0通常 / 1停止→再加速 / 2誘導 / 3波(サイン) / 4重力、分裂対応） */
public class Bullet {
  public double x,y,angle,speed,accel,curve,r,hue; public int life,kind; public boolean grazed; public double turned;
  public int mode;
  public int delay; public double da,dsp;                 // mode1
  public double homTurn; public int homTime;              // mode2
  public double sineAmp,sineFreq,baseAngle,sx0,sy0,dist;  // mode3
  public double grav;                                     // mode4
  public int splitT,splitN,splitKind; public double splitSpd;   // 分裂(0=なし)
}
