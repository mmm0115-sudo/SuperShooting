/* =======================================================================
   STELLAR CASCADE  —  6面制 弾幕シューティング（Java / Swing + Java2D）
   - メニュー → 6ステージ → 各ボス → エンディング
   - 敵・弾幕はプロシージャル生成 + シグネチャ照合で「二度と同じものが出ない」
   - 難易度 EASY / NORMAL / HARD
   コンパイル: javac StellarCascade.java
   実行:       java StellarCascade
   自己テスト: java StellarCascade selftest
   ======================================================================= */
import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.sound.sampled.*;
import java.io.*;

public class StellarCascade extends JPanel implements ActionListener, KeyListener {
  static final int W = 720, H = 960;       // プレイフィールド
  static final int SIDEW = 280;            // 右サイドバー幅
  static final int VW = W + SIDEW;          // 仮想キャンバス全幅（1000）
  static final int CX = VW/2;               // 全画面メニュー類の中心

  /* ---------------- 入力 ---------------- */
  boolean[] down = new boolean[300];
  boolean[] just = new boolean[300];
  static final Map<String,int[]> KEYS = new HashMap<>();
  static {
    KEYS.put("up",    new int[]{KeyEvent.VK_UP, KeyEvent.VK_W});
    KEYS.put("down",  new int[]{KeyEvent.VK_DOWN, KeyEvent.VK_S});
    KEYS.put("left",  new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_A});
    KEYS.put("right", new int[]{KeyEvent.VK_RIGHT, KeyEvent.VK_D});
    KEYS.put("shot",  new int[]{KeyEvent.VK_Z, KeyEvent.VK_SPACE, KeyEvent.VK_J});
    KEYS.put("bomb",  new int[]{KeyEvent.VK_X, KeyEvent.VK_K});
    KEYS.put("focus", new int[]{KeyEvent.VK_SHIFT});
    KEYS.put("confirm", new int[]{KeyEvent.VK_ENTER, KeyEvent.VK_Z});
    KEYS.put("pause", new int[]{KeyEvent.VK_P, KeyEvent.VK_ESCAPE});
    KEYS.put("mute",  new int[]{KeyEvent.VK_M});
    KEYS.put("quit",  new int[]{KeyEvent.VK_Q});
    KEYS.put("ldiff", new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_A});
    KEYS.put("rdiff", new int[]{KeyEvent.VK_RIGHT, KeyEvent.VK_D});
  }
  boolean act(String a){ for(int c:KEYS.get(a)) if(c<down.length && down[c]) return true; return false; }
  boolean actJust(String a){ for(int c:KEYS.get(a)) if(c<just.length && just[c]){ just[c]=false; return true; } return false; }
  void clearJust(){ Arrays.fill(just,false); }

  /* ---------------- 乱数 & ユニーク生成 ---------------- */
  Random rng = new Random();
  HashSet<String> usedPatternSigs = new HashSet<>();
  HashSet<String> usedEnemySigs   = new HashSet<>();
  double rr(double a,double b){ return a + rng.nextDouble()*(b-a); }
  int ir(int a,int b){ return a + rng.nextInt(b-a+1); }
  double q(double v,double step){ return Math.round(v/step)*step; }
  <T> T pick(T[] arr){ return arr[rng.nextInt(arr.length)]; }

  static final String[] PATTERN_TYPES = {"ring","spiral","fan","spray","wall","flower","aimedBurst","cross","arc","rain"};
  static final String[] ENEMY_SHAPES  = {"diamond","triangle","hex","arrow","orb","crab","star","wing"};

  /* ---------------- 難易度 ---------------- */
  static class Diff {
    String name; double bulletSpeed, density, fireMul, stageScale; int lives, bombs; boolean stackVolley;
    Diff(String n,double bs,double d,double f,double ss,int l,int b,boolean sv){
      name=n;bulletSpeed=bs;density=d;fireMul=f;stageScale=ss;lives=l;bombs=b;stackVolley=sv;
    }
  }
  static final Diff[] DIFFS = {
    new Diff("EASY",   0.58, 0.42, 2.0, 0.05, 6, 5, false),
    new Diff("NORMAL", 0.74, 0.55, 1.6, 0.08, 5, 4, false),
    new Diff("HARD",   0.92, 0.72, 1.15,0.11, 3, 3, true),
  };
  int diffIdx = 0;
  Diff diff(){ return DIFFS[diffIdx]; }

  /* ---------------- 機体・ショット選択 ---------------- */
  static final double POC_LINE = 260;     // この高さより上に行くと全アイテム自動回収
  double curStageHpMul = 1;
  static class PChar { String name,desc; double speed,focus,hitr,dmgMul,hue;
    PChar(String n,String d,double s,double f,double h,double dm,double hu){name=n;desc=d;speed=s;focus=f;hitr=h;dmgMul=dm;hue=hu;} }
  static final PChar[] CHARS = {
    new PChar("VANGUARD","標準・バランス型",      5.0, 2.1, 3.6, 1.00, 205),
    new PChar("ZEPHYR",  "高速・小さい当たり判定",6.2, 2.7, 3.0, 0.85, 165),
    new PChar("AEGIS",   "低速・高火力",          4.2, 1.8, 4.2, 1.30,  28),
  };
  static final String[] SHOT_NAMES = {"WIDE  拡散","FORWARD  集束","HOMING  誘導"};
  static final String[] SHOT_DESC  = {"広範囲をカバー","正面に集中・高火力","弾が敵を自動追尾"};
  int charSel=0, shotSel=0, selRow=0;

  /* ---------------- エンティティ ---------------- */
  static class Pattern {
    String type; int count, arms; double speed, spin, spread; boolean aim;
    double hue, size, accel, curve; int interval; double angle; String sig; int bulletKind;
  }
  static class EnemyType {
    String shape; double hue, size; int hp; String move;
    double moveSpeed, amp, freq; int fireCd, score, tier; long detailSeed;
    Pattern[] patterns; String sig;
  }
  static class Enemy {
    EnemyType g; double x,y,sx,sy; int t; int hp,maxhp; double fireT; int patIdx;
    double targetY; int hitFlash; boolean dead; int dir;
  }
  static class Bullet { double x,y,angle,speed,accel,curve,r,hue; int life,kind; boolean grazed; double turned; }
  static class PBullet { double x,y,vx,vy,r; int dmg; boolean homing; }
  static class Item { double x,y,vx,vy,r; int t; String type; boolean dead; }
  static class Particle { double x,y,vx,vy,life,decay,r,hue; boolean star; }
  static class Floater { double x,y,life,hue; String txt; }
  static class Boss {
    int defIdx; String name; double hue; double x,y,ty,r; int maxhp,hp,phaseHpMax;
    boolean entering; int t,hitFlash,phase,phaseCount,attackIdx,attackTimer,attackDur;
    List<Pattern[]> descs = new ArrayList<>(); int cooldown,moveT; boolean dead; int deathTimer;
    double mtx,mty; int invuln;
    // スペルカード
    String spellName=""; int spellTimer,spellMax,declTimer; boolean captured=true,isSpell;
  }

  /* ---------------- ボス定義 ---------------- */
  static class BossDef { String name; double hue; int hp; double size; String[][] types;
    BossDef(String n,double h,int hp,double s,String[][] t){name=n;hue=h;this.hp=hp;size=s;types=t;} }
  static final BossDef[] BOSS_DEFS = {
    new BossDef("蒼穹の哨戒機 AURORA",200,2200,46,new String[][]{{"fan","ring"},{"spiral","arc"},{"ring","flower"}}),
    new BossDef("紅蓮の双角 SCARLET",0,2900,50,new String[][]{{"ring","spiral"},{"fan","aimedBurst"},{"flower","cross"},{"spiral","rain"}}),
    new BossDef("翠嵐の蟲将 VERDANT",120,3600,54,new String[][]{{"spray","wall"},{"cross","ring"},{"arc","spiral"},{"flower","fan"}}),
    new BossDef("黄昏の機神 AMBER",42,4400,58,new String[][]{{"spiral","cross"},{"ring","flower"},{"wall","rain"},{"fan","arc"},{"ring","spiral"}}),
    new BossDef("深淵の咎竜 ABYSS",280,5400,62,new String[][]{{"flower","spiral"},{"cross","ring"},{"rain","wall"},{"arc","fan"},{"spiral","flower"},{"ring","cross"}}),
    new BossDef("天穹の終焉 STELLAR",320,7200,70,new String[][]{{"ring","spiral"},{"flower","cross"},{"fan","arc"},{"wall","rain"},{"spiral","flower"},{"cross","ring"},{"arc","spiral"},{"flower","fan"}}),
  };

  /* ---------------- ステージ情報 ---------------- */
  static class StageInfo { String name,sub; double bg; StageInfo(String n,String s,double b){name=n;sub=s;bg=b;} }
  static final StageInfo[] STAGE_INFO = {
    new StageInfo("STAGE 1","蒼天の境界",200),
    new StageInfo("STAGE 2","紅の砂嵐",10),
    new StageInfo("STAGE 3","翠玉の渓谷",130),
    new StageInfo("STAGE 4","黄昏の機巧都市",42),
    new StageInfo("STAGE 5","深淵の回廊",275),
    new StageInfo("STAGE 6","天穹の果て",315),
  };

  /* ---------------- ゲーム状態 ---------------- */
  String state = "menu";
  List<Bullet> enemyBullets = new ArrayList<>();
  List<PBullet> playerBullets = new ArrayList<>();
  List<Enemy> enemies = new ArrayList<>();
  List<Item> items = new ArrayList<>();
  List<Particle> particles = new ArrayList<>();
  List<Floater> floaters = new ArrayList<>();
  Boss boss;
  // プレイヤー
  double px,py,pr=3.6; int pShotCd,pInvuln,pBombTimer,pDeathTimer; boolean pDead;
  int stageIndex, score, hiscore, lives, bombs, power, grazeCount;
  int frame, stageTimer, menuSel, transTimer, bossWarn;
  double stageDiff = 1, shake, flash;
  // ステージ進行
  static class Ev { int t; Runnable fn; Ev(int t,Runnable f){this.t=t;fn=f;} }
  static class StageRunner { List<Ev> events; int idx; int finalTime; }
  StageRunner stageRunner;
  List<Ev> delayed = new ArrayList<>();

  Sound sound = new Sound();

  /* ====================================================================
     起動
     ==================================================================== */
  public StellarCascade(){
    setPreferredSize(new Dimension(750,720));   // VW:H ≈ 1000:960
    setBackground(Color.BLACK);
    setFocusable(true);
    addKeyListener(this);
    hiscore = loadHiscore();
    initStars();
  }
  javax.swing.Timer timer;
  long lastNano = 0;
  double accNano = 0;
  static final double STEP_NANO = 1_000_000_000.0/60.0;   // 1更新 = 1/60秒（実時間基準）
  void start(){
    sound.init();
    timer = new javax.swing.Timer(7, this);   // 高頻度で起こし、実時間で更新回数を決める
    timer.start();
  }
  // 固定タイムステップ：FPSが落ちても更新回数で速度を一定に保つ（処理落ち＝減速 を防ぐ）
  public void actionPerformed(ActionEvent e){
    long now = System.nanoTime();
    if(lastNano==0){ lastNano=now; return; }
    accNano += (now - lastNano); lastNano = now;
    if(accNano > STEP_NANO*5) accNano = STEP_NANO*5;        // 大きな遅延はクランプ（巻き戻り防止）
    int steps=0;
    while(accNano >= STEP_NANO && steps<5){
      try { update(); } catch(Throwable t){ t.printStackTrace(); }
      accNano -= STEP_NANO; steps++;
    }
    if(steps>0) repaint();
  }

  /* ====================================================================
     ユニーク生成
     ==================================================================== */
  Pattern makeUniquePattern(String biasType, double hue, double power, double aimBias){
    Diff d = diff();
    for(int attempt=0; attempt<80; attempt++){
      Pattern p = new Pattern();
      p.type = (biasType!=null)? biasType : pick(PATTERN_TYPES);
      int rawCount = ir(5, (int)Math.round(7 + 13*power));   // 弾数を削減
      p.count = Math.max(3, (int)Math.round(rawCount * d.density));
      p.arms = ir(1, 2 + (int)Math.round(2*power));
      p.speed = rr(1.4, 1.8 + 1.5*power);                    // 弾速（避けやすさ重視で控えめ）
      p.spin = rr(-0.10, 0.10);
      p.spread = rr(Math.PI*0.18, Math.PI*0.85);
      p.aim = rng.nextDouble() < aimBias;
      p.hue = (hue<0)? ir(0,359) : ((hue + ir(-30,30))%360+360)%360;
      p.size = q(rr(5.0,8.0),0.5);
      p.accel = rr(0.0, 0.03) * power;                       // 減速なし（止まる弾を排除）
      p.curve = (rng.nextDouble()<0.22)? rr(-0.025,0.025) : 0;
      p.interval = (int)Math.round(ir(8,18) * d.fireMul);
      p.angle = rr(0,Math.PI*2);
      if(p.type.equals("fan")||p.type.equals("aimedBurst")||p.type.equals("rain")) p.aim=true;
      if(p.type.equals("wall")){ p.count=ir(8,14); p.aim=false; }
      p.bulletKind = (p.size>=7)? 2 : (rng.nextDouble()<0.40?1:0);   // 0=玉 1=米弾 2=大玉
      String sig = String.join("|", p.type, ""+p.count, ""+p.arms, ""+p.bulletKind,
        ""+q(p.speed,0.25), ""+q(p.spin,0.02), ""+q(p.spread,0.15),
        p.aim?"1":"0", ""+q(p.hue,15), ""+p.size, ""+q(p.accel,0.01), ""+q(p.curve,0.01));
      if(!usedPatternSigs.contains(sig)){ usedPatternSigs.add(sig); p.sig=sig; return p; }
    }
    Pattern p = new Pattern(); p.type="ring"; p.count=ir(8,20); p.arms=1; p.speed=2;
    p.spin=rr(-.1,.1); p.spread=Math.PI; p.aim=false; p.hue=ir(0,359); p.size=6;
    p.accel=0; p.curve=0; p.interval=12; p.angle=rr(0,7);
    p.sig="fb"+usedPatternSigs.size(); usedPatternSigs.add(p.sig); return p;
  }

  EnemyType makeUniqueEnemyType(double hue, int tier, String moveBias, String shapeBias){
    EnemyType g=null;
    for(int attempt=0; attempt<80; attempt++){
      EnemyType e = new EnemyType();
      e.shape = (shapeBias!=null)? shapeBias : pick(ENEMY_SHAPES);
      e.hue = (hue<0)? ir(0,359) : ((hue+ir(-25,25))%360+360)%360;
      e.size = q(tier==1? rr(14,20): tier==2? rr(22,30): rr(34,46), 1);
      e.hp = (int)Math.round((tier==1? rr(3,6): tier==2? rr(16,30): rr(50,90)) * curStageHpMul);
      String[] moves = {"straight","sine","swoop","arc","hover","drift","dart"};
      e.move = (moveBias!=null)? moveBias : moves[rng.nextInt(moves.length)];
      e.moveSpeed = q(rr(1.2,3.0),0.1);
      e.amp = q(rr(30,140),5);
      e.freq = q(rr(0.01,0.05),0.005);
      e.detailSeed = rng.nextLong();
      e.fireCd = (int)Math.round(ir(75,155) * diff().fireMul);
      e.score = tier*100;
      e.tier = tier;
      String sig = String.join("|", e.shape, ""+q(e.hue,12), ""+e.size, ""+e.hp,
        e.move, ""+q(e.moveSpeed,0.2), ""+tier);
      if(!usedEnemySigs.contains(sig)){ usedEnemySigs.add(sig); e.sig=sig; g=e; break; }
    }
    if(g==null){ g=new EnemyType(); g.shape="orb"; g.hue=ir(0,359); g.size=18; g.hp=4;
      g.move="straight"; g.moveSpeed=2; g.amp=60; g.freq=0.03; g.detailSeed=rng.nextLong();
      g.fireCd=80; g.score=100; g.tier=tier; g.sig="ef"+usedEnemySigs.size(); }
    int pc = g.tier==1?1:(g.tier==2?(rng.nextBoolean()?1:2):2);
    g.patterns = new Pattern[pc];
    for(int i=0;i<pc;i++) g.patterns[i] = makeUniquePattern(null, g.hue, 0.28 + g.tier*0.18, g.tier==1?0.55:0.45);
    return g;
  }

  EnemyType variantOf(EnemyType g, int i){
    EnemyType v = new EnemyType();
    v.shape=g.shape; v.move=g.move; v.tier=g.tier; v.score=g.score; v.fireCd=g.fireCd;
    v.hue=((g.hue + ir(-14,14))%360+360)%360;
    v.size=g.size + ir(-2,2);
    v.hp=g.hp;
    v.detailSeed = g.detailSeed ^ (i*0x9E3779B1L);
    v.moveSpeed=g.moveSpeed; v.amp=g.amp + ir(-15,15);
    v.freq=q(g.freq + rr(-0.006,0.006),0.001);
    v.patterns = new Pattern[g.patterns.length];
    for(int k=0;k<v.patterns.length;k++) v.patterns[k]=makeUniquePattern(null, v.hue, 0.28+g.tier*0.18, 0.5);
    v.sig=g.sig+"#"+i;
    return v;
  }

  /* ====================================================================
     弾の生成 / 発射
     ==================================================================== */
  void spawnEB(double x,double y,double angle,Pattern p,double spMul,double szMul){
    if(enemyBullets.size()>2000) return;
    Bullet b=new Bullet();
    b.x=x; b.y=y; b.angle=angle;
    b.speed=p.speed*spMul*stageDiff*diff().bulletSpeed;
    b.accel=p.accel; b.curve=p.curve; b.r=p.size*szMul; b.hue=p.hue; b.kind=p.bulletKind;
    enemyBullets.add(b);
  }
  void spawnEBraw(double x,double y,double angle,double speed,double accel,double r,double hue,int kind){
    if(enemyBullets.size()>2000) return;
    Bullet b=new Bullet(); b.x=x;b.y=y;b.angle=angle;
    b.speed=speed*stageDiff*diff().bulletSpeed; b.accel=accel; b.curve=0; b.r=r; b.hue=hue; b.kind=kind;
    enemyBullets.add(b);
  }
  void firePattern(double x,double y,Pattern p){
    double aim = Math.atan2((pDead?y+100:py)-y, (pDead?x:px)-x);
    double downA = Math.PI/2;
    switch(p.type){
      case "ring": { double step=Math.PI*2/p.count, base=p.angle+(p.aim?aim:0);
        for(int i=0;i<p.count;i++) spawnEB(x,y,base+i*step,p,1,1); break; }
      case "spiral": { for(int a=0;a<p.arms;a++) spawnEB(x,y,p.angle + a*(Math.PI*2/p.arms),p,1,1); break; }
      case "cross": { for(int a=0;a<p.arms;a++){ double base=p.angle+a*(Math.PI*2/p.arms);
        for(int j=-1;j<=1;j++) spawnEB(x,y,base+j*0.18,p,1,1);} break; }
      case "fan": { double base=aim-p.spread/2; double st=p.count>1?p.spread/(p.count-1):0;
        for(int i=0;i<p.count;i++) spawnEB(x,y,base+st*i,p,1,1); break; }
      case "flower": { double step=Math.PI*2/p.count, base=p.angle+(p.aim?aim:0);
        for(int i=0;i<p.count;i++) spawnEB(x,y,base+i*step,p,(i%2==1)?1.5:0.85,1); break; }
      case "spray": { int n=Math.max(3,(int)Math.round(p.count*0.4));
        for(int i=0;i<n;i++) spawnEB(x,y,aim+(rng.nextDouble()-0.5)*p.spread,p,0.7+rng.nextDouble()*0.7,1); break; }
      case "wall": { double span=W*0.9; int n=p.count, gap=ir(0,n-1);
        for(int i=0;i<n;i++){ if(Math.abs(i-gap)<=1) continue; double bx=W*0.05+span*((double)i/(n-1));
          spawnEBraw(bx,y,downA,p.speed*0.9,0.02,p.size,p.hue,p.bulletKind);} break; }
      case "rain": { int n=Math.max(2,(int)Math.round(p.count*0.25));
        for(int i=0;i<n;i++){ double bx=rng.nextDouble()*W;
          spawnEBraw(bx,-10,downA+rr(-0.3,0.3),p.speed,0.01,p.size,p.hue,p.bulletKind);} break; }
      case "aimedBurst": { int n=Math.max(3,(int)Math.round(p.count*0.3));
        for(int i=0;i<n;i++) spawnEB(x,y,aim+rr(-0.12,0.12),p,1.4,1); break; }
      case "arc": { double base=aim-p.spread/2; double st=p.count>1?p.spread/(p.count-1):0;
        for(int i=0;i<p.count;i++){ Bullet b=new Bullet(); b.x=x;b.y=y;b.angle=base+st*i;
          b.speed=p.speed*stageDiff*diff().bulletSpeed; b.accel=p.accel;
          b.curve=(i-p.count/2.0)*0.004; b.r=p.size; b.hue=p.hue; b.kind=p.bulletKind;
          if(enemyBullets.size()<2000) enemyBullets.add(b);} break; }
    }
    p.angle += p.spin;
  }

  /* ====================================================================
     敵
     ==================================================================== */
  void spawnEnemy(EnemyType g,double x,double y,double targetY,int dir){
    Enemy e=new Enemy(); e.g=g; e.x=x; e.y=y; e.sx=x; e.sy=y;
    e.hp=g.hp; e.maxhp=g.hp; e.fireT=g.fireCd*0.5+rng.nextDouble()*g.fireCd;
    e.targetY = targetY<0 ? (40+rng.nextDouble()*180) : targetY;
    e.dir = dir!=0?dir:(x<W/2?1:-1);
    enemies.add(e);
  }
  void updateEnemy(Enemy e){
    e.t++; EnemyType g=e.g; double sp=g.moveSpeed;
    switch(g.move){
      case "straight": e.y+=sp; break;
      case "sine": e.y+=sp*0.9; e.x=e.sx+Math.sin(e.t*g.freq)*g.amp; break;
      case "swoop": if(e.y<e.targetY) e.y+=sp*1.6; else e.x+=Math.cos(e.t*g.freq)*sp*1.4*e.dir; break;
      case "arc": e.x+=Math.cos(e.t*0.02)*sp*e.dir; e.y+=Math.sin(e.t*0.02)*sp*0.6+0.3; break;
      case "hover": if(e.y<e.targetY) e.y+=sp; else e.x=e.sx+Math.sin(e.t*g.freq)*g.amp; break;
      case "drift": e.y+=sp*0.5; e.x+=Math.sin(e.t*g.freq)*1.5*e.dir; break;
      case "dart": if(e.y<e.targetY) e.y+=sp*2.2; else e.x+=sp*1.8*e.dir; break;
    }
    if(e.hitFlash>0) e.hitFlash--;
    if(e.y>0 && e.y<H*0.8){
      e.fireT--;
      if(e.fireT<=0){
        Pattern p=g.patterns[e.patIdx % g.patterns.length];
        firePattern(e.x,e.y,p); e.patIdx++;
        e.fireT = p.interval * ir(2,5);
      }
    }
    if(e.y>H+60 || e.x<-80 || e.x>W+80) e.dead=true;
  }

  /* ====================================================================
     ボス
     ==================================================================== */
  // スペルカード名生成
  static final String[] SPELL_SIGNS = {"蒼符","緋符","翠符","琥珀符","闇符","星符"};
  static final String[] SPELL_NAMES = {
    "夢幻泡影","星屑の奔流","紅蓮乱舞","螺旋回廊","蒼天円舞","無限連鎖","花鳥風月","終焉のワルツ",
    "彗星雨","幻想閃光","数珠繋ぎ","八方封陣","天網恢恢","千々の刃","虚空の檻","月下美人",
    "逆さ落とし","万華鏡","渦動旋律","静寂の波紋","白夜行","黒点爆発","流転輪廻","極彩の宴"
  };
  HashSet<String> usedSpellNames = new HashSet<>();
  String makeSpellName(Boss b){
    String sign = SPELL_SIGNS[b.defIdx % SPELL_SIGNS.length];
    for(int i=0;i<50;i++){
      String full = sign+"『"+SPELL_NAMES[rng.nextInt(SPELL_NAMES.length)]+"』";
      if(!usedSpellNames.contains(full)){ usedSpellNames.add(full); return full; }
    }
    String full = sign+"『"+SPELL_NAMES[rng.nextInt(SPELL_NAMES.length)]+" "+usedSpellNames.size()+"』";
    usedSpellNames.add(full); return full;
  }

  Boss makeBoss(int idx){
    BossDef def=BOSS_DEFS[idx];
    double hpMul = 1 + idx*diff().stageScale + diffIdx*0.18;
    Boss b=new Boss();
    b.defIdx=idx; b.name=def.name; b.hue=def.hue; b.x=W/2; b.y=-80; b.ty=170;
    b.r=def.size; b.phaseCount=def.types.length;
    int total=(int)Math.round(def.hp*5*hpMul);
    b.maxhp=total; b.phaseHpMax=Math.max(1, total/b.phaseCount); b.hp=b.phaseHpMax;
    b.entering=true; b.cooldown=15; b.attackDur=240; b.spellName="";
    b.mtx=W/2; b.mty=160; b.invuln=90;
    for(int p=0;p<def.types.length;p++){
      Pattern[] set=new Pattern[def.types[p].length];
      for(int i=0;i<set.length;i++)
        set[i]=makeUniquePattern(def.types[p][i], def.hue, 0.85+idx*0.10+p*0.05, 0.4);
      b.descs.add(set);
    }
    bossStartAttack(b);
    return b;
  }
  void bossStartAttack(Boss b){
    b.attackIdx = b.attackIdx % b.descs.get(b.phase).length;
    b.attackTimer=0;
    b.attackDur = (int)Math.round((220+rng.nextInt(120)) * (0.6 + 0.4*diff().fireMul/1.9));
    b.mtx = 120 + rng.nextDouble()*(W-240);
    b.mty = 110 + rng.nextDouble()*120;
  }
  void bossBeginSpell(Boss b){          // フェーズ＝スペルカード開始（宣言演出）
    b.isSpell=true; b.spellName=makeSpellName(b);
    b.spellMax = 60*(20 + b.phase*2 + b.defIdx);   // 制限時間（フレーム）
    b.spellTimer=b.spellMax; b.captured=true; b.declTimer=80;
    b.cooldown=20; b.attackIdx=0; bossStartAttack(b);
    sound.spellDeclare();
  }
  void awardSpell(Boss b){
    int bonus = 30000 + 12000*b.phase + b.spellTimer*15 + b.defIdx*5000;
    score += bonus; floatText(b.x,b.y,"SPELL CARD GET!  +"+bonus, 50); sound.spellGet();
    flash=0.6;
  }
  void bossAdvancePhase(Boss b, boolean captured){
    if(b.isSpell && captured) awardSpell(b);
    bulletCancelToStars(); shake=12;
    b.phase++; b.invuln=50; b.attackTimer=0; b.attackDur=240;
    b.hp=b.phaseHpMax;
    bossBeginSpell(b);
  }
  void updateBoss(Boss b){
    b.t++; if(b.hitFlash>0) b.hitFlash--;
    if(b.entering){
      b.y += (b.ty-b.y)*0.04;
      if(Math.abs(b.y-b.ty)<2){ b.entering=false; b.y=b.ty; bossBeginSpell(b); }
      return;
    }
    if(b.dead){ b.deathTimer++; return; }
    if(b.invuln>0) b.invuln--;
    b.x += (b.mtx-b.x)*0.02; b.y += (b.mty-b.y)*0.02; b.moveT++;
    if(b.declTimer>0){ b.declTimer--; return; }    // 宣言中は移動のみ・攻撃なし
    // 制限時間
    b.spellTimer--;
    if(b.spellTimer<=0 && b.phase < b.phaseCount-1){ b.captured=false; bossAdvancePhase(b,false); return; }
    if(b.spellTimer<0) b.spellTimer=0;
    // 攻撃
    b.attackTimer++;
    Pattern[] set=b.descs.get(b.phase);
    Pattern p=set[b.attackIdx % set.length];
    b.cooldown--;
    if(b.cooldown<=0){
      firePattern(b.x,b.y,p);
      b.cooldown = (int)Math.max(6, p.interval*0.75 - b.phase);
      if(diffIdx>0 && set.length>1 && b.t%2==0 && rng.nextDouble() < (0.10 + diffIdx*0.10 + b.phase*0.04))
        firePattern(b.x,b.y,set[(b.attackIdx+1)%set.length]);
    }
    if(b.attackTimer>=b.attackDur){ b.attackIdx++; bossStartAttack(b); }
  }
  void bossTakeDamage(Boss b,int dmg){
    if(b.entering||b.dead||b.invuln>0) return;
    b.hp-=dmg; b.hitFlash=2;
    if(b.hp<=0){
      if(b.phase >= b.phaseCount-1){
        if(b.isSpell && b.captured) awardSpell(b);
        b.hp=0; b.dead=true; b.deathTimer=0; onBossDefeated();
      } else {
        bossAdvancePhase(b, b.captured);
      }
    }
  }
  void bulletCancelToStars(){
    // 星パーティクルは数を抑えて生成（大量弾消し時の処理落ち防止）。得点は全弾分。
    int budget = Math.max(0, 240 - particles.size());
    int n = enemyBullets.size();
    int step = (n>budget && budget>0) ? (int)Math.ceil(n/(double)budget) : 1;
    for(int i=0;i<n;i++){
      score+=20;
      if(step>0 && i%step==0 && particles.size()<300){
        Bullet b=enemyBullets.get(i);
        Particle p=new Particle(); p.x=b.x;p.y=b.y;p.vx=(Math.random()-0.5)*1.6;p.vy=-2-Math.random()*2.2;
        p.life=1;p.decay=0.018;p.hue=48;p.r=4.5;p.star=true; particles.add(p);
      }
    }
    enemyBullets.clear();
  }

  /* ====================================================================
     アイテム・演出
     ==================================================================== */
  void dropItems(double x,double y,int tier){
    int n = tier==1? (rng.nextBoolean()?1:0) : tier==2?2:5;
    for(int i=0;i<n;i++){ Item it=new Item(); it.x=x+rr(-12,12); it.y=y;
      it.type = rng.nextDouble()<0.6?"power":"point"; it.vy=-1.4-rng.nextDouble();
      it.vx=rr(-1,1); it.r=8; items.add(it); }
  }
  void dropSpecial(double x,double y){
    Item a=new Item(); a.x=x-30;a.y=y;a.type="bomb";a.vy=-1.5;a.vx=-0.5;a.r=9; items.add(a);
    Item b=new Item(); b.x=x+30;b.y=y;b.type="life";b.vy=-1.5;b.vx=0.5;b.r=9; items.add(b);
    for(int i=0;i<10;i++){ Item it=new Item(); it.x=x+rr(-40,40);it.y=y;it.type="power";
      it.vy=-2-rng.nextDouble()*1.5; it.vx=rr(-2,2); it.r=8; items.add(it); }
  }
  void updateItem(Item it){
    it.t++;
    if(!pDead){
      boolean poc = py < POC_LINE;            // 上部に行くと画面中の全アイテムを自動回収
      double dx=px-it.x, dy=py-it.y, dd=Math.hypot(dx,dy); if(dd<0.1) dd=0.1;
      if(poc || dd<85){ double sp = poc?9.5:6; it.x+=dx/dd*sp; it.y+=dy/dd*sp; return; }
    }
    it.vy+=0.05; if(it.vy>3) it.vy=3;
    it.x+=it.vx; it.y+=it.vy; if(it.x<10||it.x>W-10) it.vx*=-1;
  }
  void explosion(double x,double y,double hue,boolean big){
    if(particles.size() > (big?1000:800)) { if(big) shake=Math.max(shake,8); return; }  // 過密時は省略
    int n=big?34:12;
    for(int i=0;i<n;i++){ double a=rng.nextDouble()*Math.PI*2, s=rng.nextDouble()*(big?6:3.5)+0.5;
      Particle p=new Particle(); p.x=x;p.y=y;p.vx=Math.cos(a)*s;p.vy=Math.sin(a)*s;
      p.life=1;p.decay=0.02+rng.nextDouble()*0.03;p.hue=hue+rr(-20,20);p.r=(big?4:2.4)+rng.nextDouble()*3;
      particles.add(p); }
    if(big) shake=Math.max(shake,8);
  }
  void floatText(double x,double y,String txt,double hue){
    Floater f=new Floater(); f.x=x;f.y=y;f.txt=txt;f.hue=hue;f.life=1; floaters.add(f);
  }

  /* ====================================================================
     ステージ進行
     ==================================================================== */
  StageRunner buildStageTimeline(int idx){
    StageRunner sr=new StageRunner(); sr.events=new ArrayList<>();
    int t=80; int waveCount=11+idx*3;
    for(int w=0;w<waveCount;w++){
      final String kind = pick(new String[]{"line","vee","stream","tank","swarm","sides"});
      final double baseHue = STAGE_INFO[idx].bg + ir(-40,40);
      sr.events.add(new Ev(t, ()->spawnWave(kind,idx,baseHue)));
      t += Math.max(70, ir(95,160) - idx*3);
    }
    int bossAt = t + 140;
    sr.events.add(new Ev(bossAt-90, ()->{ bossWarn=90; sound.bossDown(); }));
    sr.events.add(new Ev(bossAt, ()->{ boss = makeBoss(idx); }));
    sr.finalTime = bossAt;
    return sr;
  }
  void setDelayed(int delay, Runnable fn){ delayed.add(new Ev(stageTimer+delay, fn)); }

  void spawnWave(String kind,int idx,double baseHue){
    switch(kind){
      case "line": {
        EnemyType g=makeUniqueEnemyType(baseHue,1,"straight",pick(ENEMY_SHAPES));
        int n=7+Math.min(4,idx);
        for(int i=0;i<n;i++) spawnEnemy(variantOf(g,i), W*0.12+(W*0.76)*((double)i/(n-1)), -30-i*8, -1,0);
        break; }
      case "vee": {
        EnemyType g=makeUniqueEnemyType(baseHue,1,"sine",null); int n=9;
        for(int i=0;i<n;i++){ double off=Math.abs(i-(n-1)/2.0);
          spawnEnemy(variantOf(g,i), W/2+(i-(n-1)/2.0)*52, -30-off*20, -1,0);} break; }
      case "stream": {
        EnemyType g=makeUniqueEnemyType(baseHue,1,"drift",null);
        double side=rng.nextBoolean()?0.12:0.88;
        for(int i=0;i<11;i++){ final int ii=i; setDelayed(i*12, ()->spawnEnemy(variantOf(g,ii), W*side,-30,-1, side<0.5?1:-1)); } break; }
      case "sides": {
        EnemyType gL=makeUniqueEnemyType(baseHue,1,"swoop",null);
        EnemyType gR=makeUniqueEnemyType((baseHue+60)%360,1,"swoop",null);
        for(int i=0;i<6;i++){ final int ii=i; setDelayed(i*15, ()->{
          spawnEnemy(variantOf(gL,ii), W*0.1,-30, 60+ii*20, 1);
          spawnEnemy(variantOf(gR,ii), W*0.9,-30, 60+ii*20, -1); }); } break; }
      case "swarm": {
        EnemyType g=makeUniqueEnemyType(baseHue,1, pick(new String[]{"sine","arc","dart"}), null);
        for(int i=0;i<13;i++){ final int ii=i; setDelayed(i*8, ()->spawnEnemy(variantOf(g,ii), W*0.08+rng.nextDouble()*W*0.84,-30,-1,0)); } break; }
      case "tank": {
        EnemyType g=makeUniqueEnemyType(baseHue,2,"hover",null);
        spawnEnemy(g, W*0.25+rng.nextDouble()*W*0.5, -50, 80+rng.nextDouble()*60, 0);
        if(idx>=2 && rng.nextBoolean()){
          final EnemyType g2t=makeUniqueEnemyType((baseHue+30)%360,2,"hover",null);
          setDelayed(45, ()->spawnEnemy(g2t, W*0.3+rng.nextDouble()*W*0.4,-50,120+rng.nextDouble()*50,0));
        }
        EnemyType e=makeUniqueEnemyType((baseHue+40)%360,1,"sine",null);
        for(int i=0;i<5;i++){ final int ii=i; setDelayed(20+i*11, ()->spawnEnemy(variantOf(e,ii), W*0.18+ii*W*0.16,-30,-1,0)); } break; }
    }
  }

  void startStage(int idx){
    stageIndex=idx;
    enemyBullets.clear(); playerBullets.clear(); enemies.clear();
    items.clear(); particles.clear(); boss=null; delayed.clear();
    stageTimer=0; bossWarn=0;
    stageRunner=buildStageTimeline(idx);
    stageDiff = 1 + idx*diff().stageScale;
    curStageHpMul = 1 + idx*0.10;
    state="briefing"; transTimer=160;
    sound.startBGM(idx);
  }
  void onBossDefeated(){
    sound.bossDown(); explosion(boss.x,boss.y,boss.hue,true);
    score += 50000 + stageIndex*20000; flash=1; shake=20; dropSpecial(boss.x,boss.y);
  }
  void nextStageOrWin(){
    if(stageIndex>=5){ state="victory"; transTimer=0; sound.stopBGM(); saveHiIfNeeded(); }
    else startStage(stageIndex+1);
  }

  /* ====================================================================
     プレイヤー
     ==================================================================== */
  void resetPlayer(){ px=W/2; py=H-120; pr=CHARS[charSel].hitr; pShotCd=0; pInvuln=120; pDead=false; pDeathTimer=0; pBombTimer=0; }
  int shotLevel(){ if(power>=80)return 5; if(power>=55)return 4; if(power>=30)return 3; if(power>=12)return 2; return 1; }
  void playerDie(){
    if(pInvuln>0||pDead) return;
    pDead=true; pDeathTimer=0; explosion(px,py,200,true); sound.death();
    shake=18; flash=0.6; clearEnemyBullets(true);
    power=Math.max(0, power - (diffIdx==0?20:35));
    if(boss!=null) boss.captured=false;   // 被弾でスペルカード捕獲失敗
  }
  void respawnOrGameOver(){
    if(lives>0){ lives--; resetPlayer(); bombs=Math.max(bombs,2); }
    else { state="gameover"; transTimer=0; saveHiIfNeeded(); sound.stopBGM(); }
  }
  void useBomb(){
    if(bombs<=0||pDead||pBombTimer>0) return;
    bombs--; pBombTimer=80; pInvuln=Math.max(pInvuln,90); sound.bomb();
    flash=0.8; shake=14; bulletCancelToStars();
    for(Enemy e:enemies){ e.hp-=60; e.hitFlash=3; }
    if(boss!=null){ boss.captured=false; bossTakeDamage(boss, Math.max(1500, boss.phaseHpMax/12)); }
  }
  void clearEnemyBullets(boolean toScore){
    if(toScore) for(Bullet b:enemyBullets){
      Particle p=new Particle(); p.x=b.x;p.y=b.y;p.vx=0;p.vy=-1;p.life=1;p.decay=0.06;p.hue=b.hue;p.r=b.r;
      particles.add(p); score+=10; }
    enemyBullets.clear();
  }
  void updatePlayer(){
    if(pDead){ pDeathTimer++; if(pDeathTimer>60) respawnOrGameOver(); return; }
    if(pInvuln>0) pInvuln--; if(pBombTimer>0) pBombTimer--;
    PChar pc=CHARS[charSel];
    boolean focus=act("focus");
    double sp = focus?pc.focus:pc.speed;
    double dx=0,dy=0;
    if(act("left"))dx-=1; if(act("right"))dx+=1; if(act("up"))dy-=1; if(act("down"))dy+=1;
    if(dx!=0&&dy!=0){ dx*=0.7071; dy*=0.7071; }
    px+=dx*sp; py+=dy*sp;
    px=Math.max(12,Math.min(W-12,px)); py=Math.max(12,Math.min(H-12,py));
    if(pShotCd>0)pShotCd--;
    if(act("shot")&&pShotCd<=0){ firePlayer(); pShotCd=(shotSel==2?9:5); sound.shot(); }
    if(actJust("bomb")) useBomb();
    // 被弾＋グレイズ（かすり）
    for(Bullet b:enemyBullets){
      double dx2=b.x-px, dy2=b.y-py, d2=dx2*dx2+dy2*dy2;
      double hit=b.r*0.55+pr;
      if(d2<hit*hit){ if(pInvuln<=0){ playerDie(); break; } }
      else if(pInvuln<=0 && !b.grazed && d2 < (hit+15)*(hit+15)){
        b.grazed=true; grazeCount++; score+=80; sound.graze();
        Particle gp=new Particle(); gp.x=px+dx2*0.5; gp.y=py+dy2*0.5;
        gp.vx=-dx2*0.04; gp.vy=-dy2*0.04; gp.life=1; gp.decay=0.12; gp.hue=200; gp.r=2.2;
        particles.add(gp);
      }
    }
    if(pInvuln<=0){
      for(Enemy e:enemies){ double rr=e.g.size*0.55+pr;
        if((e.x-px)*(e.x-px)+(e.y-py)*(e.y-py)<rr*rr){ playerDie(); break; } }
      if(boss!=null && !boss.entering){ double rr=boss.r*0.5+pr;
        if((boss.x-px)*(boss.x-px)+(boss.y-py)*(boss.y-py)<rr*rr) playerDie(); }
    }
    for(Item it:items){ double rr=pr+it.r+8;
      if((it.x-px)*(it.x-px)+(it.y-py)*(it.y-py)<rr*rr){ collectItem(it); it.dead=true; } }
  }
  void collectItem(Item it){
    switch(it.type){
      case "power": power=Math.min(100,power+2); score+=50; sound.power(); break;
      case "point": score+=500; floatText(it.x,it.y,"+500",50); break;
      case "bomb": bombs=Math.min(8,bombs+1); sound.extend(); floatText(it.x,it.y,"BOMB+",180); break;
      case "life": lives=Math.min(9,lives+1); sound.extend(); floatText(it.x,it.y,"1UP",120); break;
    }
  }
  void mk(double x,double y,double vx,double vy,double r,int dmg,boolean homing){
    if(playerBullets.size()>400) return;
    PBullet b=new PBullet(); b.x=x;b.y=y;b.vx=vx;b.vy=vy;b.r=r;b.dmg=dmg;b.homing=homing; playerBullets.add(b);
  }
  void firePlayer(){
    int lv=shotLevel(); double sy=py-18; double m=CHARS[charSel].dmgMul;
    switch(shotSel){
      case 0: { // WIDE 拡散
        int d=(int)Math.round(6*m);
        mk(px,sy,0,-16,5,d,false);
        if(lv>=2){ mk(px-10,sy,0,-16,4,d,false); mk(px+10,sy,0,-16,4,d,false); }
        if(lv>=3){ mk(px-18,sy+6,-1.8,-15.5,4,d,false); mk(px+18,sy+6,1.8,-15.5,4,d,false); }
        if(lv>=4){ mk(px-26,sy+10,-3.2,-15,3.5,d,false); mk(px+26,sy+10,3.2,-15,3.5,d,false); }
        if(lv>=5){ mk(px-34,sy+14,-4.6,-14,3.5,d,false); mk(px+34,sy+14,4.6,-14,3.5,d,false); }
        break; }
      case 1: { // FORWARD 集束
        int d=(int)Math.round(11*m);
        mk(px,sy-6,0,-19,7,(int)Math.round(d*1.2),false);
        if(lv>=2){ mk(px-6,sy,0,-18,5,d,false); mk(px+6,sy,0,-18,5,d,false); }
        if(lv>=3){ mk(px-12,sy+4,0,-18,5,d,false); mk(px+12,sy+4,0,-18,5,d,false); }
        if(lv>=4){ mk(px-4,sy-2,-0.5,-18.5,5,d,false); mk(px+4,sy-2,0.5,-18.5,5,d,false); }
        if(lv>=5){ mk(px-18,sy+6,-0.9,-18,5,d,false); mk(px+18,sy+6,0.9,-18,5,d,false); }
        break; }
      case 2: { // HOMING 誘導（火力は控えめ・追尾も緩やか）
        int d=(int)Math.round(4*m);
        mk(px,sy,0,-13,4.5,d,true);
        if(lv>=2){ mk(px-12,sy+4,-2,-12,4,d,true); mk(px+12,sy+4,2,-12,4,d,true); }
        if(lv>=3){ mk(px,sy-4,0,-13,4,d,true); }
        if(lv>=4){ mk(px-20,sy+8,-3.5,-11,3.5,d,true); mk(px+20,sy+8,3.5,-11,3.5,d,true); }
        if(lv>=5){ mk(px-6,sy,-1,-13,3.5,d,true); mk(px+6,sy,1,-13,3.5,d,true); }
        break; }
    }
  }
  void updatePlayerBullets(){
    for(int i=playerBullets.size()-1;i>=0;i--){
      PBullet b=playerBullets.get(i);
      if(b.homing){
        double bestd=1e18, tx=0, ty=0; boolean found=false;
        for(Enemy e:enemies){ double dd=(e.x-b.x)*(e.x-b.x)+(e.y-b.y)*(e.y-b.y); if(dd<bestd){bestd=dd;tx=e.x;ty=e.y;found=true;} }
        if(boss!=null && !boss.entering && !boss.dead){ double dd=(boss.x-b.x)*(boss.x-b.x)+(boss.y-b.y)*(boss.y-b.y); if(dd<bestd){bestd=dd;tx=boss.x;ty=boss.y;found=true;} }
        if(found){ double desired=Math.atan2(ty-b.y,tx-b.x), cur=Math.atan2(b.vy,b.vx);
          double dif=Math.atan2(Math.sin(desired-cur),Math.cos(desired-cur)), turn=0.09;
          cur+=Math.max(-turn,Math.min(turn,dif)); double sp=Math.hypot(b.vx,b.vy); if(sp<1)sp=15;
          b.vx=Math.cos(cur)*sp; b.vy=Math.sin(cur)*sp; }
      }
      b.x+=b.vx; b.y+=b.vy;
      if(b.y<-20||b.y>H+30||b.x<-30||b.x>W+30){ playerBullets.remove(i); continue; }
      boolean hit=false;
      for(Enemy e:enemies){ double rr=e.g.size*0.7+b.r;
        if((e.x-b.x)*(e.x-b.x)+(e.y-b.y)*(e.y-b.y)<rr*rr){ e.hp-=b.dmg; e.hitFlash=2; hit=true; sound.enemyHit();
          Particle p=new Particle(); p.x=b.x;p.y=b.y;p.life=1;p.decay=0.1;p.hue=e.g.hue;p.r=3; particles.add(p); break; } }
      if(!hit && boss!=null && !boss.entering && !boss.dead){ double rr=boss.r*0.85+b.r;
        if((boss.x-b.x)*(boss.x-b.x)+(boss.y-b.y)*(boss.y-b.y)<rr*rr){ bossTakeDamage(boss,b.dmg); hit=true; sound.bossHit();
          Particle p=new Particle(); p.x=b.x;p.y=b.y;p.life=0.6;p.decay=0.12;p.hue=boss.hue;p.r=3; particles.add(p); } }
      if(hit) playerBullets.remove(i);
    }
  }

  /* ====================================================================
     更新（プレイ）
     ==================================================================== */
  void updatePlay(){
    frame++; sound.tick();
    if(actJust("pause")){ state="paused"; return; }
    if(actJust("mute")){ boolean m=sound.toggleMute(); floatText(W/2,40,m?"MUTE":"SOUND ON",60); }
    stageTimer++;
    if(bossWarn>0) bossWarn--;
    if(stageRunner!=null){
      while(stageRunner.idx<stageRunner.events.size() && stageTimer>=stageRunner.events.get(stageRunner.idx).t){
        stageRunner.events.get(stageRunner.idx).fn.run(); stageRunner.idx++;
      }
    }
    for(int i=delayed.size()-1;i>=0;i--){ if(stageTimer>=delayed.get(i).t){ delayed.get(i).fn.run(); delayed.remove(i); } }
    sound.bgmTick(frame, stageIndex);

    updatePlayer();
    updatePlayerBullets();

    for(int i=enemies.size()-1;i>=0;i--){
      Enemy e=enemies.get(i); updateEnemy(e);
      if(e.hp<=0 && !e.dead){ e.dead=true; score+=e.g.score; explosion(e.x,e.y,e.g.hue,e.g.tier>1);
        sound.explode(); dropItems(e.x,e.y,e.g.tier); }
      if(e.dead) enemies.remove(i);
    }
    if(boss!=null){
      updateBoss(boss);
      if(boss.dead){
        if(boss.deathTimer%6==0) explosion(boss.x+rr(-40,40), boss.y+rr(-40,40), boss.hue, true);
        if(boss.deathTimer>120){ boss=null; state="stageclear"; transTimer=200; sound.stopBGM(); }
      }
    }
    for(int i=enemyBullets.size()-1;i>=0;i--){
      Bullet b=enemyBullets.get(i);
      b.speed+=b.accel; if(b.speed<1.0)b.speed=1.0; if(b.speed>12)b.speed=12;
      // 旋回しすぎる弾は直進化（画面内を回り続けて消えないのを防ぐ）
      if(b.curve!=0){ b.turned += Math.abs(b.curve); if(b.turned>2.2) b.curve=0; }
      b.angle+=b.curve;
      b.x+=Math.cos(b.angle)*b.speed; b.y+=Math.sin(b.angle)*b.speed; b.life++;
      if(b.x<-30||b.x>W+30||b.y<-30||b.y>H+30 || b.life>1200) enemyBullets.remove(i);
    }
    for(int i=items.size()-1;i>=0;i--){ Item it=items.get(i); updateItem(it); if(it.dead||it.y>H+30) items.remove(i); }
    for(int i=particles.size()-1;i>=0;i--){ Particle p=particles.get(i); p.x+=p.vx;p.y+=p.vy;p.vy+=0.03;p.life-=p.decay; if(p.life<=0) particles.remove(i); }
    for(int i=floaters.size()-1;i>=0;i--){ Floater f=floaters.get(i); f.y-=0.6; f.life-=0.02; if(f.life<=0) floaters.remove(i); }

    if(shake>0) shake*=0.88; if(shake<0.3) shake=0;
    if(flash>0) flash*=0.9; if(flash<0.02) flash=0;
    clearJust();
  }

  /* ====================================================================
     状態ディスパッチ
     ==================================================================== */
  void update(){
    switch(state){
      case "menu": updateMenu(); break;
      case "charselect": updateCharSelect(); break;
      case "help": if(actJust("confirm")||actJust("shot")){ state="menu"; sound.menu(); } clearJust(); break;
      case "briefing": transTimer--; frame++; if(transTimer<=0){ state="play"; } clearJust(); break;
      case "play": updatePlay(); break;
      case "paused":
        frame++;
        if(actJust("pause")) state="play";
        if(actJust("quit")) toMenu();
        clearJust(); break;
      case "stageclear":
        frame++; transTimer--;
        for(int i=particles.size()-1;i>=0;i--){ Particle p=particles.get(i); p.x+=p.vx;p.y+=p.vy;p.life-=p.decay; if(p.life<=0) particles.remove(i); }
        if(transTimer<=0) nextStageOrWin();
        clearJust(); break;
      case "gameover": frame++; if(actJust("confirm")||actJust("shot")) toMenu(); clearJust(); break;
      case "victory": frame++; if(actJust("confirm")||actJust("shot")) toMenu(); clearJust(); break;
    }
  }
  void updateMenu(){
    frame++;
    int items=4;
    if(actJust("up")){ menuSel=(menuSel+items-1)%items; sound.menu(); }
    else if(actJust("down")){ menuSel=(menuSel+1)%items; sound.menu(); }
    if(menuSel==1){ // 難易度
      if(actJust("left")){ diffIdx=(diffIdx+DIFFS.length-1)%DIFFS.length; sound.menu(); }
      else if(actJust("right")){ diffIdx=(diffIdx+1)%DIFFS.length; sound.menu(); }
    }
    if(actJust("confirm")||actJust("shot")){
      sound.init(); sound.select();
      if(menuSel==0){ state="charselect"; selRow=0; }
      else if(menuSel==1){ diffIdx=(diffIdx+1)%DIFFS.length; }
      else if(menuSel==2) state="help";
      else { hiscore=0; saveHiscore(0); floatText(W/2,H-60,"HISCORE CLEARED",0); }
    }
    clearJust();
  }
  void updateCharSelect(){
    frame++;
    if(actJust("up")||actJust("down")){ selRow ^= 1; sound.menu(); }
    if(selRow==0){
      if(actJust("left")){ charSel=(charSel+CHARS.length-1)%CHARS.length; sound.menu(); }
      else if(actJust("right")){ charSel=(charSel+1)%CHARS.length; sound.menu(); }
    } else {
      if(actJust("left")){ shotSel=(shotSel+SHOT_NAMES.length-1)%SHOT_NAMES.length; sound.menu(); }
      else if(actJust("right")){ shotSel=(shotSel+1)%SHOT_NAMES.length; sound.menu(); }
    }
    if(actJust("confirm")||actJust("shot")){ sound.select(); startNewGame(); clearJust(); return; }
    if(actJust("pause")){ state="menu"; sound.menu(); }
    clearJust();
  }
  void startNewGame(){
    usedPatternSigs.clear(); usedEnemySigs.clear(); usedSpellNames.clear();
    rng = new Random();
    score=0; lives=diff().lives; bombs=diff().bombs; power=0; stageIndex=0; grazeCount=0;
    resetPlayer();
    startStage(0);
  }
  void toMenu(){
    sound.stopBGM(); state="menu"; menuSel=0;
    enemyBullets.clear(); playerBullets.clear(); enemies.clear();
    items.clear(); particles.clear(); boss=null; clearJust();
  }

  /* ====================================================================
     描画
     ==================================================================== */
  // 星
  static class Star { double x,y,z,s; }
  Star[] stars = new Star[140];
  Star[] tstars = new Star[80];
  void initStars(){
    for(int i=0;i<stars.length;i++){ Star s=new Star(); s.x=Math.random()*VW; s.y=Math.random()*H; s.z=Math.random()*2+0.4; s.s=Math.random()*1.6+0.3; stars[i]=s; }
    for(int i=0;i<tstars.length;i++){ Star s=new Star(); s.x=Math.random()*VW; s.y=Math.random()*H; s.z=Math.random()*1.5+0.3; s.s=Math.random()*2+0.5; tstars[i]=s; }
  }
  static Color hsb(double h,double s,double b){ float hh=(float)((((h%360)+360)%360)/360.0); return Color.getHSBColor(hh,(float)clamp01(s),(float)clamp01(b)); }
  static Color hsba(double h,double s,double b,int a){ Color c=hsb(h,s,b); return new Color(c.getRed(),c.getGreen(),c.getBlue(),Math.max(0,Math.min(255,a))); }
  static double clamp01(double v){ return v<0?0:v>1?1:v; }

  double bgHueFor(){
    if(state.equals("menu")||state.equals("help")||state.equals("charselect")) return 220;
    if(state.equals("victory")) return STAGE_INFO[5].bg;
    return STAGE_INFO[Math.max(0,Math.min(5,stageIndex))].bg;
  }
  // 固定解像度のオフスクリーンに描画 →1枚を拡大転送（Retina/大ウィンドウでも描画コスト一定）
  BufferedImage frameBuf; Graphics2D frameG;
  protected void paintComponent(Graphics g){
    super.paintComponent(g);
    if(frameBuf==null){
      frameBuf=new BufferedImage(VW,H,BufferedImage.TYPE_INT_RGB);
      frameG=frameBuf.createGraphics();
      frameG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      frameG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      frameG.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
    frameG.setClip(null);
    renderGame(frameG);   // VW×H の固定解像度へ描画
    // ウィンドウへ1枚転送（レターボックス）
    Graphics2D g2=(Graphics2D)g;
    int ww=getWidth(), wh=getHeight();
    g2.setColor(letterboxColor()); g2.fillRect(0,0,ww,wh);
    double scale=Math.min(ww/(double)VW, wh/(double)H);
    int dw=(int)Math.round(VW*scale), dh=(int)Math.round(H*scale);
    int ox=(ww-dw)/2, oy=(wh-dh)/2;
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g2.drawImage(frameBuf, ox,oy,dw,dh, null);
  }
  Color letterboxColor(){ return hsb(bgHueFor(),0.5,0.04); }

  void renderGame(Graphics2D g2){
    g2.setColor(Color.BLACK); g2.fillRect(0,0,VW,H);   // ブレ時の縁対策
    AffineTransform base=g2.getTransform();
    if(shake>0) g2.translate((Math.random()-0.5)*shake,(Math.random()-0.5)*shake);
    switch(state){
      case "menu": drawMenu(g2); break;
      case "charselect": drawCharSelect(g2); break;
      case "help": drawHelp(g2); break;
      case "briefing": drawBackground(g2,stageIndex); drawBriefing(g2); break;
      case "play": case "paused": case "stageclear": case "gameover": {
        drawBackground(g2,stageIndex);
        // プレイフィールドにクリップして world を描画
        Shape oldClip=g2.getClip();
        g2.setClip(0,0,W,H);
        drawWorld(g2);
        g2.setClip(oldClip);
        drawPlayfieldFrame(g2);
        drawSidebar(g2);
        if(state.equals("play")){ drawWarning(g2);
          if(boss!=null && boss.declTimer>0) drawSpellDeclare(g2,boss); }
        else if(state.equals("paused")) drawPaused(g2);
        else if(state.equals("stageclear")) drawStageClear(g2);
        else if(state.equals("gameover")) drawGameOver(g2);
        break;
      }
      case "victory": drawBackground(g2,5); drawVictory(g2); break;
    }
    g2.setTransform(base);
    if(flash>0){ g2.setColor(new Color(255,255,255,(int)(flash*150))); g2.fillRect(0,0,VW,H); }
  }

  void drawBackground(Graphics2D g2,int idx){
    double hue = (idx>=0&&idx<STAGE_INFO.length)?STAGE_INFO[idx].bg:220;
    GradientPaint gp=new GradientPaint(0,0,hsb(hue,0.6,0.08), 0,H,hsb((hue+60)%360,0.6,0.10));
    g2.setPaint(gp); g2.fillRect(0,0,VW,H);
    for(Star st:stars){
      st.y += st.z*(1.4+idx*0.15);
      if(st.y>H){ st.y=-2; st.x=Math.random()*VW; }
      g2.setColor(hsba((hue+200)%360,0.4,0.6+st.z*0.15,(int)(120+st.z*60)));
      g2.fillRect((int)st.x,(int)st.y,(int)st.s,(int)(st.s+st.z*1.5));
    }
    // 星雲
    Composite oc=g2.getComposite();
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.07f));
    for(int i=0;i<3;i++){
      double cx=(Math.sin(frame*0.001+i*2)*0.5+0.5)*VW;
      double cy=((frame*0.2+i*300)%(H+200))-100;
      RadialGradientPaint rp=new RadialGradientPaint(new Point2D.Double(cx,cy),200,
        new float[]{0f,1f}, new Color[]{hsb((hue+i*40)%360,0.7,0.5), new Color(0,0,0,0)});
      g2.setPaint(rp); g2.fillRect((int)(cx-200),(int)(cy-200),400,400);
    }
    g2.setComposite(oc);
  }
  // プレイフィールドの枠（サイドバーとの境界）
  void drawPlayfieldFrame(Graphics2D g2){
    g2.setColor(hsba(bgHueFor(),0.3,0.04,170));   // 余白部をやや暗く
    g2.fillRect(W,0,SIDEW,H);
    g2.setColor(new Color(120,150,200,120)); g2.setStroke(new BasicStroke(2f));
    g2.drawLine(W,0,W,H);
    g2.setColor(new Color(140,170,220,40)); g2.drawRect(1,1,W-2,H-2);
  }

  void drawWorld(Graphics2D g2){
    // POC ライン（ここより上に行くとアイテム自動回収）
    g2.setColor(new Color(120,200,255, items.isEmpty()?22:60));
    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{8,8}, 0));
    g2.draw(new Line2D.Double(0,POC_LINE,W,POC_LINE));
    g2.setStroke(new BasicStroke(1f));
    for(Enemy e:enemies) drawEnemy(g2,e);
    if(boss!=null) drawBoss(g2,boss);
    drawItems(g2);
    drawBullets(g2);
    drawParticles(g2);
    drawPlayer(g2);
    drawFloaters(g2);
    // ボスのフェーズHPバー（プレイフィールド最上部）
    if(boss!=null && !boss.entering){
      g2.setColor(new Color(0,0,0,110)); g2.fill(new Rectangle2D.Double(40,8,W-80,8));
      g2.setColor(hsb(boss.hue,0.85,0.6)); g2.fill(new Rectangle2D.Double(40,8,(W-80)*((double)boss.hp/boss.phaseHpMax),8));
      g2.setColor(new Color(255,255,255,90)); g2.setStroke(new BasicStroke(1f)); g2.draw(new Rectangle2D.Double(40,8,W-80,8));
    }
  }

  void drawEnemy(Graphics2D g2,Enemy e){
    EnemyType g=e.g;
    AffineTransform t=g2.getTransform();
    g2.translate(e.x,e.y);
    if(g.move.equals("arc")) g2.rotate(Math.sin(e.t*0.05)*0.3);
    boolean lit=e.hitFlash>0;
    Color fill = lit?Color.WHITE:hsb(g.hue,0.7,0.55);
    Color dark = hsb(g.hue,0.7,0.30);
    Color lite = hsb((g.hue+30)%360,0.9,0.72);
    drawShape(g2,g.shape,g.size,g.detailSeed,fill,dark,lite);
    g2.setTransform(t);
    if(g.tier>1 && e.hp<e.maxhp){
      double s=g.size;
      g2.setColor(new Color(0,0,0,120)); g2.fill(new Rectangle2D.Double(e.x-s,e.y-s-8,s*2,3));
      g2.setColor(hsb(g.hue,0.8,0.55)); g2.fill(new Rectangle2D.Double(e.x-s,e.y-s-8,s*2*((double)e.hp/e.maxhp),3));
    }
  }
  void drawShape(Graphics2D g2,String shape,double s,long seed,Color fill,Color dark,Color lite){
    g2.setStroke(new BasicStroke(2f));
    switch(shape){
      case "diamond": {
        Path2D p=poly(new double[]{0,s*0.8,0,-s*0.8}, new double[]{-s,0,s,0});
        g2.setColor(fill); g2.fill(p); g2.setColor(lite); g2.draw(p);
        g2.setColor(dark); g2.fill(circle(0,0,s*0.3)); break; }
      case "triangle": {
        Path2D p=poly(new double[]{0,s*0.9,-s*0.9}, new double[]{s,-s*0.7,-s*0.7});
        g2.setColor(fill); g2.fill(p); g2.setColor(lite); g2.draw(p);
        g2.setColor(lite); g2.fill(circle(0,-s*0.1,s*0.22)); break; }
      case "hex": {
        double[] xs=new double[6], ys=new double[6];
        for(int i=0;i<6;i++){ double a=i/6.0*Math.PI*2; xs[i]=Math.cos(a)*s; ys[i]=Math.sin(a)*s; }
        Path2D p=poly(xs,ys); g2.setColor(fill); g2.fill(p); g2.setColor(lite); g2.draw(p);
        double[] xs2=new double[6], ys2=new double[6];
        for(int i=0;i<6;i++){ double a=i/6.0*Math.PI*2; xs2[i]=Math.cos(a)*s*0.5; ys2[i]=Math.sin(a)*s*0.5; }
        g2.setColor(dark); g2.fill(poly(xs2,ys2)); break; }
      case "arrow": {
        Path2D p=poly(new double[]{0,s,s*0.4,s*0.4,-s*0.4,-s*0.4,-s}, new double[]{s,0,0,-s,-s,0,0});
        g2.setColor(fill); g2.fill(p); g2.setColor(lite); g2.draw(p); break; }
      case "orb": {
        g2.setColor(fill); g2.fill(circle(0,0,s)); g2.setColor(lite); g2.draw(circle(0,0,s));
        g2.setColor(lite); g2.fill(circle(-s*0.3,-s*0.3,s*0.35));
        g2.setColor(dark); g2.fill(circle(s*0.2,s*0.2,s*0.25)); break; }
      case "crab": {
        g2.setColor(fill); g2.fill(new Ellipse2D.Double(-s,-s*0.7,s*2,s*1.4));
        g2.setColor(lite); g2.draw(new Ellipse2D.Double(-s,-s*0.7,s*2,s*1.4));
        g2.setStroke(new BasicStroke(3f));
        g2.draw(new Line2D.Double(-s,0,-s*1.5,-s*0.6));
        g2.draw(new Line2D.Double(s,0,s*1.5,-s*0.6));
        g2.setColor(Color.WHITE); g2.fill(circle(-s*0.4,-s*0.2,s*0.18)); g2.fill(circle(s*0.4,-s*0.2,s*0.18)); break; }
      case "star": {
        double[] xs=new double[10], ys=new double[10];
        for(int i=0;i<10;i++){ double a=i/10.0*Math.PI*2-Math.PI/2; double rrr=(i%2==1)?s*0.45:s; xs[i]=Math.cos(a)*rrr; ys[i]=Math.sin(a)*rrr; }
        Path2D p=poly(xs,ys); g2.setColor(fill); g2.fill(p); g2.setColor(lite); g2.draw(p); break; }
      case "wing": {
        Path2D p=new Path2D.Double();
        p.moveTo(0,-s*0.6); p.quadTo(s*1.4,-s*0.2,s*0.5,s*0.8); p.lineTo(0,s*0.4); p.lineTo(-s*0.5,s*0.8);
        p.quadTo(-s*1.4,-s*0.2,0,-s*0.6); p.closePath();
        g2.setColor(fill); g2.fill(p); g2.setColor(lite); g2.draw(p);
        g2.setColor(lite); g2.fill(circle(0,0,s*0.2)); break; }
      default: g2.setColor(fill); g2.fill(circle(0,0,s));
    }
  }
  static Path2D poly(double[] xs,double[] ys){ Path2D p=new Path2D.Double(); p.moveTo(xs[0],ys[0]); for(int i=1;i<xs.length;i++) p.lineTo(xs[i],ys[i]); p.closePath(); return p; }
  static Ellipse2D circle(double cx,double cy,double r){ return new Ellipse2D.Double(cx-r,cy-r,r*2,r*2); }

  void drawBoss(Graphics2D g2,Boss b){
    AffineTransform t=g2.getTransform();
    g2.translate(b.x,b.y);
    double s=b.r; double hue=b.hue; boolean lit=b.hitFlash>0;
    // 足元の魔法陣
    AffineTransform mcT=g2.getTransform(); g2.rotate(b.t*0.006);
    g2.setStroke(new BasicStroke(2f)); g2.setColor(hsba(hue,0.7,0.6,55));
    double mc=s*2.3; g2.draw(circle(0,0,mc)); g2.draw(circle(0,0,mc*0.7));
    for(int i=0;i<12;i++){ double a=i/12.0*Math.PI*2;
      g2.draw(new Line2D.Double(Math.cos(a)*mc*0.7,Math.sin(a)*mc*0.7,Math.cos(a)*mc,Math.sin(a)*mc)); }
    g2.setTransform(mcT);
    g2.rotate(Math.sin(b.t*0.02)*0.15);
    for(int ring=2;ring>=0;ring--){
      double rrr=s*(1+ring*0.35);
      g2.setColor(hsba((hue+ring*30)%360,0.8,0.55-ring*0.08, (int)(200-ring*50)));
      g2.setStroke(new BasicStroke((float)(4-ring)));
      Path2D p=new Path2D.Double();
      for(int i=0;i<=8;i++){ double a=i/8.0*Math.PI*2 + b.t*0.01*(ring%2==1?1:-1);
        double X=Math.cos(a)*rrr, Y=Math.sin(a)*rrr; if(i==0)p.moveTo(X,Y); else p.lineTo(X,Y); }
      g2.draw(p);
    }
    Path2D body=new Path2D.Double();
    for(int i=0;i<12;i++){ double a=i/12.0*Math.PI*2; double rrr=s*(0.75+0.25*Math.abs(Math.sin(i*1.7+b.t*0.03)));
      double X=Math.cos(a)*rrr, Y=Math.sin(a)*rrr; if(i==0)body.moveTo(X,Y); else body.lineTo(X,Y); }
    body.closePath();
    g2.setColor(lit?Color.WHITE:hsb(hue,0.75,0.5)); g2.fill(body);
    g2.setColor(hsb((hue+40)%360,0.9,0.75)); g2.setStroke(new BasicStroke(3f)); g2.draw(body);
    RadialGradientPaint rp=new RadialGradientPaint(new Point2D.Double(0,0),(float)(s*0.6),
      new float[]{0f,0.4f,1f}, new Color[]{Color.WHITE,hsb((hue+20)%360,0.9,0.7),new Color(0,0,0,0)});
    g2.setPaint(rp); g2.fill(circle(0,0,s*0.6));
    g2.setTransform(t);
  }

  // 弾スプライトのキャッシュ（毎フレームの fillOval 大量描画を回避＝処理落ち対策）
  final HashMap<Long,BufferedImage> bulletCache = new HashMap<>();
  final AffineTransform tmpAT = new AffineTransform();
  BufferedImage bulletSprite(int kind,double hue,double r){
    int hb=(((int)Math.round(hue/12.0)*12)%360+360)%360;
    int rb=Math.max(2,(int)Math.round(r));
    long key=((long)kind<<40) ^ ((long)hb<<8) ^ rb;
    BufferedImage img=bulletCache.get(key);
    if(img!=null) return img;
    double rr=rb;
    int w,h;
    if(kind==1){ w=(int)Math.ceil(rr*2.9)+2; h=(int)Math.ceil(rr*1.3)+2; }
    else { int d=(int)Math.ceil(rr*2.4)+2; w=d; h=d; }
    img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
    Graphics2D g=img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    double cx=w/2.0, cy=h/2.0;
    if(kind==1){ // 米弾（横向き）
      g.setColor(hsb(hb,0.85,0.6)); g.fill(new Ellipse2D.Double(cx-rr*1.35,cy-rr*0.5,rr*2.7,rr));
      g.setColor(new Color(255,255,255,235)); g.fill(new Ellipse2D.Double(cx-rr*0.55,cy-rr*0.28,rr*1.1,rr*0.56));
    } else if(kind==2){ // 大玉
      g.setColor(hsba(hb,0.95,0.45,200)); g.fill(circle(cx,cy,rr*1.18));
      g.setColor(hsb(hb,0.85,0.62)); g.fill(circle(cx,cy,rr*0.82));
      g.setColor(new Color(255,255,255,235)); g.fill(circle(cx,cy,rr*0.4));
      g.setColor(new Color(255,255,255,150)); g.fill(circle(cx-rr*0.3,cy-rr*0.3,rr*0.2));
    } else { // 玉
      g.setColor(hsb(hb,0.95,0.55)); g.fill(circle(cx,cy,rr));
      g.setColor(new Color(255,255,255,240)); g.fill(circle(cx,cy,rr*0.55));
    }
    g.dispose();
    bulletCache.put(key,img);
    return img;
  }
  void drawBullets(Graphics2D g2){
    for(Bullet b:enemyBullets){
      BufferedImage img=bulletSprite(b.kind,b.hue,b.r);
      if(b.kind==1){
        tmpAT.setToTranslation(b.x,b.y); tmpAT.rotate(b.angle);
        tmpAT.translate(-img.getWidth()/2.0,-img.getHeight()/2.0);
        g2.drawImage(img,tmpAT,null);
      } else {
        g2.drawImage(img,(int)Math.round(b.x-img.getWidth()/2.0),(int)Math.round(b.y-img.getHeight()/2.0),null);
      }
    }
    g2.setColor(new Color(120,230,255,230));
    for(PBullet b:playerBullets) g2.fill(new RoundRectangle2D.Double(b.x-b.r*0.5,b.y-b.r*1.6,b.r,b.r*3.2,b.r,b.r));
    g2.setColor(Color.WHITE);
    for(PBullet b:playerBullets) g2.fill(new Rectangle2D.Double(b.x-b.r*0.25,b.y-b.r*1.6,b.r*0.5,b.r*3.2));
  }

  void drawPlayer(Graphics2D g2){
    if(pDead) return;
    AffineTransform t=g2.getTransform(); g2.translate(px,py);
    Composite oc=g2.getComposite();
    if(pInvuln>0 && (pInvuln/4)%2==0) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.4f));
    Path2D body=poly(new double[]{0,8,16,6,0,-6,-16,-8}, new double[]{-18,2,14,10,16,10,14,2});
    g2.setColor(new Color(207,239,255)); g2.fill(body);
    g2.setColor(hsb(CHARS[charSel].hue,0.85,0.6)); g2.fill(poly(new double[]{0,5,0,-5}, new double[]{-12,4,8,4}));
    g2.setColor(new Color(120,220,255,(int)(150+Math.random()*80)));
    g2.fill(poly(new double[]{-5,0,5}, new double[]{12,20+Math.random()*8,12}));
    g2.setComposite(oc);
    if(act("focus")){
      g2.setColor(new Color(255,80,120,230)); g2.setStroke(new BasicStroke(1.5f)); g2.draw(circle(0,0,pr+1));
      g2.setColor(new Color(255,59,107)); g2.fill(circle(0,0,pr*0.7));
      g2.setColor(new Color(255,255,255,60)); g2.draw(circle(0,0,14));
    }
    g2.setTransform(t);
  }

  void drawItems(Graphics2D g2){
    for(Item it:items){
      Color c; String tx;
      if(it.type.equals("power")){ c=hsb(0,0.9,0.55); tx="P"; }
      else if(it.type.equals("point")){ c=hsb(50,0.95,0.55); tx="•"; }
      else if(it.type.equals("bomb")){ c=hsb(180,0.9,0.55); tx="B"; }
      else { c=hsb(120,0.9,0.55); tx="1"; }
      g2.setColor(c); g2.fill(new Rectangle2D.Double(it.x-it.r*0.6,it.y-it.r*0.6,it.r*1.2,it.r*1.2));
      g2.setColor(Color.BLACK); g2.setFont(new Font("SansSerif",Font.BOLD,9));
      centerStr(g2,tx,it.x,it.y+3);
    }
  }
  void drawParticles(Graphics2D g2){
    for(Particle p:particles){
      int a=(int)(Math.max(0,p.life)*255);
      g2.setColor(hsba(p.hue,0.9,0.65,a));
      if(p.star) drawSpark(g2,p.x,p.y, p.r*Math.max(0.3,p.life)+1);
      else { double r=p.r*p.life+0.5; g2.fill(circle(p.x,p.y,r)); }
    }
  }
  void drawSpark(Graphics2D g2,double x,double y,double r){
    AffineTransform t=g2.getTransform(); g2.translate(x,y);
    double[] xs=new double[10], ys=new double[10];
    for(int i=0;i<10;i++){ double a=i/10.0*Math.PI*2-Math.PI/2; double rr=(i%2==1)?r*0.4:r; xs[i]=Math.cos(a)*rr; ys[i]=Math.sin(a)*rr; }
    g2.fill(poly(xs,ys)); g2.setTransform(t);
  }
  void drawFloaters(Graphics2D g2){
    g2.setFont(new Font("SansSerif",Font.BOLD,16));
    for(Floater f:floaters){ g2.setColor(hsba(f.hue,0.9,0.75,(int)(Math.max(0,f.life)*255))); centerStr(g2,f.txt,f.x,f.y); }
  }

  /* ---------------- HUD（右サイドバー） ---------------- */
  static final Font F_TITLE=new Font("SansSerif",Font.BOLD,24);
  static final Font F_LBL  =new Font("SansSerif",Font.BOLD,14);
  static final Font F_NUM  =new Font("Monospaced",Font.BOLD,22);
  static final Font F_SMALL=new Font("SansSerif",Font.PLAIN,12);
  static final Font F_TIMER=new Font("Monospaced",Font.BOLD,22);
  void drawWrapped(Graphics2D g2,String s,int x,int y,int maxw,int lh){
    FontMetrics fm=g2.getFontMetrics(); StringBuilder line=new StringBuilder(); int cy=y;
    for(int i=0;i<s.length();i++){ line.append(s.charAt(i));
      if(fm.stringWidth(line.toString())>maxw){ g2.drawString(line.substring(0,line.length()-1),x,cy);
        cy+=lh; line.setLength(0); line.append(s.charAt(i)); } }
    if(line.length()>0) g2.drawString(line.toString(),x,cy);
  }
  void drawSidebar(Graphics2D g2){
    int sx=W+24, right=VW-20, sw=right-sx;
    g2.setFont(F_TITLE); g2.setColor(new Color(150,200,255)); g2.drawString("STELLAR",sx,46);
    g2.setColor(new Color(190,150,255)); g2.drawString("CASCADE",sx,72);
    g2.setFont(F_LBL); g2.setColor(new Color(150,180,220)); g2.drawString("SCORE",sx,108);
    g2.setFont(F_NUM); g2.setColor(Color.WHITE); g2.drawString(pad(score,8),sx,134);
    g2.setFont(F_SMALL); g2.setColor(new Color(127,168,216)); g2.drawString("HI "+pad(hiscore,8),sx,154);
    g2.setFont(F_LBL); g2.setColor(new Color(159,255,207)); g2.drawString("PLAYER",sx,190);
    for(int i=0;i<lives;i++){ g2.setColor(new Color(207,239,255)); double bx=sx+12+i*18, by=204;
      g2.fill(poly(new double[]{bx,bx+6,bx-6}, new double[]{by-7,by+6,by+6})); }
    g2.setFont(F_LBL); g2.setColor(new Color(159,227,255)); g2.drawString("BOMB",sx,232);
    for(int i=0;i<bombs;i++){ g2.setColor(hsb(180,0.9,0.6)); g2.fill(circle(sx+14+i*16,244,5)); }
    int lv=shotLevel();
    g2.setFont(F_LBL); g2.setColor(new Color(255,210,127)); g2.drawString("POWER  Lv."+lv,sx,278);
    g2.setColor(new Color(255,255,255,40)); g2.fill(new Rectangle2D.Double(sx,286,sw,10));
    g2.setColor(hsb(40,0.95,0.55)); g2.fill(new Rectangle2D.Double(sx,286,sw*(power/100.0),10));
    g2.setFont(F_LBL); g2.setColor(new Color(150,210,255)); g2.drawString("GRAZE  "+grazeCount,sx,322);
    g2.setColor(new Color(120,150,200,80)); g2.setStroke(new BasicStroke(1f)); g2.drawLine(sx,338,right,338);
    if(boss!=null && !boss.entering){
      g2.setFont(F_LBL); g2.setColor(new Color(255,120,150)); g2.drawString("◆ BOSS",sx,368);
      g2.setFont(F_SMALL); g2.setColor(new Color(220,230,250)); drawWrapped(g2,boss.name,sx,388,sw,15);
      int remain=boss.phaseCount-boss.phase;
      for(int i=0;i<boss.phaseCount;i++){ g2.setColor(i<remain?new Color(255,90,120):new Color(70,70,82));
        g2.fill(circle(sx+8+i*16,410,5)); }
      g2.setColor(hsb(boss.hue,0.5,1.0)); g2.setFont(F_LBL); drawWrapped(g2,boss.spellName,sx,442,sw,20);
      int secs=(int)Math.ceil(boss.spellTimer/60.0);
      g2.setColor(secs<=5?new Color(255,90,90):Color.WHITE); g2.setFont(F_TIMER);
      g2.drawString("TIME  "+secs,sx,498);
    }
    g2.setFont(F_SMALL); g2.setColor(new Color(143,184,232)); g2.drawString(STAGE_INFO[stageIndex].name,sx,H-92);
    g2.setColor(new Color(120,160,210)); g2.drawString(STAGE_INFO[stageIndex].sub,sx,H-72);
    g2.drawString("DIFFICULTY  "+diff().name,sx,H-48);
    g2.drawString(CHARS[charSel].name+" / "+SHOT_NAMES[shotSel].trim().split("\\s+")[0],sx,H-28);
    if(sound.muted){ g2.setColor(new Color(255,136,136)); g2.drawString("MUTE (M)",sx,H-8); }
  }
  void drawSpellDeclare(Graphics2D g2,Boss b){
    double tt=1 - b.declTimer/80.0;            // 0→1
    int by=H/2-50;
    Composite oc=g2.getComposite();
    float band=(float)Math.min(0.5, tt*1.6);
    g2.setColor(new Color(0,0,0,(int)(band*180))); g2.fillRect(0,by,W,100);
    g2.setColor(hsba(b.hue,0.8,0.7,(int)(band*255))); g2.fillRect(0,by,W,2); g2.fillRect(0,by+98,W,2);
    int a=(int)(Math.min(1,tt*2)*255);
    g2.setColor(new Color(255,205,90,a)); g2.setFont(new Font("SansSerif",Font.BOLD,16));
    centerStr(g2,"◆  S P E L L   C A R D  ◆",W/2,by+34);
    g2.setColor(hsba(b.hue,0.45,1.0,a)); g2.setFont(new Font("SansSerif",Font.BOLD,34));
    centerStr(g2,b.spellName,W/2,by+76);
    g2.setComposite(oc);
  }
  void drawWarning(Graphics2D g2){
    if(bossWarn<=0) return;
    Composite oc=g2.getComposite();
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,(float)(0.4+0.6*Math.abs(Math.sin(bossWarn*0.3)))));
    g2.setColor(new Color(255,59,91)); g2.setFont(new Font("SansSerif",Font.BOLD,40));
    centerStr(g2,"⚠ WARNING ⚠",W/2,H/2);
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,18));
    centerStr(g2,"強大な反応を感知",W/2,H/2+34);
    g2.setComposite(oc);
  }

  /* ---------------- 画面 ---------------- */
  void drawMenu(Graphics2D g2){
    GradientPaint gp=new GradientPaint(0,0,new Color(6,9,22),0,H,new Color(10,14,34));
    g2.setPaint(gp); g2.fillRect(0,0,VW,H);
    for(Star st:tstars){ st.y+=st.z; if(st.y>H)st.y=0;
      g2.setColor(new Color(159,200,255,(int)(120+Math.sin((frame+st.x)*0.05)*90)));
      g2.fill(new Rectangle2D.Double(st.x,st.y,st.s,st.s)); }
    // タイトル
    AffineTransform t=g2.getTransform(); g2.translate(CX,250);
    g2.setFont(new Font("SansSerif",Font.BOLD,64));
    g2.setColor(new Color(92,225,255)); centerStr(g2,"STELLAR",2,2);
    g2.setColor(Color.WHITE); centerStr(g2,"STELLAR",0,0);
    g2.setColor(new Color(176,123,255)); centerStr(g2,"CASCADE",2,66);
    g2.setColor(Color.WHITE); centerStr(g2,"CASCADE",0,64);
    g2.setTransform(t);
    g2.setColor(new Color(127,168,216)); g2.setFont(new Font("SansSerif",Font.BOLD,18));
    centerStr(g2,"— 六面制 弾幕シューティング —",CX,350);

    String[] labels={"ゲームスタート","難易度:  "+diff().name,"遊び方","ハイスコア消去"};
    for(int i=0;i<labels.length;i++){
      boolean sel=i==menuSel;
      g2.setFont(new Font("SansSerif", sel?Font.BOLD:Font.PLAIN, sel?28:22));
      g2.setColor(sel?Color.WHITE:new Color(95,127,174));
      String s = sel? (i==1? "◀ "+labels[i]+" ▶" : "▶ "+labels[i]+" ◀") : labels[i];
      centerStr(g2,s,CX,460+i*55);
    }
    g2.setColor(new Color(95,127,174)); g2.setFont(new Font("SansSerif",Font.PLAIN,13));
    centerStr(g2,"移動: 方向キー / WASD    ショット: Z / Space    ボム: X",CX,H-110);
    centerStr(g2,"低速(当たり判定表示): Shift    ポーズ: P    ミュート: M",CX,H-88);
    g2.setColor(new Color(58,86,127)); g2.setFont(new Font("SansSerif",Font.PLAIN,12));
    centerStr(g2,"HI-SCORE  "+pad(hiscore,8),CX,H-52);
    centerStr(g2,"敵も弾幕も自動生成 — 二度と同じものは出現しません",CX,H-30);
  }
  void drawHelp(Graphics2D g2){
    g2.setColor(new Color(6,9,22)); g2.fillRect(0,0,VW,H);
    g2.setColor(new Color(207,227,255)); g2.setFont(new Font("SansSerif",Font.BOLD,30));
    centerStr(g2,"遊び方",CX,90);
    String[] lines={
      "◆ 目的","　 6つのステージを攻略し、各面のボスを撃破せよ。","",
      "◆ 操作","　 方向キー / WASD … 移動","　 Z / Space … ショット（押しっぱなしで連射）",
      "　 X … ボム（敵弾を消し大ダメージ）","　 Shift … 低速移動（赤い点が当たり判定）",
      "　 P / Esc … ポーズ　　M … サウンドON/OFF","",
      "◆ システム","　 赤P=パワーアップ(最大Lv.5)  黄•=得点","　 青B=ボム増加  緑1=残機増加",
      "　 難易度はメニューで EASY / NORMAL / HARD 選択可","",
      "◆ 特徴","　 敵と弾幕はすべて自動生成され、シグネチャ照合で",
      "　 「二度と同じ敵・同じ弾幕」が出ないよう保証。","",
      "Z / Enter で戻る",
    };
    double y=140;
    for(String l:lines){
      boolean head=l.startsWith("◆");
      g2.setColor(head?new Color(127,208,255):new Color(170,204,240));
      g2.setFont(new Font("SansSerif",head?Font.BOLD:Font.PLAIN,head?18:16));
      g2.drawString(l,70,(int)y); y+=27;
    }
  }
  void drawCharSelect(Graphics2D g2){
    GradientPaint gp=new GradientPaint(0,0,new Color(8,10,26),0,H,new Color(12,16,38));
    g2.setPaint(gp); g2.fillRect(0,0,VW,H);
    for(Star st:tstars){ st.y+=st.z; if(st.y>H)st.y=0;
      g2.setColor(new Color(159,200,255,80)); g2.fill(new Rectangle2D.Double(st.x,st.y,st.s,st.s)); }
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,30));
    centerStr(g2,"機体・装備 選択",CX,90);
    PChar c=CHARS[charSel];
    drawSelRow(g2,"機体",c.name,c.desc,210,selRow==0);
    drawSelRow(g2,"ショット",SHOT_NAMES[shotSel].trim(),SHOT_DESC[shotSel],330,selRow==1);
    // 機体プレビュー
    AffineTransform t=g2.getTransform(); g2.translate(CX,480); g2.scale(3.0,3.0);
    Path2D body=poly(new double[]{0,8,16,6,0,-6,-16,-8}, new double[]{-18,2,14,10,16,10,14,2});
    g2.setColor(new Color(207,239,255)); g2.fill(body);
    g2.setColor(hsb(c.hue,0.85,0.6)); g2.fill(poly(new double[]{0,5,0,-5}, new double[]{-12,4,8,4}));
    g2.setColor(new Color(120,220,255,180)); g2.fill(poly(new double[]{-5,0,5}, new double[]{12,22,12}));
    g2.setTransform(t);
    // ステータス
    g2.setColor(new Color(170,204,240)); g2.setFont(new Font("SansSerif",Font.PLAIN,15));
    centerStr(g2,String.format("速度 %.1f    当たり判定 %.1f    火力 ×%.2f", c.speed, c.hitr, c.dmgMul), CX, 640);
    g2.setColor(new Color(127,168,216)); g2.setFont(new Font("SansSerif",Font.PLAIN,14));
    centerStr(g2,"↑↓ 項目移動    ←→ 変更    Z / Enter 決定    Esc 戻る", CX, H-80);
  }
  void drawSelRow(Graphics2D g2,String label,String val,String desc,int y,boolean sel){
    g2.setFont(new Font("SansSerif",Font.BOLD,16)); g2.setColor(new Color(127,168,216));
    centerStr(g2,label,CX,y-26);
    g2.setFont(new Font("SansSerif",Font.BOLD,sel?30:26)); g2.setColor(sel?Color.WHITE:new Color(120,150,190));
    centerStr(g2, sel? "◀   "+val+"   ▶" : val, CX, y+6);
    g2.setFont(new Font("SansSerif",Font.PLAIN,14)); g2.setColor(new Color(150,180,215));
    centerStr(g2,desc,CX,y+30);
  }
  void drawBriefing(Graphics2D g2){
    Composite oc=g2.getComposite();
    float a=(float)Math.max(0,Math.min(1, transTimer<30? transTimer/30.0 : 1));
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,a));
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,26));
    centerStr(g2,STAGE_INFO[stageIndex].name,CX,H/2-30);
    g2.setFont(new Font("SansSerif",Font.BOLD,44));
    centerStr(g2,STAGE_INFO[stageIndex].sub,CX,H/2+24);
    g2.setComposite(oc);
  }
  void drawPaused(Graphics2D g2){
    g2.setColor(new Color(0,0,10,180)); g2.fillRect(0,0,VW,H);
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,48)); centerStr(g2,"PAUSE",CX,H/2-20);
    g2.setColor(new Color(159,200,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,18));
    centerStr(g2,"P / Esc で再開　　Q でタイトルへ",CX,H/2+30);
  }
  void drawStageClear(Graphics2D g2){
    g2.setColor(new Color(0,0,10,140)); g2.fillRect(0,0,VW,H);
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,40));
    centerStr(g2,"STAGE "+(stageIndex+1)+" CLEAR!",CX,H/2-20);
    g2.setColor(new Color(159,255,207)); g2.setFont(new Font("SansSerif",Font.PLAIN,18));
    centerStr(g2,"SCORE  "+pad(score,8),CX,H/2+24);
  }
  void drawGameOver(Graphics2D g2){
    g2.setColor(new Color(20,0,5,205)); g2.fillRect(0,0,VW,H);
    g2.setColor(new Color(255,107,107)); g2.setFont(new Font("SansSerif",Font.BOLD,56));
    centerStr(g2,"GAME OVER",CX,H/2-20);
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.PLAIN,20));
    centerStr(g2,"SCORE  "+pad(score,8),CX,H/2+30);
    g2.setColor(new Color(159,200,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,16));
    centerStr(g2,"Z / Enter でタイトルへ",CX,H/2+70);
  }
  void drawVictory(Graphics2D g2){
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,50)); centerStr(g2,"ALL CLEAR!",CX,220);
    g2.setColor(new Color(255,210,127)); g2.setFont(new Font("SansSerif",Font.BOLD,22)); centerStr(g2,"全6面 制覇 — おめでとう！",CX,280);
    g2.setColor(new Color(207,227,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,18)); centerStr(g2,"最終スコア",CX,360);
    g2.setColor(Color.WHITE); g2.setFont(new Font("Monospaced",Font.BOLD,36)); centerStr(g2,pad(score,8),CX,410);
    g2.setColor(new Color(255,180,210)); g2.setFont(new Font("SansSerif",Font.BOLD,17));
    centerStr(g2,"GRAZE  "+grazeCount,CX,458);
    g2.setColor(new Color(127,208,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,15));
    centerStr(g2,"出現した固有の敵タイプ: "+usedEnemySigs.size()+" 種",CX,492);
    centerStr(g2,"出現した固有の弾幕: "+usedPatternSigs.size()+" 種",CX,518);
    centerStr(g2,"出現したスペルカード: "+usedSpellNames.size()+" 種",CX,544);
    centerStr(g2,"（すべて互いに異なるパターンでした）",CX,570);
    g2.setColor(new Color(159,200,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,16)); centerStr(g2,"Z / Enter でタイトルへ",CX,624);
  }

  // テキストヘルパ
  void centerStr(Graphics2D g2,String s,double cx,double cy){ FontMetrics fm=g2.getFontMetrics(); g2.drawString(s,(int)(cx-fm.stringWidth(s)/2.0),(int)cy); }
  void rightStr(Graphics2D g2,String s,double rx,double cy){ FontMetrics fm=g2.getFontMetrics(); g2.drawString(s,(int)(rx-fm.stringWidth(s)),(int)cy); }
  static String pad(int v,int n){ String s=Integer.toString(Math.max(0,v)); while(s.length()<n) s="0"+s; return s; }

  /* ---------------- ハイスコア保存 ---------------- */
  static File hiFile(){ return new File(System.getProperty("user.home"), ".stellar_cascade_hi"); }
  int loadHiscore(){ try{ BufferedReader r=new BufferedReader(new FileReader(hiFile())); int v=Integer.parseInt(r.readLine().trim()); r.close(); return v; }catch(Exception e){ return 0; } }
  void saveHiscore(int v){ try{ FileWriter w=new FileWriter(hiFile()); w.write(""+v); w.close(); }catch(Exception e){} }
  void saveHiIfNeeded(){ if(score>hiscore){ hiscore=score; saveHiscore(hiscore); } }

  /* ---------------- KeyListener ---------------- */
  public void keyPressed(KeyEvent e){ int c=e.getKeyCode(); if(c<down.length){ if(!down[c]) just[c]=true; down[c]=true; } }
  public void keyReleased(KeyEvent e){ int c=e.getKeyCode(); if(c<down.length) down[c]=false; }
  public void keyTyped(KeyEvent e){}

  /* ====================================================================
     オーディオ（自前ミキサ + 簡易シーケンサ）
     ==================================================================== */
  static class Sound {
    static final int RATE=44100;
    SourceDataLine line; volatile boolean ok=false, muted=false;
    final List<Voice> voices = Collections.synchronizedList(new ArrayList<>());
    Thread th; int shotCd=0;
    static final int[][] SCALES = {
      {0,2,3,5,7,8,10},{0,2,4,7,9},{0,3,5,6,7,10},{0,1,4,5,7,8,11},{0,2,3,7,8},{0,2,4,5,7,9,11}
    };
    int[] scale = SCALES[0]; double bassRoot=82; boolean bgmOn=false; int stepFrames=8; int bgmStep=0;
    java.util.Random rnd=new java.util.Random();

    void init(){
      if(ok) return;
      try{
        AudioFormat fmt=new AudioFormat(RATE,16,1,true,false);
        line=AudioSystem.getSourceDataLine(fmt); line.open(fmt, 4096); line.start();
        ok=true;
        th=new Thread(this::mix); th.setDaemon(true); th.start();
      }catch(Throwable e){ ok=false; }
    }
    void mix(){
      final int N=512; byte[] buf=new byte[N*2];
      while(ok){
        for(int i=0;i<N;i++){
          double s=0;
          synchronized(voices){
            for(int v=voices.size()-1; v>=0; v--){ Voice vo=voices.get(v); s+=vo.next(); if(vo.done) voices.remove(v); }
          }
          if(muted) s=0;
          s*=0.5; if(s>1)s=1; if(s<-1)s=-1;
          short val=(short)(s*32767);
          buf[i*2]=(byte)(val&0xff); buf[i*2+1]=(byte)((val>>8)&0xff);
        }
        try{ line.write(buf,0,buf.length); }catch(Throwable e){ break; }
      }
    }
    void add(Voice v){ if(ok && !muted){ if(voices.size()<48) voices.add(v); } }
    void tone(double freq,double dur,int type,double vol,double slide){ add(new Voice(freq,dur,type,vol,slide,false)); }
    void noise(double dur,double vol){ add(new Voice(0,dur,0,vol,0,true)); }
    void tick(){ if(shotCd>0) shotCd--; if(grazeCd>0) grazeCd--; }
    // SFX
    void shot(){ if(shotCd>0) return; shotCd=4; tone(880,0.05,0,0.05,0.6); }
    void enemyHit(){ tone(220,0.04,0,0.05,0.8); }
    void explode(){ noise(0.22,0.28); tone(120,0.3,1,0.16,0.4); }
    void bossHit(){ noise(0.05,0.05); }
    void bomb(){ noise(0.55,0.32); tone(80,0.6,1,0.22,0.5); }
    void death(){ noise(0.45,0.38); tone(300,0.55,1,0.28,0.3); }
    void menu(){ tone(660,0.06,0,0.12,1.2); }
    void select(){ tone(990,0.1,0,0.15,1.3); }
    void bossDown(){ tone(330,1.0,1,0.28,0.4); noise(0.9,0.28); }
    void power(){ tone(1200,0.08,2,0.16,1.4); }
    void extend(){ tone(523,0.12,2,0.18,1.0); tone(784,0.18,2,0.18,1.0); }
    int grazeCd=0;
    void graze(){ if(grazeCd>0) return; grazeCd=3; tone(2400,0.03,2,0.04,1.1); }
    void spellDeclare(){ tone(220,0.6,1,0.16,2.0); tone(330,0.6,2,0.12,2.0); noise(0.2,0.12); }
    void spellGet(){ tone(523,0.5,2,0.18,1.0); tone(659,0.5,2,0.16,1.0); tone(784,0.5,2,0.16,1.0); tone(1046,0.6,2,0.18,1.0); }
    boolean toggleMute(){ muted=!muted; return muted; }
    // BGM
    void startBGM(int idx){ scale=SCALES[idx%SCALES.length]; bassRoot=82*Math.pow(2,(idx%3)/12.0); bgmOn=true; bgmStep=0; stepFrames=Math.max(5,9-idx/2); }
    void stopBGM(){ bgmOn=false; }
    void bgmTick(int frame,int idx){
      if(!bgmOn||!ok||muted) return;
      if(frame % stepFrames != 0) return;
      int s=bgmStep%16;
      if(s%4==0) tone(bassRoot,0.18,3,0.16,0.9);
      int deg=scale[(bgmStep*3)%scale.length];
      int oct=(bgmStep%8<4)?2:3;
      double f=bassRoot*Math.pow(2,oct)*Math.pow(2,deg/12.0);
      if(s%2==0) tone(f,0.12,0,0.045,1.0);
      if(s%2==1) noise(0.025,0.035);
      bgmStep++;
    }
    class Voice {
      double baseFreq, vol, slide, phase; int total, pos, type; boolean noise2, done;
      Voice(double freq,double dur,int type,double vol,double slide,boolean noise){
        this.baseFreq=freq; this.vol=vol; this.slide=slide; this.type=type; this.noise2=noise;
        total=(int)(dur*RATE); if(total<1) total=1;
      }
      double next(){
        if(done) return 0;
        double s;
        if(noise2){ s=rnd.nextDouble()*2-1; }
        else {
          double f = (slide>0)? baseFreq*Math.pow(slide,(double)pos/total) : baseFreq;
          phase += f/RATE; if(phase>=1) phase-=1;
          switch(type){
            case 0: s=phase<0.5?1:-1; break;            // square
            case 1: s=2*phase-1; break;                  // saw
            case 2: s=Math.sin(phase*Math.PI*2); break;  // sine
            default: s=phase<0.5? (4*phase-1):(3-4*phase); break; // triangle
          }
        }
        double env=vol*Math.max(0,1.0-(double)pos/total);
        int attack=64; if(pos<attack) env*=(double)pos/attack;
        pos++; if(pos>=total) done=true;
        return s*env;
      }
    }
  }

  /* ====================================================================
     main
     ==================================================================== */
  public static void main(String[] args){
    if(args.length>0 && args[0].equals("selftest")){ runSelfTest(); return; }
    try{ System.setProperty("apple.awt.application.name","Stellar Cascade"); }catch(Exception e){}
    SwingUtilities.invokeLater(()->{
      JFrame f=new JFrame("STELLAR CASCADE");
      StellarCascade panel=new StellarCascade();
      f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
      f.setContentPane(panel);
      f.pack();
      f.setLocationRelativeTo(null);
      f.setVisible(true);
      panel.requestFocusInWindow();
      panel.start();
    });
  }

  static void runSelfTest(){
    StellarCascade g=new StellarCascade();
    BufferedImage img=new BufferedImage(VW,H,BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2=img.createGraphics();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    try{
      // メニュー描画
      g.renderGame(g2);
      g.diffIdx=0; g.startNewGame();
      for(int f=0;f<400;f++){ g.down[KeyEvent.VK_Z]=true; g.update(); g.renderGame(g2); }
      if(g.stageRunner!=null) g.stageTimer = g.stageRunner.finalTime+5;
      for(int f=0;f<500;f++){ g.update(); g.renderGame(g2); }
      if(g.boss!=null){ for(int i=0;i<30;i++){ g.bossTakeDamage(g.boss,150); g.update(); g.renderGame(g2); } }
      // ボス戦の代表フレームをPNG保存（レイアウト目視確認用）
      try{ javax.imageio.ImageIO.write(img,"png",new File("/tmp/sc_frame.png")); }catch(Exception ex){}
      // 全ボス + 全弾幕タイプ + 全難易度
      for(int d=0;d<DIFFS.length;d++){ g.diffIdx=d;
        for(int bi=0;bi<6;bi++){ Boss b=g.makeBoss(bi); b.entering=false; for(int k=0;k<40;k++){ g.updateBoss(b); g.bossTakeDamage(b,1000);} } }
      String[] types={"ring","spiral","fan","spray","wall","flower","aimedBurst","cross","arc","rain"};
      for(String t:types){ Pattern p=g.makeUniquePattern(t,200,1.0,0.5); g.firePattern(360,200,p); }
      // 全機体 × 全ショット（誘導弾含む）を発射 → 命中処理まで
      g.power=99;
      for(int cs=0; cs<CHARS.length; cs++){ for(int ss=0; ss<SHOT_NAMES.length; ss++){
        g.charSel=cs; g.shotSel=ss; g.resetPlayer(); g.playerBullets.clear(); g.enemies.clear(); g.boss=null;
        EnemyType et=g.makeUniqueEnemyType(100,1,"straight","orb"); et.hp=300; g.spawnEnemy(et,360,300,-1,0);
        for(int f=0;f<40;f++){ g.firePlayer(); g.updatePlayerBullets(); }
      }}
      // POC：上部でアイテム自動回収（プレイヤーへ引き寄せられるか）
      g.charSel=0; g.resetPlayer(); g.py=100; g.items.clear();
      Item it=new Item(); it.x=100; it.y=500; it.type="power"; it.r=8; g.items.add(it);
      for(int f=0;f<60;f++){ g.updateItem(it); }
      boolean pocOK = it.y < 200;   // 500 → プレイヤー(100付近)へ吸い寄せ
      g.renderGame(g2);
      if(!pocOK) System.out.println("WARN: POC pull not working (y="+it.y+")");
      // 画面外カリング検証：湾曲弾を大量に撃ち、一定時間後に全消えるか
      g.state="play"; g.stageRunner=null; g.enemies.clear(); g.boss=null; g.items.clear();
      g.enemyBullets.clear(); g.resetPlayer();
      for(String t:new String[]{"arc","spiral","ring","flower"}){
        Pattern p=g.makeUniquePattern(t,200,1.2,0.3);
        for(int k=0;k<6;k++){ g.firePattern(360,300,p); }
      }
      int peak=g.enemyBullets.size();
      for(int f=0; f<1300; f++){ g.update(); }
      System.out.println("CULL test: peak="+peak+" -> after1300="+g.enemyBullets.size()+(g.enemyBullets.size()==0?" OK":" NOT CLEARED"));
      System.out.println("TEST_OK psig="+g.usedPatternSigs.size()+" esig="+g.usedEnemySigs.size()+" eb="+g.enemyBullets.size());
    }catch(Throwable e){ System.out.println("TEST_FAIL "+e); e.printStackTrace(); System.exit(1); }
    g2.dispose();
    System.exit(0);
  }
}
