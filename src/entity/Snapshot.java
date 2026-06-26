package entity;

import java.util.ArrayList;
import bullet.Bullet;
import bullet.Laser;

/** 残響リワインド用：状態のディープコピー保持 */
public class Snapshot {
  public double px,py; public int pInvuln,pBombTimer,pShotCd,pDeathTimer; public boolean pDead;
  public int lives,bombs,power,score,grazeCount,stageTimer;
  public ArrayList<Bullet> eb=new ArrayList<>(); public ArrayList<Enemy> en=new ArrayList<>();
  public ArrayList<Item> it=new ArrayList<>(); public ArrayList<Laser> lz=new ArrayList<>(); public Boss boss;
}
