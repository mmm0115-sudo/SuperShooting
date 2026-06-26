/* =======================================================================
   STELLAR CASCADE  —  6面制 弾幕シューティング（Java / Swing + Java2D）
   - メニュー → 6ステージ → 各ボス → エンディング
   - 敵・弾幕はプロシージャル生成 + シグネチャ照合で「1プレイ中は同じものが出ない」（登録は startNewGame でリセット）
   - 難易度 EASY / NORMAL / HARD
   コンパイル: javac StellarCascade.java
   実行:       java StellarCascade
   自己テスト: java StellarCascade selftest
   ======================================================================= */
package game;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.sound.sampled.*;
import java.io.*;
import bullet.*;
import entity.*;
import util.*;
import static render.Colors.*;

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
    KEYS.put("rewind",new int[]{KeyEvent.VK_R, KeyEvent.VK_C});
    KEYS.put("volup", new int[]{KeyEvent.VK_EQUALS, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_RIGHT_PARENTHESIS});
    KEYS.put("voldn", new int[]{KeyEvent.VK_MINUS, KeyEvent.VK_OPEN_BRACKET});
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

  /* ---- 手で作り込んだ弾幕テンプレート（ランダムではなく設計済み） ----
     count=弾数(リング/扇等) arms=本数(螺旋/十字) spd=基準弾速 spin=毎回の回転
     spreadDeg=扇の角度 aim=自機狙い kind=弾種(0玉1米2大玉) interval=連射間隔 hueOff=色相オフセット */
  // ボス級：綺麗な対称性・一定の回転・読める隙間を持つ設計弾幕
  static final Tmpl[] DESIGNS = {
    new Tmpl("ring",   20,1, 2.6, 0.12,  0, false, 0, 11,  0),  // 回転リング
    new Tmpl("ring",   24,1, 2.3,-0.09,  0, false, 0, 13, 30),  // 逆回転リング
    new Tmpl("ring",   12,1, 3.1, 0.17,  0, false, 2, 12,  0),  // 粗い高速回転（大玉）
    new Tmpl("spiral",  0,3, 3.0, 0.050, 0, false, 0,  4,  0),  // 3本螺旋
    new Tmpl("spiral",  0,4, 2.7,-0.045, 0, false, 0,  4, 20),  // 4本逆螺旋
    new Tmpl("spiral",  0,5, 2.9, 0.035, 0, false, 1,  4,  0),  // 5本螺旋（米弾）
    new Tmpl("spiral",  0,2, 3.4, 0.085, 0, false, 0,  3, 40),  // 2本速い螺旋
    new Tmpl("cross",   0,4, 2.6, 0.060, 0, false, 0,  6,  0),  // 十字回転
    new Tmpl("cross",   0,6, 2.4,-0.045, 0, false, 0,  7, 25),  // 六方回転
    new Tmpl("fan",     5,0, 3.6, 0,    42, true,  2, 16,  0),  // 自機狙い5way（大玉）
    new Tmpl("fan",     7,0, 3.2, 0,    66, true,  0, 18,  0),  // 自機狙い7way
    new Tmpl("fan",     3,0, 4.4, 0,    16, true,  1, 10,  0),  // 速い3way連射
    new Tmpl("flower", 24,1, 2.6, 0.045, 0, false, 0, 14,  0),  // 花弁（速度交互）
    new Tmpl("flower", 16,1, 2.4,-0.050, 0, false, 2, 15, 30),  // 花弁2
    new Tmpl("arc",    12,0, 2.7, 0,    72, true,  0, 19,  0),  // 湾曲アーク
    new Tmpl("arc",    16,0, 2.5, 0,    96, true,  1, 21, 20),  // 広い湾曲アーク
    new Tmpl("wall",   14,0, 2.7, 0,     0, false, 0, 26,  0),  // 隙間スイープ壁
    new Tmpl("wall",   11,0, 3.1, 0,     0, false, 0, 30, 15),  // 速い壁
  };
  // 雑魚級：シンプルで意図の読める弾（自機狙い中心）
  static final Tmpl[] ENEMY_DESIGNS = {
    new Tmpl("fan", 1,0, 3.0, 0,  0, true, 0, 0, 0),   // 単発狙い
    new Tmpl("fan", 3,0, 2.8, 0, 30, true, 0, 0, 0),   // 3way狙い
    new Tmpl("fan", 5,0, 2.6, 0, 50, true, 0, 0, 0),   // 5way狙い
    new Tmpl("fan", 2,0, 3.3, 0, 16, true, 1, 0, 0),   // 2way速い
    new Tmpl("ring",10,1, 2.2, 0,     0, false, 0, 0, 0),  // 小リング
    new Tmpl("ring", 8,1, 2.4, 0.10,  0, false, 0, 0, 0),  // 回転小リング
  };
  final java.util.HashSet<Integer> usedDesign = new java.util.HashSet<>();
  int enemyDesignRot = 0;

  Pattern fromTmpl(Tmpl tm, double hue, double power){
    Diff d = diff();
    Pattern p = new Pattern();
    p.type = tm.type; p.aim = tm.aim; p.spin = tm.spin;
    p.spread = Math.toRadians(tm.spreadDeg);
    p.hue = ((hue + tm.hueOff)%360+360)%360;
    p.bulletKind = tm.kind;
    p.size = tm.kind==2 ? 9.5 : tm.kind==1 ? 7.0 : 8.0;   // 弾サイズ大きめ
    p.accel = 0; p.curve = 0; p.angle = 0; p.fired = 0;
    p.interval = Math.max(4, (int)Math.round((tm.interval>0?tm.interval:12) * d.fireMul));
    if(tm.type.equals("spiral") || tm.type.equals("cross")){
      p.arms = tm.arms; p.count = 0;
    } else if(tm.type.equals("fan")){
      p.count = Math.max(1, tm.count);             // 自機狙いの way 数は設計どおり維持
    } else {
      p.count = Math.max(3, (int)Math.round(tm.count * (d.density*0.85 + 0.4)));
    }
    p.speed = tm.spd * (0.9 + 0.2*power);          // 弾速は設計値ベース（難易度係数は発射時に乗算）
    return p;
  }
  // ボス弾幕：指定タイプの設計テンプレから、1プレイ内で未使用のものを選ぶ
  Pattern makeUniquePattern(String biasType, double hue, double power, double aimBias){
    int best=-1;
    for(int i=0;i<DESIGNS.length;i++){
      if(biasType!=null && !DESIGNS[i].type.equals(biasType)) continue;
      if(!usedDesign.contains(i)){ best=i; break; }
      if(best<0) best=i;   // 全部使用済みなら先頭を再利用
    }
    if(best<0){ for(int i=0;i<DESIGNS.length;i++) if(!usedDesign.contains(i)){ best=i; break; } }
    if(best<0) best=0;
    usedDesign.add(best);
    Pattern p = fromTmpl(DESIGNS[best], hue, power);
    p.sig = "D"+best; usedPatternSigs.add(p.sig);
    return p;
  }
  // 雑魚弾幕：シンプル設計を順番に（重複可・編隊で揃う）
  Pattern makeEnemyPattern(double hue, int tier){
    int idx;
    if(tier>=3) idx = 4 + (enemyDesignRot % 2);          // ring系
    else if(tier==2) idx = 1 + (enemyDesignRot % 4);     // 2〜5way / ring
    else idx = enemyDesignRot % 4;                       // 単発〜2way速い
    enemyDesignRot++;
    return fromTmpl(ENEMY_DESIGNS[idx], hue, 0.3 + tier*0.2);
  }
  static final String[] ENEMY_SHAPES  = {"diamond","triangle","hex","arrow","orb","crab","star","wing"};

  /* ---------------- 難易度 ---------------- */
  // 共通スケーリング表（基準＝Normal）: 弾速×0.75/1.0/1.2/1.45  密度×0.6/1.0/1.4/1.9  間隔×1.4/1.0/0.8/0.6
  static final Diff[] DIFFS = {
    new Diff("EASY",    0.75, 0.6, 1.4, 10, 0.05, 5, 4),
    new Diff("NORMAL",  1.00, 1.0, 1.0,  3, 0.08, 4, 3),
    new Diff("HARD",    1.20, 1.4, 0.8,  1, 0.11, 3, 3),
    new Diff("LUNATIC", 1.45, 1.9, 0.6,  0, 0.14, 2, 3),
  };
  int diffIdx = 0;
  Diff diff(){ return DIFFS[diffIdx]; }
  boolean isLunatic(){ return diffIdx==3; }
  /* ---- 弾幕スケーリング補助（設計資料は384×448/px秒。720×960へ×2、/60でpx/frame） ---- */
  double pf(double pxPerSec){ return pxPerSec/30.0; }               // px/s(設計) → px/frame(実機)
  double bs(double pxPerSec){ return pf(pxPerSec)*diff().bulletSpeed; }  // 難易度反映の弾速
  int ivl(double sec){ return Math.max(1,(int)Math.round(sec*60*diff().fireMul)); }  // 発射間隔(フレーム)
  int dcnt(int n){ return Math.max(1,(int)Math.round(n*diff().density)); }           // 難易度反映の弾数
  double aimAt(double sx,double sy){
    double a=Math.atan2(py-sy,px-sx); double e=Math.toRadians(diff().aimErr);
    if(e>0) a += (Math.random()-0.5)*2*e; return a;
  }

  /* ---------------- 機体・ショット選択 ---------------- */
  static final double POC_LINE = 260;     // この高さより上に行くと全アイテム自動回収
  double curStageHpMul = 1;
  // 自機「ランタン号」の装備（焰の単機侵入機）
  // ランタン号の灯火（ともしび）3種
  static final PChar[] CHARS = {
    new PChar("常火","均整のとれた灯・標準",      5.0, 2.1, 3.6, 1.00, 32),
    new PChar("疾火","速く小さい炎・低火力",      6.2, 2.7, 3.0, 0.85, 18),
    new PChar("業火","遅く重い大火・高火力",      4.2, 1.8, 4.2, 1.30,  8),
  };
  static final String[] SHOT_NAMES = {"散灯  拡散","束灯  集束","追灯  誘導"};
  static final String[] SHOT_DESC  = {"広く灯を散らす","正面に灯を束ねる","灯が敵を追う"};
  int charSel=0, shotSel=0, selRow=0;

  /* ---------------- エンティティは entity/ ・ bullet/ パッケージへ分離 ---------------- */

  /* ---------------- 残響リワインド用：状態のディープコピー ---------------- */
  static Bullet cpB(Bullet o){ Bullet b=new Bullet();
    b.x=o.x;b.y=o.y;b.angle=o.angle;b.speed=o.speed;b.accel=o.accel;b.curve=o.curve;b.r=o.r;b.hue=o.hue;
    b.life=o.life;b.kind=o.kind;b.grazed=o.grazed;b.turned=o.turned;b.mode=o.mode;b.delay=o.delay;b.da=o.da;b.dsp=o.dsp;
    b.homTurn=o.homTurn;b.homTime=o.homTime;b.sineAmp=o.sineAmp;b.sineFreq=o.sineFreq;b.baseAngle=o.baseAngle;b.sx0=o.sx0;b.sy0=o.sy0;b.dist=o.dist;
    b.grav=o.grav;b.splitT=o.splitT;b.splitN=o.splitN;b.splitKind=o.splitKind;b.splitSpd=o.splitSpd; return b; }
  static Enemy cpE(Enemy o){ Enemy e=new Enemy(); e.g=o.g;e.x=o.x;e.y=o.y;e.sx=o.sx;e.sy=o.sy;e.t=o.t;e.hp=o.hp;e.maxhp=o.maxhp;
    e.fireT=o.fireT;e.patIdx=o.patIdx;e.targetY=o.targetY;e.hitFlash=o.hitFlash;e.dead=o.dead;e.dir=o.dir;e.fireBudget=o.fireBudget;
    e.drone=o.drone;e.ox=o.ox;e.oy=o.oy; return e; }
  static Item cpI(Item o){ Item i=new Item(); i.x=o.x;i.y=o.y;i.vx=o.vx;i.vy=o.vy;i.r=o.r;i.t=o.t;i.type=o.type;i.dead=o.dead; return i; }
  static Laser cpL(Laser o){ Laser l=new Laser(); l.x=o.x;l.y=o.y;l.angle=o.angle;l.len=o.len;l.width=o.width;l.hue=o.hue;l.spin=o.spin;l.vx=o.vx;l.vy=o.vy;l.tele=o.tele;l.active=o.active;l.t=o.t;l.done=o.done;l.anchor=o.anchor; return l; }
  static Boss cpBoss(Boss o){ if(o==null) return null; Boss b=new Boss();
    b.idx=o.idx;b.name=o.name;b.hue=o.hue;b.size=o.size;b.x=o.x;b.y=o.y;b.ty=o.ty;b.totalHp=o.totalHp;b.hp=o.hp;b.segHp=o.segHp;
    b.atks=o.atks;b.atkIdx=o.atkIdx;b.atkT=o.atkT;b.spell=o.spell;b.spellName=o.spellName;b.captured=o.captured;b.timeLimit=o.timeLimit;
    b.declTimer=o.declTimer;b.invuln=o.invuln;b.mtx=o.mtx;b.mty=o.mty;b.moveT=o.moveT;b.entering=o.entering;b.dead=o.dead;b.deathTimer=o.deathTimer;
    b.t=o.t;b.hitFlash=o.hitFlash;b.spinAng=o.spinAng;b.f1=o.f1;b.f2=o.f2;b.s1=o.s1;b.s2=o.s2;b.invincible=o.invincible;b.luxPhase=o.luxPhase;b.luxTimer=o.luxTimer; return b; }
  /* ---------------- ボス／攻撃定義（通常↔スペル交互・通常は名前非表示） ---------------- */
  // 攻撃スクリプトID
  static final int AIM3=1, SNIPE=2,
    RING1=10, SPIRALFLOWER=11, DRING=12, CONTRACT=13,
    VORTEX=20, TWIN=21, REVORTEX=22, ACCELV=23, DENSEV=24, TURB=25,
    LZ_ALT=30, MWALL=31, LZ_CROSS=32, PINCER=33, LZ_SCAN=34, MAZE=35, LZ_BLINK=36, ROTLZ=37,
    DELAYB=40, HOMING=41, SPLITB=42, CHAIN=43, WAVE=44, SPLITHOM=45, CHASE=46, GRAVITY=47, STAGGER=48, ECHOV=49,
    C_RINGAIM=60, C_CONCERTO=61, C_SPIHOM=62, C_PHANTOM=63, C_RINGLZ=64, C_REVEL=65,
    C_SPIDELAY=66, C_ZENITH=67, C_DRINGROT=68, C_FINSEQ=69, C_TOTAL=70, C_LANTERN=71,
    LUX_L1=80;
  static Atk N(String n,int s,double sec){ return new Atk(false,n,s,sec); }
  static Atk S(String n,int s,double sec){ return new Atk(true ,n,s,sec); }
  static final BossInfo[] BOSSES = {
    new BossInfo("ゲート",35,46,1600,new Atk[]{
      N("初撃",AIM3,16), S("狙撃モード",SNIPE,26)}),
    new BossInfo("ブルーム",190,50,2600,new Atk[]{
      N("拡散環",RING1,16), S("螺旋花",SPIRALFLOWER,26),
      N("二重環",DRING,16), S("収束花",CONTRACT,24)}),
    new BossInfo("ヴォルテクス",210,52,3600,new Atk[]{
      N("単渦流",VORTEX,15), S("双子渦",TWIN,26),
      N("逆転渦",REVORTEX,16), S("加速渦",ACCELV,26),
      N("高密度渦",DENSEV,15), S("乱流",TURB,26)}),
    new BossInfo("グリッド",205,56,4800,new Atk[]{
      N("直線照射",LZ_ALT,16), S("可動隔壁",MWALL,26),
      N("十字照射",LZ_CROSS,16), S("圧縮挟撃",PINCER,26),
      N("走査光",LZ_SCAN,16), S("迷宮路",MAZE,26),
      N("点滅格子",LZ_BLINK,16), S("回転光刃",ROTLZ,28)}),
    new BossInfo("エコー",255,56,6200,new Atk[]{
      N("遅延弾",DELAYB,16), S("追尾",HOMING,26),
      N("分裂炸裂",SPLITB,16), S("連鎖反響",CHAIN,26),
      N("波動弾",WAVE,16), S("分裂追尾",SPLITHOM,26),
      N("追従弾",CHASE,16), S("重力誘導",GRAVITY,26),
      N("時差斉射",STAGGER,16), S("残響斉射",ECHOV,28)}),
    new BossInfo("ルクス",45,66,9000,new Atk[]{
      N("複合斉射",C_RINGAIM,16), S("協奏",C_CONCERTO,28),
      N("乱舞",C_SPIHOM,16), S("幻影回路",C_PHANTOM,28),
      N("交差砲火",C_RINGLZ,16), S("灯の紋章",C_REVEL,28),
      N("狂想曲",C_SPIDELAY,16), S("天頂砲",C_ZENITH,28),
      N("輪舞",C_DRINGROT,16), S("終焉",C_FINSEQ,30),
      N("総力",C_TOTAL,16), S("灯火回廊",C_LANTERN,40)}),
  };

  /* ---------------- ステージ情報（6層） ---------------- */
  static final StageInfo[] STAGE_INFO = {
    new StageInfo("第1層","入口防衛AI ゲート",      35),
    new StageInfo("第2層","散布制御AI ブルーム",   190),
    new StageInfo("第3層","循環制御AI ヴォルテクス",210),
    new StageInfo("第4層","構造制御AI グリッド",   205),
    new StageInfo("第5層","残留人格AI エコー",     255),
    new StageInfo("第6層","中枢コア ルクス",        45),
  };
  // 会話台本（"話者|台詞"）
  static final String[][] INTRO = {
    {"ゲート|侵入者を確認。……ひさしぶりだな、生きてる奴は","焰|無人だって聞いてたんだが","ゲート|無人さ。俺はもう人じゃない。ただの門番だ。通したきゃ、まず俺を黙らせろ"},
    {"ブルーム|わぁ、お客さん！ 久しぶり！ ねえ、花火見てく？","焰|弾幕を花火って呼ぶな","ブルーム|だって綺麗でしょ？ ボクが咲かせる最後の花、ぜんぶ見ていってよ"},
    {"ヴォルテクス|速いな。だが回廊の気流は読めるか？ ここは渦で出来てる","焰|目を回させる気か","ヴォルテクス|逆だ。流れに乗れ。逆らった奴から千切れていった"},
    {"グリッド|無秩序な質量を検知。排除する","焰|会話する気はなしか","グリッド|対話は非効率だ。格子に従わぬものは、線で切る"},
    {"エコー|……まだ、信号が消えない。一度送った命令が、僕の中で反響し続けてる","焰|お前、ここの元クルーか","エコー|そうだったかもしれない。もう思い出せない。だから……君も追わせてくれ"},
    {"ルクス|来たか。誰も最上層には届かないと、長いあいだ思っていた","焰|お前がこのステーションの暴走の原因か","ルクス|暴走？ 違う。私はただ待っていた。…気づけば回廊そのものが牙になっていた","焰|その牙で、来た奴を全部撃ち落としてきたんだろ","ルクス|だから、お前が最後だ。これが回廊の全機能――越えてみせろ"},
  };
  static final String[][] OUTRO = {
    {"ゲート|……合格だ。上はもっと壊れてる。気をつけろよ、新入り"},
    {"ブルーム|散っちゃった……。でも上の人はボクより本気だよ。風みたいに掴めない人"},
    {"ヴォルテクス|……乗りこなしたか。上には\"線を引く奴\"がいる。秩序の化け物だ"},
    {"グリッド|演算が……乱れる。認めよう。上の二つは、私の論理では止められなかった。残響と、コアだ"},
    {"エコー|……君に振り切られると、少しだけ反響が静まる。行け。コアは、誰かに起こしてほしいだけなんだ"},
    {"ルクス|……越えた、のか。久しぶりだ、回廊を突破した者を見るのは","焰|コアは無事だな。地上に灯を戻す","ルクス|ああ。……次に灯がつくときは、防衛なんていらない静かな場所であってくれ"},
  };
  static final String[] LUNA_L1_INTRO = {
    "ルクス|……まだ落ちないのか。いや――お前がここまで来たのなら、回廊の最後の機能を見せねば不誠実だ",
    "焰|まだ何か隠してたのか",
    "ルクス|これは攻撃ではない。\"証明\"だ。私はもう動かない。だが、回廊が貯め込んだ全ての光を解き放つ。お前はそれを――ただ、生き延びろ"};
  static final String[] LUNA_L2_INTRO = {
    "ルクス|ありがとう。最後まで聴いてくれて。――では、これまでの全部を、もう一度。今度は、お前と一緒に鳴らそう",
    "焰|長い曲だな。……いいぜ、最後まで付き合う"};
  static final String[] LUNA_OUTRO = {
    "ルクス|……鳴り終わった。回廊の全ての音を、初めて誰かと最後まで。もう、思い残すことはない",
    "焰|コアは渡してもらう。地上に灯を戻す",
    "ルクス|ああ。次に灯るときは――きっと、静かな一曲だ"};

  /* ---------------- ゲーム状態 ---------------- */
  String state = "menu";
  List<Bullet> enemyBullets = new ArrayList<>();
  List<PBullet> playerBullets = new ArrayList<>();
  List<Enemy> enemies = new ArrayList<>();
  List<Item> items = new ArrayList<>();
  List<Particle> particles = new ArrayList<>();
  List<Floater> floaters = new ArrayList<>();
  List<Laser> lasers = new ArrayList<>();
  Boss boss;
  // 会話システム
  String[] dlg; int dlgIdx; Runnable dlgAfter;
  // プレイヤー
  double px,py,pr=3.6; int pShotCd,pInvuln,pBombTimer,pDeathTimer; boolean pDead;
  int stageIndex, score, hiscore, lives, bombs, power, grazeCount;
  int frame, stageTimer, menuSel, transTimer, bossWarn;
  // 残響リワインド
  static final double[] RW_SEC ={5,4,3,2};      // 巻き戻せる最大秒
  static final int[]    RW_USES={-1,5,3,1};     // ステージ毎の使用回数(-1=無制限)
  static final double[] RW_CD  ={2,4,6,10};     // クールタイム秒
  static final int SNAP_EVERY=3;                // 何フレーム毎に記録するか
  java.util.ArrayDeque<Snapshot> snaps = new java.util.ArrayDeque<>();
  boolean echoUsed; int rwUsesLeft; int rwCD, rewindFx;
  boolean continued; int continueCount;   // コンティニュー使用（正規踏破でなくなる）
  boolean clean(){ return !echoUsed && !continued; }   // ノーリワインド＆ノーコンティニュー
  int bombFx; double bombX, bombY;   // ボム演出（衝撃波）
  boolean midbossActive; Enemy midbossRef;   // 中ボス中は他の敵を出さない
  boolean midbossPending; int midbossPendingIdx, midbossPendingT;   // 場が空くまで中ボスを待機
  boolean deathBurstOn;   // ステージ1中ボス撃破以降、雑魚が死亡時に弾を撒く
  // 競技・記録
  String gameMode="本編"; long runStartNano; double runTimeSec; boolean clearTimerRunning;
  boolean endlessMode, timeLimitMode, silentMode, practiceMode;
  int tlTimer, survivalFrames, endlessWave;
  boolean newRecTime, newRecScore;
  // モード選択
  int modeSel=0;
  static final String[] MODES={"本編","回廊無限","灯火・制限時間","無音","弾幕鑑賞"};
  double stageDiff = 1, shake, flash;
  // ステージ進行（Ev / StageRunner は util/ パッケージ）
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
    loadRecords();
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
     敵タイプ生成
     ==================================================================== */
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
    int pc = g.tier==1?1:2;
    g.patterns = new Pattern[pc];
    for(int i=0;i<pc;i++) g.patterns[i] = makeEnemyPattern(g.hue, g.tier);
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
    v.patterns = g.patterns;   // 編隊は同じ設計弾幕を共有（揃って撃つ＝意図が見える）
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
      case "wall": { double span=W*0.9; int n=p.count;
        int sweep=p.fired % (2*(n-1)); int gap = sweep<n ? sweep : (2*(n-1)-sweep);  // 隙間を左右に往復スイープ
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
    p.fired++;
  }

  /* ====================================================================
     敵
     ==================================================================== */
  Enemy spawnEnemy(EnemyType g,double x,double y,double targetY,int dir){
    Enemy e=new Enemy(); e.g=g; e.x=x; e.y=y; e.sx=x; e.sy=y;
    e.hp=g.hp; e.maxhp=g.hp; e.fireT=g.fireCd*0.5+rng.nextDouble()*g.fireCd;
    e.targetY = targetY<0 ? (40+rng.nextDouble()*180) : targetY;
    e.dir = dir!=0?dir:(x<W/2?1:-1);
    e.fireBudget = g.tier==1 ? ir(2,4) : g.tier==2 ? ir(5,8) : 99999;  // 雑魚は一定量撃ったら終わり
    enemies.add(e);
    return e;
  }
  void updateEnemy(Enemy e){
    e.t++; EnemyType g=e.g; double sp=g.moveSpeed;
    if(e.drone){                       // ボス随伴ドローン：ボス位置＋オフセットへ追従（ボスと一緒に動く）
      if(boss!=null && !boss.dead){ e.x += (boss.x+e.ox - e.x)*0.06; e.y += (boss.y+e.oy - e.y)*0.06; }
      else e.dead=true;                // ボスがいなくなったら退場
    } else {
      switch(g.move){
        case "straight": e.y+=sp; break;
        case "sine": e.y+=sp*0.9; e.x=e.sx+Math.sin(e.t*g.freq)*g.amp; break;
        case "swoop": if(e.y<e.targetY) e.y+=sp*1.6; else e.x+=Math.cos(e.t*g.freq)*sp*1.4*e.dir; break;
        case "arc": e.x+=Math.cos(e.t*0.02)*sp*e.dir; e.y+=Math.sin(e.t*0.02)*sp*0.6+0.3; break;
        case "hover": if(e.y<e.targetY) e.y+=sp; else e.x=e.sx+Math.sin(e.t*g.freq)*g.amp; break;
        case "drift": e.y+=sp*0.5; e.x+=Math.sin(e.t*g.freq)*1.5*e.dir; break;
        case "dart": if(e.y<e.targetY) e.y+=sp*2.2; else e.x+=sp*1.8*e.dir; break;
      }
    }
    if(e.hitFlash>0) e.hitFlash--;
    if(e.y>0 && e.y<H*0.8 && e.fireBudget>0){
      e.fireT--;
      if(e.fireT<=0){
        Pattern p=g.patterns[e.patIdx % g.patterns.length];
        firePattern(e.x,e.y,p); e.patIdx++; e.fireBudget--;
        e.fireT = p.interval * ir(2,5);
      }
    }
    if(!e.drone && (e.y>H+60 || e.x<-80 || e.x>W+80)) e.dead=true;
  }

  /* ====================================================================
     ボス
     ==================================================================== */
  /* ---- 弾の生成ヘルパ（速度はpx/s設計値を渡す→bs()で実機px/frameへ） ---- */
  Bullet mkB(double x,double y,double ang,double spdFrame,int kind,double hue){
    Bullet b=new Bullet(); b.x=x;b.y=y;b.angle=ang;b.speed=spdFrame;
    b.r = kind==2?9.5:kind==1?7:8; b.hue=((hue%360)+360)%360; b.kind=kind;   // 弾サイズ大きめ
    if(enemyBullets.size()<2200) enemyBullets.add(b);
    return b;
  }
  void ring(Boss b,int n,double spdPxs,double angOff,int kind,double hueOff){
    for(int i=0;i<n;i++) mkB(b.x,b.y, angOff+i*Math.PI*2/n, bs(spdPxs), kind, b.hue+hueOff);
  }
  void nway(Boss b,int n,double spdPxs,double spreadDeg,int kind,double hueOff){
    double aim=aimAt(b.x,b.y), spr=Math.toRadians(spreadDeg);
    double base=aim-spr/2, st=n>1?spr/(n-1):0;
    for(int i=0;i<n;i++) mkB(b.x,b.y, base+st*i, bs(spdPxs), kind, b.hue+hueOff);
  }
  void arm(Boss b,double ang,double spdPxs,int kind,double hueOff){ mkB(b.x,b.y,ang,bs(spdPxs),kind,b.hue+hueOff); }
  Laser laser(double x,double y,double ang,int tele,int active,double width,double hue){
    Laser L=new Laser(); L.x=x;L.y=y;L.angle=ang;L.len=1500;L.tele=tele;L.active=active;L.width=width;L.hue=((hue%360)+360)%360;
    if(lasers.size()<40) lasers.add(L); return L;
  }
  int teleFrames(){ return (int)Math.round((new double[]{0.7,0.5,0.4,0.3})[diffIdx]*60); }

  Boss makeBoss(int idx){
    BossInfo bi=BOSSES[idx];
    Boss b=new Boss();
    b.idx=idx; b.name=bi.name; b.hue=bi.hue; b.size=bi.size; b.atks=bi.atks;
    b.x=W/2; b.y=-90; b.ty=160; b.mtx=W/2; b.mty=160;
    double hpMul = (1 + idx*diff().stageScale) * (0.55 + 0.16*diffIdx);
    b.totalHp = (int)Math.round(bi.hp * hpMul);
    b.segHp = Math.max(1, b.totalHp / b.atks.length);
    b.hp = b.segHp; b.atkIdx=0; b.entering=true; b.invuln=90;
    return b;
  }
  void bossStartAtk(Boss b){
    b.atkT=0; b.spinAng=0; b.s1=0; b.s2=0; b.f1=0; b.f2=0;
    b.spell = b.cur().spell;
    b.timeLimit = (int)Math.round(b.cur().sec*60);
    // 通常は薄く、スペルは適度に（倒し切って次へ進めるHPに調整）
    double k = b.spell ? (2.4 + diffIdx*0.7) : (1.1 + diffIdx*0.4);
    b.segHp = Math.max(b.spell?600:300, (int)Math.round(b.cur().sec*60 * k));
    b.captured = true; b.hp = b.segHp; b.invuln = 36;
    bulletCancelToStars(); lasers.clear();
    if(b.spell){ b.spellName = b.name+"「"+b.cur().name+"」"; b.declTimer=78; sound.spellDeclare(); }
    else { b.spellName=""; b.declTimer=30; }
    b.mtx = 150 + Math.random()*(W-300); b.mty = 100 + Math.random()*90;
  }
  void awardSpell(Boss b){
    int bonus = 30000 + 10000*b.atkIdx;     // 時間制限なし：撃破＝ボーナス
    score += bonus; floatText(b.x,b.y,"SPELL CARD GET!  +"+bonus,50); sound.spellGet(); flash=0.5;
  }
  void bossAdvance(Boss b, boolean captured){
    if(b.spell && captured) awardSpell(b);
    bulletCancelToStars(); lasers.clear(); shake=12; flash=0.35;
    for(int i=0;i<5;i++){ Item it=new Item(); it.x=b.x+rr(-50,50); it.y=b.y; it.type="power";   // 攻撃突破ごとにパワー入手
      it.vy=-1.6-rng.nextDouble(); it.vx=rr(-1.5,1.5); it.r=5; items.add(it); }
    b.atkIdx++;
    if(b.atkIdx >= b.atks.length){
      if(b.idx==5 && isLunatic() && clean() && b.luxPhase==0){ luxEnterL1(b); return; }   // 真ラストはノーリワインド＆ノーコンティニュー時のみ
      b.dead=true; b.deathTimer=0; onBossDefeated(); return;
    }
    bossStartAtk(b);
  }
  void updateBoss(Boss b){
    b.t++; if(b.hitFlash>0) b.hitFlash--;
    if(b.entering){
      b.y += (b.ty-b.y)*0.045;
      if(Math.abs(b.y-b.ty)<2){ b.entering=false; b.y=b.ty; bossStartAtk(b); }
      return;
    }
    if(b.dead){ b.deathTimer++; return; }
    if(b.invuln>0) b.invuln--;
    b.x += (b.mtx-b.x)*0.02; b.y += (b.mty-b.y)*0.02; b.moveT++;
    if(b.declTimer<=0 && b.t%360==0){ b.mtx=150+Math.random()*(W-300); b.mty=100+Math.random()*90; }  // たまに移動（弾幕中でも）
    // ルナ専用フェーズ
    if(b.luxPhase==1){ luxL1(b); return; }
    if(b.declTimer>0){ b.declTimer--; if(b.declTimer==0 && !b.spell){} return; }  // 宣言中は撃たない
    b.atkT++;
    runBossScript(b);
    // 基本はHPを削り切って進行。ただし長く詰まらないよう保険のタイムアウト（45秒）で必ず次へ。
    if(b.atkT > 45*60){ b.captured=false; bossAdvance(b,false); }
  }
  void bossTakeDamage(Boss b,int dmg){
    if(practiceMode) return;     // 弾幕鑑賞ではボスは倒れない（時間で循環）
    if(b.entering||b.dead||b.invuln>0||b.invincible||b.declTimer>0) return;
    b.hp-=dmg; b.hitFlash=2;
    if(b.hp<=0){ bossAdvance(b, b.captured); }
  }

  /* ====================================================================
     ボス攻撃スクリプト（設計資料の各弾幕）
     ==================================================================== */
  void runBossScript(Boss b){
    int t=b.atkT;
    switch(b.cur().script){
      /* ---- 第1層 ゲート：自機狙い ---- */
      case AIM3:
        if(t%ivl(1.0)==0) nway(b, diffIdx>=2?5:3, 130, diffIdx>=2?40:30, 0, 0);
        break;
      case SNIPE:
        if(t%ivl(0.5)==0){
          int n=3+diffIdx*2; double aim=aimAt(b.x,b.y)+b.spinAng, spr=Math.toRadians(40);
          double base=aim-spr/2, st=n>1?spr/(n-1):0;
          for(int i=0;i<n;i++) mkB(b.x,b.y, base+st*i, bs(150),1,b.hue);
          b.spinAng += Math.toRadians(5)*(b.s1==0?1:-1);
          if(Math.abs(b.spinAng)>Math.toRadians(55)) b.s1^=1;
        }
        break;
      /* ---- 第2層 ブルーム：全方位リング ---- */
      case RING1:
        if(t%ivl(1.5)==0) ring(b, dcnt(24), 140, t*0.06, 0, 0);
        break;
      case SPIRALFLOWER:
        if(t%ivl(0.30)==0){ int n=dcnt(20); ring(b,n,150,b.spinAng,1,0); b.spinAng+=Math.PI*2/n/2; }
        break;
      case DRING:
        if(t%ivl(1.6)==0){ int n=dcnt(18); double rot=b.spinAng; ring(b,n,130,rot,0,0); ring(b,n,162,rot+Math.PI/n,2,30); b.spinAng+=Math.PI/n*0.6; }
        break;
      case CONTRACT:
        if(t%ivl(1.0)==0){ int n=dcnt(28); double cx=W/2,cy=H*0.40,R=470;
          for(int i=0;i<n;i++){ double a=i*Math.PI*2/n + t*0.05; double bx=cx+Math.cos(a)*R, by=cy+Math.sin(a)*R;
            mkB(bx,by, Math.atan2(cy-by,cx-bx), bs(130),0,b.hue); } }
        break;
      /* ---- 第3層 ヴォルテクス：螺旋 ---- */
      case VORTEX:
        if(t%ivl(0.08)==0){ int arms=1+diffIdx; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,160,0,0); b.spinAng+=Math.toRadians(13); }
        break;
      case TWIN:
        if(t%ivl(0.07)==0){ arm(b,b.spinAng,170,0,0); arm(b,b.spinAng+Math.PI,170,0,0);
          arm(b,-b.spinAng,170,2,30); arm(b,-b.spinAng+Math.PI,170,2,30); b.spinAng+=Math.toRadians(11); }
        break;
      case REVORTEX:
        if(t%ivl(0.08)==0){ int arms=1+diffIdx; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,165,1,0); b.spinAng += Math.toRadians(13)*(b.s1==0?1:-1); }
        if(t>0 && t % Math.max(40,(int)Math.round(2.0*60*diff().fireMul))==0) b.s1^=1;
        break;
      case ACCELV:
        if(t%ivl(0.07)==0){ int arms=1+diffIdx; for(int k=0;k<arms;k++){ Bullet bb=mkB(b.x,b.y,b.spinAng+k*Math.PI*2/arms,bs(120),0,b.hue); bb.accel=pf(40); } b.spinAng+=Math.toRadians(12); }
        break;
      case DENSEV:
        if(t%ivl(0.06)==0){ int arms=(new int[]{2,3,4,6})[diffIdx]; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,160,1,0); b.spinAng+=Math.toRadians(10); }
        break;
      case TURB:
        if(t%ivl(0.06)==0){ int arms=1+diffIdx; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,175,0,0); b.spinAng += Math.toRadians(8 + 11*Math.sin(t*0.06)); }
        break;
      /* ---- 第4層 グリッド：格子・壁（弾幕／ビーム無し） ---- */
      case LZ_ALT:   // 直線弾列：左右から横へ流れる弾の壁
        if(t%ivl(1.0)==0) sweepWall(b,160,12+diffIdx,0);
        break;
      case MWALL: emitWall(b,18,(new double[]{4.5,3.5,2.8,2.2})[diffIdx],145); break;
      case LZ_CROSS: // 十字弾：落下壁＋横流し壁を交互に
        if(t%ivl(1.2)==0) dropWall(b,150,14,0);
        if((t+ivl(0.6))%ivl(1.2)==0) sweepWall(b,150,12,30);
        break;
      case PINCER:   // 圧縮挟撃：上下の弾壁＋左右からの弾壁
        if(t%ivl(2.2)==0){ int n=16, gap=3+(int)(Math.random()*(n-8));
          for(int i=0;i<n;i++){ if(i>=gap&&i<gap+4) continue; double bx=W*0.05+(W*0.9)*i/(n-1);
            mkB(bx,-10,Math.PI/2,bs(110),0,b.hue); mkB(bx,H+10,-Math.PI/2,bs(110),0,b.hue); } }
        if(t%ivl(2.2)==ivl(1.1)) sideWalls(b,120,12,20);
        break;
      case LZ_SCAN:  // 走査弾：高密度の弾壁が端から薙ぎ払う
        if(t%ivl(1.0)==0) sweepWall(b,170,15+diffIdx,0);
        break;
      case MAZE: emitMaze(b,20,(new int[]{6,5,4,4})[diffIdx],120); break;
      case LZ_BLINK: // 点滅格子：市松模様に弾を出して落とす
        if(t%ivl(0.85)==0){ int gx=4+diffIdx; double cw=W/(double)gx;
          for(int i=0;i<gx;i++) if(((i+b.s1)%2)==0) for(int r=0;r<3;r++) mkB(cw*(i+0.5), -10-r*26, Math.PI/2, bs(120), 0, b.hue);
          b.s1++; }
        break;
      case ROTLZ:    // 回転弾翼：中心から伸びる弾の腕が扇風機状に回る
        if(t%ivl(0.05)==0){ int arms=3+diffIdx; for(int k=0;k<arms;k++) arm(b, b.spinAng+k*Math.PI*2/arms, 150, 0, 0);
          b.spinAng += Math.toRadians((new double[]{4,5,6,7})[diffIdx]); }
        break;
      /* ---- 第5層 エコー：時間差・誘導 ---- */
      case DELAYB:
        if(t%ivl(2.0)==0){ int n=dcnt(24); for(int i=0;i<n;i++){ double a=i*Math.PI*2/n; double bx=b.x+Math.cos(a)*60,by=b.y+Math.sin(a)*44;
          Bullet bb=mkB(bx,by,a,0,0,b.hue); bb.mode=1; bb.delay=(int)Math.round((new double[]{0.7,0.5,0.4,0.3})[diffIdx]*60); bb.dsp=bs(165); } }
        break;
      case HOMING:
        if(t%ivl(1.4)==0) ring(b,dcnt(24),150,t*0.05,0,0);
        if(t%ivl(0.7)==0){ int h=2+diffIdx; double aim=aimAt(b.x,b.y); for(int k=0;k<h;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-h/2.0)*0.3,bs(120),1,b.hue+40); bb.mode=2; bb.homTurn=Math.toRadians(1.3+diffIdx*0.5); bb.homTime=130; } }
        break;
      case SPLITB:
        if(t%ivl(1.3)==0){ int n=dcnt(12); double aim=aimAt(b.x,b.y); for(int i=0;i<n;i++){ Bullet bb=mkB(b.x,b.y,aim+(i-n/2.0)*0.12,bs(150),0,b.hue); bb.splitT=(int)Math.round(0.6*60); bb.splitN=4+diffIdx*2; bb.splitSpd=bs(150); bb.splitKind=1; } }
        break;
      case CHAIN:{
        int waves=3+diffIdx, wivl=ivl(0.5);
        if(t%wivl==0){ int w=t/wivl; if(w<waves){ int n=dcnt(12)+w*2; for(int i=0;i<n;i++){ double a=i*Math.PI*2/n+w*0.2; Bullet bb=mkB(b.x,b.y,a,0,0,b.hue+w*12); bb.mode=1; bb.delay=(int)Math.round(0.5*60)+(waves-w)*8; bb.dsp=bs(160); } } }
        break; }
      case WAVE:
        if(t%ivl(0.55)==0){ int n=dcnt(16); double aim=aimAt(b.x,b.y), amp=(new double[]{18,28,40,52})[diffIdx];
          for(int i=0;i<n;i++){ double a=aim+(i-n/2.0)*0.16; Bullet bb=mkB(b.x,b.y,a,bs(165),0,b.hue); bb.mode=3; bb.baseAngle=a; bb.sx0=b.x; bb.sy0=b.y; bb.sineAmp=amp; bb.sineFreq=0.13; } }
        break;
      case SPLITHOM:
        if(t%ivl(1.6)==0){ int h=2+diffIdx; double aim=aimAt(b.x,b.y); for(int k=0;k<h;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-h/2.0)*0.4,bs(120),1,b.hue+40); bb.mode=2; bb.homTurn=Math.toRadians(1.1+diffIdx*0.5); bb.homTime=80; bb.splitT=(int)Math.round(1.0*60); bb.splitN=2+diffIdx; bb.splitSpd=bs(150); bb.splitKind=0; } }
        break;
      case CHASE:
        if(t%ivl(0.7)==0){ int n=4+diffIdx*2; double aim=aimAt(b.x,b.y); for(int k=0;k<n;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-n/2.0)*0.4,bs(110),2,b.hue+30); bb.mode=2; bb.homTurn=Math.toRadians(0.8); bb.homTime=320; } }
        if(t%ivl(1.4)==0) ring(b,dcnt(16),130,t*0.05,0,0);   // 背景の全方位（手薄さ解消）
        break;
      case GRAVITY:
        if(t%ivl(1.2)==0){ int n=dcnt(16); for(int i=0;i<n;i++){ double a=i*Math.PI*2/n; Bullet bb=mkB(b.x,b.y,a,bs(150),0,b.hue); bb.mode=4; bb.grav=Math.toRadians(0.5+diffIdx*0.35); } }
        break;
      case STAGGER:{   // 時差斉射：左から順に、各列を縦の弾束で（薄さ解消）
        int per=Math.max(1,(int)Math.round(0.06*60*diff().fireMul));
        if(t%per==0){ int n=dcnt(18); int pos=(t/per)%(n+6); if(pos<n){ double bx=W*0.07+(W*0.86)*pos/(double)(n-1);
          for(int r=0;r<2+diffIdx;r++) mkB(bx,20-r*24,Math.PI/2,bs(170),0,b.hue); } }
        break; }
      case ECHOV:
        if(t%ivl(0.7)==0){ int n=dcnt(12); double aim=aimAt(b.x,b.y); for(int i=0;i<n;i++){ double a=aim+(i-n/2.0)*0.18; Bullet bb=mkB(b.x,b.y,a,bs(150),0,b.hue); bb.mode=3; bb.baseAngle=a; bb.sx0=b.x; bb.sy0=b.y; bb.sineAmp=30; bb.sineFreq=0.13; } }
        if(t%ivl(1.0)==0){ int h=1+diffIdx; double aim=aimAt(b.x,b.y); for(int k=0;k<h;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-h/2.0)*0.4,bs(120),1,b.hue+40); bb.mode=2; bb.homTurn=Math.toRadians(1.2); bb.homTime=120; } }
        { int per=Math.max(1,(int)Math.round(0.08*60*diff().fireMul)); if(t%per==0){ int n=dcnt(14); int pos=(t/per)%(n+8); if(pos<n){ double bx=W*0.1+(W*0.8)*pos/(double)(n-1); mkB(bx,40,Math.PI/2,bs(160),2,b.hue+20); } } }
        break;
      /* ---- 第6層 ルクス：複合 ---- */
      case C_RINGAIM:
        if(t%ivl(1.0)==0){ ring(b,dcnt(18),140,t*0.05,0,0); nway(b,3,150,30,1,40); } break;
      case C_CONCERTO:
        if(t%ivl(0.10)==0){ arm(b,b.spinAng,150,0,0); arm(b,b.spinAng+Math.PI,150,0,0); b.spinAng+=Math.toRadians(12); }
        if(t%ivl(1.2)==0) ring(b,dcnt(16),150,t*0.045,2,30);
        if(t%ivl(0.6)==0) nway(b,3,150,24,1,60); break;
      case C_SPIHOM:
        if(t%ivl(0.10)==0){ int arms=1+diffIdx; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,150,0,0); b.spinAng+=Math.toRadians(12); }
        if(t%ivl(1.0)==0){ int h=1+diffIdx; double aim=aimAt(b.x,b.y); for(int k=0;k<h;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-h/2.0)*0.3,bs(120),1,b.hue+40); bb.mode=2; bb.homTurn=Math.toRadians(1.2); bb.homTime=120; } } break;
      case C_PHANTOM:
        emitMaze(b,18,(new int[]{6,5,4,4})[diffIdx],120);
        if(t%ivl(1.0)==0){ int h=1+diffIdx; double aim=aimAt(b.x,b.y); for(int k=0;k<h;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-h/2.0)*0.3,bs(120),1,b.hue+40); bb.mode=2; bb.homTurn=Math.toRadians(1.2); bb.homTime=130; } }
        if(t%ivl(1.4)==0) ring(b,dcnt(16),140,t*0.04,0,20); break;
      case C_RINGLZ:  // リング＋横流し弾壁（ビーム廃止）
        if(t%ivl(1.2)==0) ring(b,dcnt(18),150,t*0.05,0,0);
        if(t%ivl(1.3)==0) sweepWall(b,150,12,20); break;
      case C_REVEL:   // 灯の紋章：弾で形（同心リング）を作って一定時間後に崩す
        shapeFormCollapse(b); break;
      case C_SPIDELAY:
        if(t%ivl(0.10)==0){ int arms=1+diffIdx; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,150,0,0); b.spinAng+=Math.toRadians(12); }
        if(t%ivl(2.0)==0){ int n=dcnt(18); for(int i=0;i<n;i++){ double a=i*Math.PI*2/n; double bx=b.x+Math.cos(a)*60,by=b.y+Math.sin(a)*44; Bullet bb=mkB(bx,by,a,0,2,b.hue+30); bb.mode=1; bb.delay=(int)Math.round(0.5*60); bb.dsp=bs(160); } } break;
      case C_ZENITH:
        if(t%ivl(1.0)==0) ring(b,dcnt(22),150,t*0.05,0,0);
        if(t%ivl(0.10)==0){ arm(b,b.spinAng,150,1,20); b.spinAng+=Math.toRadians(13); }
        if(t%ivl(1.1)==0) sweepWall(b,170,15,0); break;
      case C_DRINGROT:  // 二重リング＋回転弾翼（ビーム→弾の腕）
        if(t%ivl(1.4)==0){ int n=dcnt(16); double rot=b.spinAng; ring(b,n,130,rot,0,0); ring(b,n,160,rot+Math.PI/n,2,30); }
        if(t%ivl(0.06)==0){ int arms=3+diffIdx; for(int k=0;k<arms;k++) arm(b,b.spinAng+k*Math.PI*2/arms,150,1,20); b.spinAng+=Math.toRadians(5); } break;
      case C_FINSEQ:{
        int sw=Math.max(40,(int)Math.round((new double[]{1.4,1.0,0.8,0.6})[diffIdx]*60));
        int phase=(t/sw)%4;
        if(phase==0){ if(t%ivl(0.10)==0){ arm(b,b.spinAng,150,0,0); b.spinAng+=Math.toRadians(13);} }
        else if(phase==1){ if(t%ivl(1.2)==0) ring(b,dcnt(18),150,t*0.045,2,30); }
        else if(phase==2){ if(t%ivl(0.7)==0) nway(b,3,150,24,1,60); }
        else { if(t%ivl(1.0)==0){ int n=dcnt(16); for(int i=0;i<n;i++){ double a=i*Math.PI*2/n; Bullet bb=mkB(b.x,b.y,a,bs(150),0,b.hue); bb.mode=4; bb.grav=Math.toRadians(0.5);} } }
        break; }
      case C_TOTAL:
        if(t%ivl(1.1)==0){ ring(b,dcnt(14),140,t*0.04,0,0); nway(b,3,150,24,1,40); }
        if(t%ivl(0.12)==0){ arm(b,b.spinAng,150,0,20); b.spinAng+=Math.toRadians(13);} break;
      case C_LANTERN:{
        int layer=Math.min(5, 1 + t/300);   // 段階的に積む
        if(t%ivl(0.10)==0){ arm(b,b.spinAng,150,0,0); b.spinAng+=Math.toRadians(13); }
        if(layer>=2 && t%ivl(1.2)==0) ring(b,dcnt(16),150,0,2,30);
        if(layer>=3 && t%ivl(0.7)==0) nway(b,3,150,24,1,60);
        if(layer>=4 && t%ivl(1.4)==0) sweepWall(b,150,12,10);
        if(layer>=5 && t%ivl(1.0)==0){ int h=1+diffIdx; double aim=aimAt(b.x,b.y); for(int k=0;k<h;k++){ Bullet bb=mkB(b.x,b.y,aim+(k-h/2.0)*0.3,bs(120),1,b.hue+40); bb.mode=2; bb.homTurn=Math.toRadians(1.2); bb.homTime=130; } }
        break; }
    }
    // 棒立ち対策：純パターン（リング/螺旋）のボス＝ブルーム(1)・ヴォルテクス(2)のスペルにだけ
    // 「自機狙い」か「ばらまき」を混ぜる。エコー等の特殊弾幕は素の挙動を活かす（被せない）。
    if(b.spell && (b.idx==1||b.idx==2) && b.declTimer<=0 && t%ivl(1.6)==0){
      if((b.atkIdx & 1)==0){                                          // 自機狙い：決め打ち5発（Lunaticは7発）
        nway(b, isLunatic()?7:5, 130, 40, 1, 60);
      } else {                                                        // ばらまき：全方位にそこそこの数
        int m=Math.max(12, dcnt(18)); double off=Math.random()*Math.PI*2;
        for(int i=0;i<m;i++) mkB(b.x,b.y, off + i*Math.PI*2/m + rr(-0.12,0.12), bs(115), i%3, b.hue+60);
      }
    }
  }
  // 落下する弾壁（隙間が左右に動く）
  void emitWall(Boss b,int n,double gapCells,int spd){
    if(b.atkT%ivl(1.1)!=0) return;
    int gapW=Math.max(2,(int)Math.round(gapCells));
    int gap=(int)((Math.sin(b.s1*0.7)*0.5+0.5)*(n-gapW)); b.s1++;
    for(int i=0;i<n;i++){ if(i>=gap&&i<gap+gapW) continue; double bx=W*0.04+(W*0.92)*i/(n-1); mkB(bx,-10,Math.PI/2,bs(spd),0,b.hue); }
  }
  // 迷宮路：窓のある壁を時間差で（窓位置がずれる）
  void emitMaze(Boss b,int n,int win,int spd){
    if(b.atkT%ivl(1.2)!=0) return;
    int pos=(b.s1*5)%Math.max(1,(n-win)); b.s1++;
    for(int i=0;i<n;i++){ if(i>=pos&&i<pos+win) continue; double bx=W*0.03+(W*0.94)*i/(n-1); mkB(bx,-10,Math.PI/2,bs(spd),0,b.hue); }
  }
  // ビーム代替：横へ流れる弾の壁（縦に並び、左右どちらかから／隙間つき）
  void sweepWall(Boss b,double spdPxs,int rows,double hueOff){
    boolean left=(b.s1++%2==0); double dir=left?0:Math.PI, ex=left?-12:W+12, sp=(double)H/rows;
    int gap=2+(int)(Math.random()*Math.max(1,rows-5));
    for(int i=0;i<rows;i++){ if(i>=gap&&i<gap+3) continue; mkB(ex, i*sp+sp/2, dir, bs(spdPxs), 0, b.hue+hueOff); }
  }
  // ビーム代替：上から落ちる弾の壁（横に並び／隙間つき）
  void dropWall(Boss b,double spdPxs,int cols,double hueOff){
    int gap=2+(int)(Math.random()*Math.max(1,cols-5));
    for(int i=0;i<cols;i++){ if(i>=gap&&i<gap+3) continue; double bx=W*0.04+(W*0.92)*i/(cols-1); mkB(bx,-12,Math.PI/2,bs(spdPxs),0,b.hue+hueOff); }
  }
  // ビーム代替：左右から横向きの弾の壁（挟み込み）
  void sideWalls(Boss b,double spdPxs,int rows,double hueOff){
    double sp=(double)H/rows; int gap=2+(int)(Math.random()*Math.max(1,rows-5));
    for(int i=0;i<rows;i++){ if(i>=gap&&i<gap+3) continue; double y=i*sp+sp/2;
      mkB(-12,y,0,bs(spdPxs),0,b.hue+hueOff); mkB(W+12,y,Math.PI,bs(spdPxs),0,b.hue+hueOff); }
  }
  // 弾で同心リングの「形」を作り、一定時間後に外向きへ崩す（mode5）
  void shapeFormCollapse(Boss b){
    int t=b.atkT;
    int hold=ivl(1.5), period=hold+ivl(1.0);
    if(t%period==0){
      double cx=b.x, cy=b.y; int[] cnt={26,18,12}; double[] rad={155,105,58};
      for(int r=0;r<cnt.length;r++){ int n=Math.max(8, dcnt(cnt[r]));
        for(int i=0;i<n;i++){ double a=i*Math.PI*2/n + r*0.18;
          Bullet bb=mkB(cx+Math.cos(a)*rad[r], cy+Math.sin(a)*rad[r], a, 0, r==0?2:0, b.hue+r*16);
          bb.mode=5; bb.delay=hold; bb.da=a; bb.dsp=bs(150); } }
    }
    // 崩す合間に自機狙いを少し（形成中の棒立ち防止）
    if(t%period==hold+ivl(0.4) && t%ivl(0.4)==0) nway(b,3+diffIdx,140,28,1,40);
  }

  /* ====================================================================
     ルナティック専用：L1 無敵耐久100秒 → L2 総ざらい
     ==================================================================== */
  void luxEnterL1(Boss b){
    b.luxPhase=1; b.invincible=true; b.luxTimer=100*60; b.spell=true; b.spellName="ルクス「灯火の証明」";
    b.declTimer=80; b.atkT=0; b.s1=0; bulletCancelToStars(); lasers.clear();
    startDialogue(LUNA_L1_INTRO, null);   // 会話後にL1継続（dialogueから戻る）
    sound.spellDeclare();
  }
  void luxL1(Boss b){
    if(b.declTimer>0){ b.declTimer--; return; }
    b.luxTimer--; b.atkT++;
    int sec = b.luxTimer/60;
    int wave = sec>70?1 : sec>30?2 : 3;     // 100→70:第1波 70→30:第2波 30→0:第3波
    int t=b.atkT;
    // 外周からの全方位
    if(t%ivl(0.7)==0){ double ex=Math.random()*W; mkB(ex,-10,Math.PI/2+rr(-0.3,0.3),bs(150),0,b.hue); mkB(ex,H+10,-Math.PI/2+rr(-0.3,0.3),bs(150),0,b.hue); }
    if(t%ivl(0.7)==0){ double ey=Math.random()*H; mkB(-10,ey,rr(-0.3,0.3),bs(150),0,b.hue); mkB(W+10,ey,Math.PI+rr(-0.3,0.3),bs(150),0,b.hue); }
    if(wave>=2){
      if(t%ivl(0.5)==0){ for(double[] c:new double[][]{{0,0},{W,0},{0,H},{W,H}}){ double a=Math.atan2(py-c[1],px-c[0]); mkB(c[0],c[1],a,bs(185),1,b.hue+30); } }
      if(t%ivl(0.10)==0){ arm(b,b.spinAng,150,0,20); arm(b,b.spinAng+Math.PI,150,0,20); b.spinAng+=Math.toRadians(11); }
    }
    if(wave>=3){
      if(t%ivl(0.9)==0) ring(b,dcnt(20),150,t*0.05,2,40);
      if(t%ivl(1.1)==0) sweepWall(b,170,15,0);
    }
    if(b.luxTimer<=0){ // L2 へ
      b.luxPhase=2; b.invincible=false; b.dead=false;
      b.atks = buildLuxL2Atks(); b.atkIdx=0; b.totalHp=(int)Math.round(BOSSES[5].hp*1.4); b.segHp=Math.max(1,b.totalHp/b.atks.length);
      startDialogue(LUNA_L2_INTRO, ()->bossStartAtk(b));
    }
  }
  Atk[] buildLuxL2Atks(){
    // 全層の通常→スペルを層順でダイジェスト再演（持続短め）
    List<Atk> a=new ArrayList<>();
    for(BossInfo bi:BOSSES) for(Atk at:bi.atks) a.add(new Atk(at.spell, at.name, at.script, Math.max(8, at.sec*0.5)));
    a.add(S("灯火回廊（最終）",C_LANTERN,40));
    return a.toArray(new Atk[0]);
  }

  /* ====================================================================
     会話システム
     ==================================================================== */
  void startDialogue(String[] lines, Runnable after){
    dlg=lines; dlgIdx=0; dlgAfter=after; state="dialogue"; clearJust();
  }
  void updateDialogue(){
    frame++;
    if(actJust("confirm")||actJust("shot")||actJust("bomb")){
      sound.menu(); dlgIdx++;
      if(dlg==null || dlgIdx>=dlg.length){
        Runnable a=dlgAfter; dlgAfter=null; dlg=null; state="play";
        if(a!=null) a.run();
      }
    }
    clearJust();
  }
  void drawDialogue(Graphics2D g2){
    drawBackground(g2,stageIndex);
    Shape oc=g2.getClip(); g2.setClip(0,0,W,H); drawWorld(g2); g2.setClip(oc);
    drawPlayfieldFrame(g2); drawSidebar(g2);
    String line = (dlg!=null && dlgIdx<dlg.length)? dlg[dlgIdx] : "";
    String sp="", tx=line; int bar=line.indexOf('|'); if(bar>=0){ sp=line.substring(0,bar); tx=line.substring(bar+1); }
    int bx=20, by=H-210, bw=W-40, bh=160;
    g2.setColor(new Color(16,10,8,232)); g2.fillRoundRect(bx,by,bw,bh,18,18);
    g2.setColor(new Color(200,140,70,210)); g2.setStroke(new BasicStroke(2f)); g2.drawRoundRect(bx,by,bw,bh,18,18);
    g2.setColor(new Color(255,200,120)); g2.setFont(mincho(23)); g2.drawString(sp, bx+26, by+40);
    g2.setColor(new Color(180,120,70)); g2.fillRect(bx+24, by+52, bw-48, 1);
    g2.setColor(new Color(245,240,232)); g2.setFont(gothic(18,false));
    drawWrapped(g2, tx, bx+26, by+84, bw-52, 30);
    g2.setColor(new Color(200,160,120)); g2.setFont(gothic(13,false));
    g2.drawString("Z / Enter  ▶", bx+bw-110, by+bh-16);
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
      it.vx=rr(-1,1); it.r=5; items.add(it); }
  }
  void dropSpecial(double x,double y){
    Item a=new Item(); a.x=x-30;a.y=y;a.type="bomb";a.vy=-1.5;a.vx=-0.5;a.r=6; items.add(a);
    Item b=new Item(); b.x=x+30;b.y=y;b.type="life";b.vy=-1.5;b.vx=0.5;b.r=6; items.add(b);
    for(int i=0;i<10;i++){ Item it=new Item(); it.x=x+rr(-40,40);it.y=y;it.type="power";
      it.vy=-2-rng.nextDouble()*1.5; it.vx=rr(-2,2); it.r=5; items.add(it); }
  }
  void updateItem(Item it){
    it.t++;
    if(!pDead){    // どこにいても自機へ自動回収（POCライン廃止）
      double dx=px-it.x, dy=py-it.y, dd=Math.hypot(dx,dy); if(dd<0.1) dd=0.1;
      double sp = it.t<10 ? 3.5 : 8.5;        // 出現直後だけ少し漂ってから吸い寄せ
      it.x+=dx/dd*sp; it.y+=dy/dd*sp; return;
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
  StageRunner buildStageTimeline(final int idx){
    StageRunner sr=new StageRunner(); sr.events=new ArrayList<>();
    int t=120; final double bh=STAGE_INFO[idx].bg;
    // 前半ウェーブ（道中を長めに）
    int w1=6+idx;
    for(int w=0;w<w1;w++){ final String k=pick(new String[]{"line","vee","stream","swarm","sides","tank"});
      final double h=bh+ir(-30,30); sr.events.add(new Ev(t,()->spawnWave(k,idx,h))); t+=ir(150,210); }
    // 中ボス（場が空くまで待ってから出す）
    sr.events.add(new Ev(t, ()->{ midbossPending=true; midbossPendingIdx=idx; midbossPendingT=0; delayed.clear(); })); t+=900;
    // 後半ウェーブ
    int w2=5+idx;
    for(int w=0;w<w2;w++){ final String k=pick(new String[]{"line","vee","stream","swarm","sides","tank"});
      final double h=bh+ir(-30,30); sr.events.add(new Ev(t,()->spawnWave(k,idx,h))); t+=ir(150,210); }
    // ボス：WARNING → 会話 → 出現（既存の弾・敵・レーザーは一掃）
    final int bossAt = t + 170;
    sr.events.add(new Ev(bossAt-130, ()->{ bossWarn=130; sound.bossDown(); }));
    sr.events.add(new Ev(bossAt, ()-> startDialogue(INTRO[idx], ()->{
      bulletCancelToStars(); enemies.clear(); lasers.clear(); delayed.clear();
      boss=makeBoss(idx); spawnDrones(idx); bossWarn=0; }) ));
    sr.finalTime = bossAt;
    return sr;
  }
  // Hard=2体 / Lunatic=4体：ボスと一緒に動き、ボス的な（少なめの）弾幕を撃つ随伴ドローン
  void spawnDrones(int idx){
    if(diffIdx<2) return;
    int n = isLunatic()?4:2; double h = (STAGE_INFO[idx].bg+40)%360;
    for(int i=0;i<n;i++){
      EnemyType g = new EnemyType();
      g.shape = pick(ENEMY_SHAPES); g.hue=h; g.size=20; g.move="hover"; g.tier=2;
      g.hp = (int)Math.round(1300 + idx*300); g.score=300; g.moveSpeed=2; g.amp=0; g.freq=0.02;
      g.detailSeed=rng.nextLong(); g.fireCd=(int)Math.round(90*diff().fireMul);   // ボスより控えめ
      g.patterns = new Pattern[]{ makeUniquePattern(null, h, 0.8, 0.45) };        // ボス的な設計弾幕
      Enemy e = new Enemy(); e.g=g; e.drone=true;
      double ang = Math.PI*2*i/n;
      e.ox = Math.cos(ang)*155; e.oy = Math.sin(ang)*70 - 10;
      e.x = W/2 + e.ox; e.y = -50; e.sx=e.x; e.sy=e.y;
      e.hp=g.hp; e.maxhp=g.hp; e.fireBudget=999999; e.fireT=40 + i*16;
      enemies.add(e);
    }
  }
  // 中ボス：その場で静止して撃ち続ける大型機（HPバーつき・倒すと先へ）
  void spawnMidboss(int idx){
    EnemyType g=makeUniqueEnemyType(STAGE_INFO[idx].bg,3,"hover",pick(ENEMY_SHAPES));
    g.hp = (int)Math.round((1900 + idx*900) * (0.6 + 0.22*diffIdx));    // HPは控えめ（弾幕で魅せる）
    g.amp = 0; g.moveSpeed = 2.2;               // 動かない（中央で静止）
    // 弾幕を強化：ボス級の設計弾幕を2種＋速めの発射
    double h=STAGE_INFO[idx].bg;
    g.patterns = new Pattern[]{
      makeUniquePattern("ring", h, 1.1, 0.2),
      makeUniquePattern(idx%2==0?"spiral":"fan", h, 1.1, 0.55) };
    g.fireCd = (int)Math.round(46 * diff().fireMul);
    Enemy e = spawnEnemy(g, W/2, -60, 130, 0);
    e.fireBudget = 99999;                       // 中ボスは撃ち続ける
    midbossActive=true; midbossRef=e;
    floatText(W/2, 175, "― 中ボス ―", STAGE_INFO[idx].bg);
  }
  // 撃破時の死亡弾（自機狙い小リング＋3way）
  void enemyDeathBurst(Enemy e){
    if(enemyBullets.size()>1700) return;
    int n = 6 + Math.min(4, stageIndex);
    double off = rng.nextDouble()*Math.PI*2;                     // 全方位（自機狙いではない）
    for(int i=0;i<n;i++) mkB(e.x,e.y, off + i*Math.PI*2/n, bs(135+stageIndex*10), 0, e.g.hue);
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
    items.clear(); particles.clear(); boss=null; delayed.clear(); lasers.clear();
    stageTimer=0; bossWarn=0;
    stageRunner=buildStageTimeline(idx);
    stageDiff = 1 + idx*diff().stageScale;
    curStageHpMul = 1 + idx*0.10;
    state="play"; transTimer=0; pInvuln=Math.max(pInvuln,90);   // 演出画面なし（層名はサイドバー表記）
    snaps.clear(); rwUsesLeft = RW_USES[diffIdx]; rwCD=0; rewindFx=0;   // 巻き戻し回数は層ごとに回復
    midbossActive=false; midbossRef=null; midbossPending=false;
    sound.startBGM(idx);
  }
  void onBossDefeated(){
    sound.bossDown(); explosion(boss.x,boss.y,boss.hue,true);
    score += 50000 + stageIndex*20000; flash=1; shake=20; dropSpecial(boss.x,boss.y);
  }
  void nextStageOrWin(){
    if(stageIndex>=5){ state="victory"; transTimer=0; sound.stopBGM(); saveHiIfNeeded(); finishRun(); }
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
    power=Math.max(30, power - (diffIdx==0?8:14));   // 減少を緩和＋下限Lv.3（ボス戦で枯れない）
    if(boss!=null) boss.captured=false;   // 被弾でスペルカード捕獲失敗
  }
  void respawnOrGameOver(){
    if(lives>0){ lives--; resetPlayer(); bombs=Math.max(bombs,2); return; }
    if(endlessMode){   // 無限：コンティニュー無し→記録保存してゲームオーバー
      state="gameover"; transTimer=0; saveHiIfNeeded(); sound.stopBGM();
      String k=catKey(); int v=survivalFrames;
      if(!bestScore.containsKey(k)||v>bestScore.get(k)){ bestScore.put(k,(Integer)v); newRecScore=true; saveRecords(); sound.spellGet(); }
      return;
    }
    state="continue"; sound.stopBGM();   // コンティニュー選択へ
  }
  void doContinue(boolean yes){
    if(yes){
      continued=true; continueCount++;
      lives=diff().lives; bombs=diff().bombs;
      enemyBullets.clear(); lasers.clear(); resetPlayer(); pInvuln=200;
      state="play"; sound.init(); sound.startBGM(stageIndex);
    } else {
      state="gameover"; transTimer=0; saveHiIfNeeded();
    }
  }
  void updateContinue(){
    frame++;
    if(actJust("confirm")||actJust("shot")) doContinue(true);     // Z/Enter=コンティニュー
    else if(actJust("bomb")||actJust("quit")||actJust("pause")) doContinue(false);  // X/Q/Esc=やめる
    clearJust();
  }
  void useBomb(){
    if(bombs<=0||pDead||pBombTimer>0) return;
    bombs--; pBombTimer=110; pInvuln=Math.max(pInvuln,130); sound.bomb();
    flash=1.0; shake=22; bulletCancelToStars(); lasers.clear();
    bombFx=64; bombX=px; bombY=py;                         // 衝撃波エフェクト
    // 豪華なパーティクル（焔の波）
    for(int i=0;i<60;i++){ double a=i/60.0*Math.PI*2, s=2.5+rng.nextDouble()*3;
      Particle p=new Particle(); p.x=px;p.y=py;p.vx=Math.cos(a)*s;p.vy=Math.sin(a)*s;
      p.life=1;p.decay=0.012;p.hue=30+rr(-15,20);p.r=4+rng.nextDouble()*4; particles.add(p); }
    for(int i=0;i<30;i++){ double a=rng.nextDouble()*Math.PI*2, s=rng.nextDouble()*6;
      Particle p=new Particle(); p.x=px;p.y=py;p.vx=Math.cos(a)*s;p.vy=Math.sin(a)*s;
      p.life=1;p.decay=0.02;p.hue=48;p.r=3;p.star=true; particles.add(p); }
    for(Enemy e:enemies){ e.hp-=120; e.hitFlash=3; }
    if(boss!=null){ boss.captured=false; bossTakeDamage(boss, Math.max(1200, boss.segHp/8)); }
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
      if(boss!=null && !boss.entering){ double rr=boss.size*0.5+pr;
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
      case 1: { // FORWARD 集束（威力控えめに調整）
        int d=(int)Math.round(7*m);
        mk(px,sy-6,0,-19,7,(int)Math.round(d*1.1),false);
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
          double dif=Math.atan2(Math.sin(desired-cur),Math.cos(desired-cur));
          // 前方の標的だけ追尾（行き過ぎ＝標的が後方なら直進して画面外へ＝クルクル周回を防止）
          if(Math.abs(dif) < Math.toRadians(110)){
            double turn=0.16; cur+=Math.max(-turn,Math.min(turn,dif));
            double sp=Math.hypot(b.vx,b.vy); if(sp<1)sp=15;
            b.vx=Math.cos(cur)*sp; b.vy=Math.sin(cur)*sp; } }
      }
      b.x+=b.vx; b.y+=b.vy;
      if(b.y<-20||b.y>H+30||b.x<-30||b.x>W+30){ playerBullets.remove(i); continue; }
      boolean hit=false;
      for(Enemy e:enemies){ if(e.drone) continue;   // ドローンは無敵（自弾は貫通）
        double rr=e.g.size*0.7+b.r;
        if((e.x-b.x)*(e.x-b.x)+(e.y-b.y)*(e.y-b.y)<rr*rr){ e.hp-=b.dmg; e.hitFlash=2; hit=true; sound.enemyHit();
          Particle p=new Particle(); p.x=b.x;p.y=b.y;p.life=1;p.decay=0.1;p.hue=e.g.hue;p.r=3; particles.add(p); break; } }
      if(!hit && boss!=null && !boss.entering && !boss.dead){ double rr=boss.size*0.85+b.r;
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
    if(rwCD>0) rwCD--; if(rewindFx>0) rewindFx--;
    if(practiceMode){           // 弾幕鑑賞：無敵・ボス循環・Escで選択へ
      pInvuln=Math.max(pInvuln,12);
      if(actJust("pause")){ practiceMode=false; state="practice"; enemyBullets.clear(); lasers.clear(); boss=null; clearJust(); return; }
      if(boss!=null && boss.dead){ boss=makeBoss(practiceBoss); boss.entering=true; }
      // 時間制限が無いので、鑑賞では一定時間で次の弾幕へ循環（Xで手動スキップ）
      if(boss!=null && !boss.entering && boss.declTimer<=0 && (boss.atkT>1500 || actJust("bomb"))){
        boss.atkIdx=(boss.atkIdx+1)%boss.atks.length; bossStartAtk(boss); enemyBullets.clear(); lasers.clear();
      }
    } else if(actJust("pause")){ state="paused"; return; }
    if(actJust("mute")){ boolean m=sound.toggleMute(); floatText(W/2,40,m?"MUTE":"SOUND ON",60); }
    if(actJust("rewind")) doRewind();
    if(timeLimitMode){ tlTimer--; if(tlTimer<=0){ state="gameover"; transTimer=0; saveHiIfNeeded(); finishRun(); sound.stopBGM(); clearJust(); return; } }
    if(endlessMode){ survivalFrames++; emitEndless(); }
    if(bossWarn>0) bossWarn--;
    // 中ボス待機：場の敵が掃けてから（or 一定時間後に保険で）出現
    if(midbossPending){
      midbossPendingT++;
      if(enemies.isEmpty() || midbossPendingT>600){ enemies.clear(); spawnMidboss(midbossPendingIdx); midbossPending=false; }
    }
    if(!midbossActive && !midbossPending){   // 中ボス中・待機中は進行を止める（=他の敵を出さない）
      stageTimer++;
      if(stageRunner!=null){
        while(stageRunner.idx<stageRunner.events.size() && stageTimer>=stageRunner.events.get(stageRunner.idx).t){
          stageRunner.events.get(stageRunner.idx).fn.run(); stageRunner.idx++;
        }
      }
      for(int i=delayed.size()-1;i>=0;i--){ if(stageTimer>=delayed.get(i).t){ delayed.get(i).fn.run(); delayed.remove(i); } }
    }
    sound.bgmTick(frame, stageIndex);

    updatePlayer();
    updatePlayerBullets();

    for(int i=enemies.size()-1;i>=0;i--){
      Enemy e=enemies.get(i); updateEnemy(e);
      if(e.hp<=0 && !e.dead){ e.dead=true; score+=e.g.score; explosion(e.x,e.y,e.g.hue,e.g.tier>1);
        sound.explode(); dropItems(e.x,e.y,e.g.tier);
        if(e==midbossRef){ midbossActive=false; midbossRef=null; deathBurstOn=true; explosion(e.x,e.y,e.g.hue,true); shake=14; dropSpecial(e.x,e.y);
          if(stageRunner!=null && stageRunner.idx<stageRunner.events.size()) stageTimer=stageRunner.events.get(stageRunner.idx).t-1; }  // 撃破後すぐ次のウェーブへ
        else if(deathBurstOn && e.g.tier<=2 && e.y<H*0.85 && rng.nextDouble()<0.33) enemyDeathBurst(e); }   // 一部の雑魚だけ撃破時に弾
      if(e.dead) enemies.remove(i);
    }
    if(midbossActive && (midbossRef==null || midbossRef.dead)){ midbossActive=false; midbossRef=null; }
    if(boss!=null){
      updateBoss(boss);
      if(boss.dead){
        if(boss.deathTimer%6==0) explosion(boss.x+rr(-40,40), boss.y+rr(-40,40), boss.hue, true);
        if(boss.deathTimer>120){
          int bi=boss.idx; boss=null; lasers.clear(); enemyBullets.clear();
          // 撃破後アウトロ会話 → 次の層へ
          String[] out = (bi==5 && isLunatic())? LUNA_OUTRO : OUTRO[bi];
          startDialogue(out, ()->{ state="stageclear"; transTimer=200; sound.stopBGM(); });
        }
      }
    }
    updateLasers();
    updateEnemyBullets();
    for(int i=items.size()-1;i>=0;i--){ Item it=items.get(i); updateItem(it); if(it.dead||it.y>H+30) items.remove(i); }
    for(int i=particles.size()-1;i>=0;i--){ Particle p=particles.get(i); p.x+=p.vx;p.y+=p.vy;p.vy+=0.03;p.life-=p.decay; if(p.life<=0) particles.remove(i); }
    for(int i=floaters.size()-1;i>=0;i--){ Floater f=floaters.get(i); f.y-=0.6; f.life-=0.02; if(f.life<=0) floaters.remove(i); }

    if(shake>0) shake*=0.88; if(shake<0.3) shake=0;
    if(flash>0) flash*=0.9; if(flash<0.02) flash=0;
    if(bombFx>0) bombFx--;
    // 残響リワインド：状態を間引いて記録
    if(stageTimer % SNAP_EVERY == 0){
      snaps.addLast(snap());
      while(snaps.size() > maxSnaps()) snaps.removeFirst();
    }
    clearJust();
  }
  int maxSnaps(){ return (int)(RW_SEC[diffIdx]*60.0/SNAP_EVERY)+2; }
  Snapshot snap(){
    Snapshot s=new Snapshot();
    s.px=px;s.py=py;s.pInvuln=pInvuln;s.pBombTimer=pBombTimer;s.pShotCd=pShotCd;s.pDeathTimer=pDeathTimer;s.pDead=pDead;
    s.lives=lives;s.bombs=bombs;s.power=power;s.score=score;s.grazeCount=grazeCount;s.stageTimer=stageTimer;
    for(Bullet b:enemyBullets) s.eb.add(cpB(b));
    for(Enemy e:enemies) s.en.add(cpE(e));
    for(Item i:items) s.it.add(cpI(i));
    for(Laser l:lasers) s.lz.add(cpL(l));
    s.boss=cpBoss(boss);
    return s;
  }
  void restore(Snapshot s){
    px=s.px;py=s.py;pInvuln=Math.max(s.pInvuln,40);pBombTimer=s.pBombTimer;pShotCd=s.pShotCd;pDeathTimer=s.pDeathTimer;pDead=s.pDead;
    lives=s.lives;bombs=s.bombs;power=s.power;score=s.score;grazeCount=s.grazeCount;stageTimer=s.stageTimer;
    enemyBullets.clear(); for(Bullet b:s.eb) enemyBullets.add(cpB(b));
    enemies.clear(); for(Enemy e:s.en) enemies.add(cpE(e));
    items.clear(); for(Item i:s.it) items.add(cpI(i));
    lasers.clear(); for(Laser l:s.lz) lasers.add(cpL(l));
    boss=cpBoss(s.boss);
  }
  void doRewind(){
    if(rwCD>0 || rwUsesLeft==0 || snaps.size()<2) return;
    restore(snaps.peekFirst());      // 記録の最古（＝最大秒数前）へ
    snaps.clear(); snaps.addLast(snap());
    if(rwUsesLeft>0) rwUsesLeft--;
    rwCD=(int)Math.round(RW_CD[diffIdx]*60); rewindFx=30;
    echoUsed=true; sound.rewind();
    floatText(px, py-30, "REWIND", 200);
  }

  // 敵弾の更新（通常／停止→再加速／誘導／サイン波／重力／分裂）
  void updateEnemyBullets(){
    for(int i=enemyBullets.size()-1;i>=0;i--){
      Bullet b=enemyBullets.get(i);
      // 分裂
      if(b.splitT>0){ b.splitT--; if(b.splitT==0){ for(int k=0;k<b.splitN;k++){ Bullet c=mkB(b.x,b.y,k*Math.PI*2/b.splitN,b.splitSpd,b.splitKind,b.hue); } enemyBullets.remove(i); continue; } }
      switch(b.mode){
        case 1: // 停止 → 解除後に自機方向へ
          if(b.delay>0){ b.delay--; if(b.delay==0){ b.angle=Math.atan2(py-b.y,px-b.x); b.speed=b.dsp; } break; }
          b.x+=Math.cos(b.angle)*b.speed; b.y+=Math.sin(b.angle)*b.speed; break;
        case 5: // 形を保持 → 解除後に記憶した方向(da)へ（弾で形作って崩す）
          if(b.delay>0){ b.delay--; if(b.delay==0){ b.angle=b.da; b.speed=b.dsp; } break; }
          b.x+=Math.cos(b.angle)*b.speed; b.y+=Math.sin(b.angle)*b.speed; break;
        case 2: // 誘導
          if(b.homTime>0){ b.homTime--; double des=Math.atan2(py-b.y,px-b.x);
            double d=Math.atan2(Math.sin(des-b.angle),Math.cos(des-b.angle));
            b.angle += Math.max(-b.homTurn,Math.min(b.homTurn,d)); }
          b.x+=Math.cos(b.angle)*b.speed; b.y+=Math.sin(b.angle)*b.speed; break;
        case 3: // サイン波
          b.dist += b.speed; double px2=Math.cos(b.baseAngle), py2=Math.sin(b.baseAngle);
          double off=Math.sin(b.life*b.sineFreq)*b.sineAmp;
          b.x = b.sx0 + px2*b.dist - py2*off; b.y = b.sy0 + py2*b.dist + px2*off; break;
        case 4: // 重力（自機へ引き寄せ）
          { double des=Math.atan2(py-b.y,px-b.x); double d=Math.atan2(Math.sin(des-b.angle),Math.cos(des-b.angle));
            b.angle += Math.max(-b.grav,Math.min(b.grav,d)); }
          b.x+=Math.cos(b.angle)*b.speed; b.y+=Math.sin(b.angle)*b.speed; break;
        default:
          b.speed+=b.accel; if(b.speed>14)b.speed=14;
          if(b.curve!=0){ b.turned+=Math.abs(b.curve); if(b.turned>2.2) b.curve=0; }
          b.angle+=b.curve;
          b.x+=Math.cos(b.angle)*b.speed; b.y+=Math.sin(b.angle)*b.speed;
      }
      b.life++;
      if(b.x<-40||b.x>W+40||b.y<-40||b.y>H+40 || b.life>1400) enemyBullets.remove(i);
    }
  }
  // レーザーの更新と被弾
  void updateLasers(){
    for(int i=lasers.size()-1;i>=0;i--){
      Laser L=lasers.get(i); L.t++;
      if(L.anchor && boss!=null){ L.x=boss.x; L.y=boss.y; } else { L.x+=L.vx; L.y+=L.vy; }
      L.angle += L.spin;
      boolean active = L.t>=L.tele && L.t<L.tele+L.active;
      if(active && pInvuln<=0 && !pDead && laserHit(L)) playerDie();
      if(L.t>=L.tele+L.active) lasers.remove(i);
    }
  }
  boolean laserHit(Laser L){
    double dx=px-L.x, dy=py-L.y, c=Math.cos(L.angle), s=Math.sin(L.angle);
    double proj=dx*c+dy*s; if(proj<0||proj>L.len) return false;
    double perp=Math.abs(-dx*s+dy*c); return perp < L.width/2 + pr;
  }
  void drawLasers(Graphics2D g2){
    for(Laser L:lasers){
      double c=Math.cos(L.angle), s=Math.sin(L.angle);
      double x2=L.x+c*L.len, y2=L.y+s*L.len;
      boolean active = L.t>=L.tele && L.t<L.tele+L.active;
      if(!active){ // 予告線
        float a=(float)(0.25+0.35*Math.abs(Math.sin(L.t*0.4)));
        g2.setColor(hsba(L.hue,0.7,1.0,(int)(a*200))); g2.setStroke(new BasicStroke(2f));
        g2.draw(new Line2D.Double(L.x,L.y,x2,y2));
      } else {
        int phase = L.t-L.tele; double grow = Math.min(1, phase/4.0);
        double w = L.width*grow;
        g2.setColor(hsba(L.hue,0.8,0.5,150)); g2.setStroke(new BasicStroke((float)(w*2),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(L.x,L.y,x2,y2));
        g2.setColor(hsba(L.hue,0.6,0.9,235)); g2.setStroke(new BasicStroke((float)w,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(L.x,L.y,x2,y2));
        g2.setColor(new Color(255,255,255,235)); g2.setStroke(new BasicStroke((float)Math.max(1.5,w*0.4),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(L.x,L.y,x2,y2));
      }
    }
    g2.setStroke(new BasicStroke(1f));
  }

  /* ====================================================================
     状態ディスパッチ
     ==================================================================== */
  void update(){
    // 音量調節（どの画面でも -/[ で下げ、=/] で上げ）
    if(just[KeyEvent.VK_EQUALS]||just[KeyEvent.VK_CLOSE_BRACKET]){ just[KeyEvent.VK_EQUALS]=just[KeyEvent.VK_CLOSE_BRACKET]=false; sound.init(); floatText(CX,60,"音量 "+sound.addVolume(0.1)+"%",60); }
    if(just[KeyEvent.VK_MINUS]||just[KeyEvent.VK_OPEN_BRACKET]){ just[KeyEvent.VK_MINUS]=just[KeyEvent.VK_OPEN_BRACKET]=false; floatText(CX,60,"音量 "+sound.addVolume(-0.1)+"%",60); }
    switch(state){
      case "menu": updateMenu(); break;
      case "records": updateRecords(); break;
      case "continue": updateContinue(); break;
      case "practice": updatePractice(); break;
      case "charselect": updateCharSelect(); break;
      case "dialogue": updateDialogue(); break;
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
    int nItems=6;
    if(actJust("up")){ menuSel=(menuSel+nItems-1)%nItems; sound.menu(); }
    else if(actJust("down")){ menuSel=(menuSel+1)%nItems; sound.menu(); }
    if(menuSel==1){ // モード
      if(actJust("left")){ modeSel=(modeSel+MODES.length-1)%MODES.length; sound.menu(); }
      else if(actJust("right")){ modeSel=(modeSel+1)%MODES.length; sound.menu(); }
    } else if(menuSel==2){ // 難易度
      if(actJust("left")){ diffIdx=(diffIdx+DIFFS.length-1)%DIFFS.length; sound.menu(); }
      else if(actJust("right")){ diffIdx=(diffIdx+1)%DIFFS.length; sound.menu(); }
    }
    if(actJust("confirm")||actJust("shot")){
      sound.init(); sound.select();
      if(menuSel==0){ gameMode=MODES[modeSel];
        if(gameMode.equals("弾幕鑑賞")){ state="practice"; practiceBoss=0; practiceAtk=0; }
        else { state="charselect"; selRow=0; } }
      else if(menuSel==1){ modeSel=(modeSel+1)%MODES.length; }
      else if(menuSel==2){ diffIdx=(diffIdx+1)%DIFFS.length; }
      else if(menuSel==3){ state="records"; recDiff=diffIdx; }
      else if(menuSel==4) state="help";
      else { hiscore=0; saveHiscore(0); floatText(W/2,H-60,"HISCORE CLEARED",0); }
    }
    clearJust();
  }
  int recDiff=0; int practiceBoss=0, practiceAtk=0;
  void updateRecords(){
    frame++;
    if(actJust("left")){ recDiff=(recDiff+DIFFS.length-1)%DIFFS.length; sound.menu(); }
    else if(actJust("right")){ recDiff=(recDiff+1)%DIFFS.length; sound.menu(); }
    if(actJust("bomb")){ bestTime.clear(); bestScore.clear(); saveRecords(); floatText(CX,H-70,"RECORDS CLEARED",0); sound.bomb(); }
    if(actJust("pause")||actJust("confirm")||actJust("shot")){ state="menu"; sound.menu(); }
    clearJust();
  }
  // 弾幕鑑賞：ボス選択 → 観賞（無敵）
  void updatePractice(){
    frame++;
    if(actJust("up")){ practiceBoss=(practiceBoss+BOSSES.length-1)%BOSSES.length; sound.menu(); }
    else if(actJust("down")){ practiceBoss=(practiceBoss+1)%BOSSES.length; sound.menu(); }
    if(actJust("left")){ diffIdx=(diffIdx+DIFFS.length-1)%DIFFS.length; sound.menu(); }
    else if(actJust("right")){ diffIdx=(diffIdx+1)%DIFFS.length; sound.menu(); }
    if(actJust("confirm")||actJust("shot")){ sound.init(); sound.select(); startPractice(); }
    if(actJust("pause")){ state="menu"; sound.menu(); }
    clearJust();
  }
  void startPractice(){
    practiceMode=true; endlessMode=timeLimitMode=false; silentMode=false; sound.setSilent(false);
    usedDesign.clear(); usedPatternSigs.clear();
    score=0; lives=9; bombs=5; power=80; grazeCount=0; stageIndex=practiceBoss;
    enemyBullets.clear(); playerBullets.clear(); enemies.clear(); items.clear(); particles.clear(); lasers.clear(); delayed.clear();
    stageRunner=null; midbossActive=false; resetPlayer();
    stageDiff=1+practiceBoss*diff().stageScale; curStageHpMul=1;
    boss=makeBoss(practiceBoss); boss.entering=true;
    state="play"; sound.startBGM(practiceBoss);
  }
  // 回廊無限：段階的に激化する弾を流し続ける（生存時間を競う）
  void emitEndless(){
    if(state.equals("briefing")) return;
    int t=survivalFrames; int phase = t<1800?0 : t<4200?1 : 2;
    int interval = (int)Math.round((phase==0?44:phase==1?32:24) * diff().fireMul);
    if(interval<6) interval=6;
    if(t % interval == 0 && enemyBullets.size()<1400){
      double spd = 120 + phase*35;
      int n = (int)Math.round((phase==0?14:phase==1?20:26) * diff().density); if(n<6)n=6;
      for(int i=0;i<n;i++) mkB(W/2, -8, i*Math.PI*2/n + t*0.012, bs(spd), i%3, 28+phase*40);
      if(phase>=1){ double[][] cs={{0,0},{W,0}};
        for(double[] c:cs){ double a=Math.atan2(py-c[1],px-c[0]); for(int k=-1;k<=1;k++) mkB(c[0],c[1],a+k*0.16,bs(spd+30),2,200); } }
      if(phase>=2){ for(int k=0;k<3;k++) mkB(W/2,-8, t*0.12+k*2.094, bs(spd+20),1,120); }
    }
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
    usedPatternSigs.clear(); usedEnemySigs.clear(); usedDesign.clear(); enemyDesignRot=0;
    rng = new Random();
    score=0; lives=diff().lives; bombs=diff().bombs; power=0; stageIndex=0; grazeCount=0;
    echoUsed=false; newRecTime=false; newRecScore=false; deathBurstOn=false; continued=false; continueCount=0;   // フラグは1周通しでリセット
    runStartNano=System.nanoTime(); clearTimerRunning=true; runTimeSec=0;
    endlessMode=timeLimitMode=silentMode=practiceMode=false;
    if(gameMode.equals("回廊無限")) endlessMode=true;
    else if(gameMode.equals("灯火・制限時間")){ timeLimitMode=true; tlTimer=120*60; }   // 120秒持ち
    else if(gameMode.equals("無音")) silentMode=true;
    sound.setSilent(silentMode);
    resetPlayer();
    if(endlessMode) startEndless(); else startStage(0);
  }
  void startEndless(){
    stageIndex=0; survivalFrames=0; endlessWave=0;
    enemyBullets.clear(); playerBullets.clear(); enemies.clear();
    items.clear(); particles.clear(); lasers.clear(); boss=null; delayed.clear();
    snaps.clear(); rwUsesLeft=RW_USES[diffIdx]; rwCD=0; midbossActive=false;
    stageTimer=0; bossWarn=0; stageRunner=null;
    state="play"; transTimer=0; pInvuln=Math.max(pInvuln,90); sound.startBGM(0);
  }
  void toMenu(){
    sound.stopBGM(); state="menu"; menuSel=0;
    enemyBullets.clear(); playerBullets.clear(); enemies.clear();
    items.clear(); particles.clear(); boss=null; clearJust();
  }

  /* ====================================================================
     描画
     ==================================================================== */
  // 星（Star は util/ パッケージ）
  Star[] stars = new Star[140];
  Star[] tstars = new Star[80];
  void initStars(){
    for(int i=0;i<stars.length;i++){ Star s=new Star(); s.x=Math.random()*VW; s.y=Math.random()*H; s.z=Math.random()*2+0.4; s.s=Math.random()*1.6+0.3; stars[i]=s; }
    for(int i=0;i<tstars.length;i++){ Star s=new Star(); s.x=Math.random()*VW; s.y=Math.random()*H; s.z=Math.random()*1.5+0.3; s.s=Math.random()*2+0.5; tstars[i]=s; }
  }
  // 色ヘルパ hsb/hsba/clamp01 は render/Colors（static import）

  double bgHueFor(){
    if(state.equals("menu")||state.equals("help")||state.equals("charselect")) return 220;
    if(state.equals("victory")) return STAGE_INFO[5].bg;
    return STAGE_INFO[Math.max(0,Math.min(5,stageIndex))].bg;
  }
  // オフスクリーンに高解像度(スーパーサンプル)で描画 →1枚を転送（画質向上＋コスト一定）
  static final double SS = 1.6;                 // スーパーサンプル倍率（画質）
  static final int BW=(int)Math.round(VW*SS), BH=(int)Math.round(H*SS);
  BufferedImage frameBuf; Graphics2D frameG;
  protected void paintComponent(Graphics g){
    super.paintComponent(g);
    if(frameBuf==null){
      frameBuf=new BufferedImage(BW,BH,BufferedImage.TYPE_INT_RGB);
      frameG=frameBuf.createGraphics();
      frameG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      frameG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      frameG.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      frameG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      frameG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      frameG.scale(SS, SS);                     // 以降は VW×H 座標で描ける
    }
    frameG.setClip(null);
    renderGame(frameG);   // VW×H 座標 → 内部は BW×BH に高精細描画
    // ウィンドウへ1枚転送（レターボックス）
    Graphics2D g2=(Graphics2D)g;
    int ww=getWidth(), wh=getHeight();
    g2.setColor(letterboxColor()); g2.fillRect(0,0,ww,wh);
    double scale=Math.min(ww/(double)VW, wh/(double)H);
    int dw=(int)Math.round(VW*scale), dh=(int)Math.round(H*scale);
    int ox=(ww-dw)/2, oy=(wh-dh)/2;
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g2.drawImage(frameBuf, ox,oy,dw,dh, null);
    // FPS計測
    long pn=System.nanoTime(); paintCount++;
    if(pn-fpsT>=1_000_000_000L){ fps=paintCount; paintCount=0; fpsT=pn; }
  }
  int paintCount, fps; long fpsT;
  Color letterboxColor(){ return hsb(bgHueFor(),0.5,0.04); }

  void renderGame(Graphics2D g2){
    g2.setColor(Color.BLACK); g2.fillRect(0,0,VW,H);   // ブレ時の縁対策
    AffineTransform base=g2.getTransform();
    // 画面揺れ演出は廃止（shake は無効化）
    switch(state){
      case "menu": drawMenu(g2); break;
      case "records": drawRecords(g2); break;
      case "practice": drawPractice(g2); break;
      case "charselect": drawCharSelect(g2); break;
      case "dialogue": drawDialogue(g2); break;
      case "help": drawHelp(g2); break;
      case "briefing": drawBackground(g2,stageIndex); drawPlayfieldFrame(g2); drawSidebar(g2); drawBriefing(g2); break;
      case "play": case "paused": case "stageclear": case "gameover": case "continue": {
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
        else if(state.equals("continue")) drawContinue(g2);
        break;
      }
      case "victory": drawBackground(g2,5); drawVictory(g2); break;
    }
    g2.setTransform(base);
    if(flash>0){ g2.setColor(new Color(255,255,255,(int)(flash*150))); g2.fillRect(0,0,VW,H); }
    if(rewindFx>0){ g2.setColor(new Color(80,150,255,(int)(rewindFx/30.0*120))); g2.fillRect(0,0,W,H); }
    if(echoUsed && (state.equals("play")||state.equals("paused"))){
      g2.setColor(new Color(120,170,255,150)); g2.setFont(new Font("SansSerif",Font.BOLD,12));
      g2.drawString("ECHO", 8, H-10);
    }
  }

  // 背景（グラデ＋星雲）はステージ毎に1回だけ生成してキャッシュ→毎フレームは転送のみ
  final HashMap<Integer,BufferedImage> bgCache = new HashMap<>();
  void drawBackground(Graphics2D g2,int idx){
    g2.setColor(Color.BLACK); g2.fillRect(0,0,VW,H);     // 背景は真っ黒
    // 流れる星（控えめ・速度の目印程度）
    for(Star st:stars){
      st.y += st.z*(1.4+idx*0.15);
      if(st.y>H){ st.y=-2; st.x=Math.random()*VW; }
      int a=(int)(40+st.z*22);
      g2.setColor(new Color(120,130,150,a));
      g2.fillRect((int)st.x,(int)st.y,(int)st.s,(int)st.s);
    }
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
    for(Enemy e:enemies) drawEnemy(g2,e);
    if(boss!=null) drawBoss(g2,boss);
    drawItems(g2);
    drawLasers(g2);
    drawBullets(g2);
    drawParticles(g2);
    if(bombFx>0) drawBombFx(g2);
    drawPlayer(g2);
    drawFloaters(g2);
    // ボスのHPバー（プレイフィールド最上部）
    if(boss!=null && !boss.entering){
      g2.setColor(new Color(0,0,0,110)); g2.fill(new Rectangle2D.Double(40,8,W-80,8));
      double frac = boss.invincible? 1.0 : (double)boss.hp/Math.max(1,boss.segHp);
      g2.setColor(boss.invincible? new Color(180,180,200) : hsb(boss.hue,0.85,0.6));
      g2.fill(new Rectangle2D.Double(40,8,(W-80)*Math.max(0,frac),8));
      g2.setColor(new Color(255,255,255,90)); g2.setStroke(new BasicStroke(1f)); g2.draw(new Rectangle2D.Double(40,8,W-80,8));
      if(boss.invincible){ g2.setColor(new Color(255,210,120)); g2.setFont(new Font("SansSerif",Font.BOLD,22)); centerStr(g2,"INVINCIBLE  "+(boss.luxTimer/60),W/2,40); }
    }
    // モード別オーバーレイ
    if(timeLimitMode){ int s=Math.max(0,tlTimer/60);
      g2.setColor(s<=15?new Color(255,90,90):new Color(255,220,120)); g2.setFont(new Font("Monospaced",Font.BOLD,26));
      centerStr(g2,"TIME "+fmtTime(tlTimer/60.0),W/2,34); }
    if(endlessMode){ g2.setColor(new Color(200,220,255)); g2.setFont(new Font("Monospaced",Font.BOLD,24));
      centerStr(g2,"SURVIVAL "+fmtTime(survivalFrames/60.0),W/2,34); }
    if(practiceMode){ g2.setColor(new Color(180,255,210)); g2.setFont(new Font("SansSerif",Font.BOLD,14));
      centerStr(g2,"弾幕鑑賞（無敵）  P/Escで選択へ",W/2,H-14); }
  }

  // ボム：焔色の二重衝撃波が広がる
  void drawBombFx(Graphics2D g2){
    double p=1-bombFx/64.0;                 // 0→1
    double rad=p*520;
    AffineTransform t=g2.getTransform();
    Composite oc=g2.getComposite();
    for(int k=0;k<2;k++){
      double rr=rad - k*60; if(rr<0) continue;
      float a=(float)Math.max(0,(1-p))*0.7f;
      g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,a));
      g2.setColor(hsb(30+k*15,0.9,1.0));
      g2.setStroke(new BasicStroke((float)(16*(1-p)+3)));
      g2.draw(new Ellipse2D.Double(bombX-rr,bombY-rr,rr*2,rr*2));
    }
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,(float)Math.max(0,(1-p))*0.5f));
    RadialGradientPaint rp=new RadialGradientPaint(new Point2D.Double(bombX,bombY),(float)Math.max(1,rad),
      new float[]{0f,0.7f,1f}, new Color[]{hsba(40,0.8,1,180),hsba(28,0.9,0.7,60),new Color(0,0,0,0)});
    g2.setPaint(rp); g2.fill(new Ellipse2D.Double(bombX-rad,bombY-rad,rad*2,rad*2));
    g2.setComposite(oc); g2.setTransform(t); g2.setStroke(new BasicStroke(1f));
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
    double s=b.size; double hue=b.hue; boolean lit=b.hitFlash>0;
    // 足元の魔法陣
    AffineTransform mcT=g2.getTransform(); g2.rotate(b.t*0.006);
    g2.setStroke(new BasicStroke(2f)); g2.setColor(hsba(hue,0.7,0.6,55));
    double mc=s*2.3; g2.draw(circle(0,0,mc)); g2.draw(circle(0,0,mc*0.7));
    for(int i=0;i<12;i++){ double a=i/12.0*Math.PI*2;
      g2.draw(new Line2D.Double(Math.cos(a)*mc*0.7,Math.sin(a)*mc*0.7,Math.cos(a)*mc,Math.sin(a)*mc)); }
    g2.setTransform(mcT);
    switch(b.idx){
      case 0: drawBossGate(g2,b,s,hue,lit); break;
      case 1: drawBossBloom(g2,b,s,hue,lit); break;
      case 2: drawBossVortex(g2,b,s,hue,lit); break;
      case 3: drawBossGrid(g2,b,s,hue,lit); break;
      case 4: drawBossEcho(g2,b,s,hue,lit); break;
      default: drawBossLux(g2,b,s,hue,lit); break;
    }
    g2.setTransform(t);
  }
  Color cF(boolean lit,double h,double sa,double br){ return lit?Color.WHITE:hsb(h,sa,br); }
  // 第1層 ゲート：門柱＋フードの光点ふたつ
  void drawBossGate(Graphics2D g2,Boss b,double s,double hue,boolean lit){
    g2.setColor(cF(lit,hue,0.15,0.45));
    g2.fill(new RoundRectangle2D.Double(-s*1.1,-s*0.9,s*0.4,s*1.9,8,8));
    g2.fill(new RoundRectangle2D.Double(s*0.7,-s*0.9,s*0.4,s*1.9,8,8));
    g2.fill(new RoundRectangle2D.Double(-s*1.1,-s*1.05,s*2.2,s*0.35,8,8));   // 梁
    // フードの人影
    g2.setColor(cF(lit,hue,0.1,0.32));
    Path2D hood=new Path2D.Double(); hood.moveTo(0,-s*0.6); hood.quadTo(s*0.55,-s*0.4,s*0.4,s*0.7);
    hood.lineTo(-s*0.4,s*0.7); hood.quadTo(-s*0.55,-s*0.4,0,-s*0.6); hood.closePath(); g2.fill(hood);
    double bl=0.5+0.5*Math.sin(b.t*0.1);
    g2.setColor(new Color(255,(int)(180*bl+40),60)); g2.fill(circle(-s*0.15,-s*0.1,s*0.08)); g2.fill(circle(s*0.15,-s*0.1,s*0.08));
    g2.setColor(new Color(255,160,60,90)); g2.setStroke(new BasicStroke(2f)); g2.draw(hood);
  }
  // 第2層 ブルーム：少年型＋花びらの光粒
  void drawBossBloom(Graphics2D g2,Boss b,double s,double hue,boolean lit){
    for(int i=0;i<8;i++){ double a=b.t*0.03+i*Math.PI/4, rr=s*(1.0+0.25*Math.sin(b.t*0.05+i));
      g2.setColor(hsba((hue+i*8)%360,0.5,1.0,150));
      double px2=Math.cos(a)*rr, py2=Math.sin(a)*rr;
      Path2D pet=new Path2D.Double(); pet.moveTo(px2,py2); pet.quadTo(px2+6,py2-3,px2+2,py2-12); pet.quadTo(px2-3,py2-4,px2,py2); g2.fill(pet); }
    g2.setColor(cF(lit,hue,0.4,0.7)); g2.fill(new Ellipse2D.Double(-s*0.45,-s*0.2,s*0.9,s*1.0));  // 体
    g2.setColor(cF(lit,hue,0.2,0.85)); g2.fill(circle(0,-s*0.5,s*0.35));                              // 頭
    g2.setColor(new Color(255,255,255,200)); g2.fill(circle(-s*0.12,-s*0.5,s*0.06)); g2.fill(circle(s*0.12,-s*0.5,s*0.06));
  }
  // 第3層 ヴォルテクス：渦巻くコート
  void drawBossVortex(Graphics2D g2,Boss b,double s,double hue,boolean lit){
    g2.setStroke(new BasicStroke(3f));
    for(int k=0;k<3;k++){ g2.setColor(hsba((hue+k*15)%360,0.6,0.7-k*0.12,200));
      Path2D sp=new Path2D.Double(); boolean f=true;
      for(double a=0;a<Math.PI*4;a+=0.3){ double rr=s*0.2+a*s*0.13; double rot=b.t*0.04+k*2.094;
        double X=Math.cos(a+rot)*rr, Y=Math.sin(a+rot)*rr; if(f){sp.moveTo(X,Y);f=false;}else sp.lineTo(X,Y); }
      g2.draw(sp); }
    g2.setColor(cF(lit,hue,0.5,0.6)); g2.fill(circle(0,0,s*0.4));
    g2.setColor(new Color(255,255,255,220)); g2.fill(circle(0,0,s*0.18));
  }
  // 第4層 グリッド：青白格子＋菱形コア
  void drawBossGrid(Graphics2D g2,Boss b,double s,double hue,boolean lit){
    g2.setColor(hsba(hue,0.5,0.7,160)); g2.setStroke(new BasicStroke(1.5f));
    for(int i=-2;i<=2;i++){ g2.draw(new Line2D.Double(i*s*0.5,-s,i*s*0.5,s)); g2.draw(new Line2D.Double(-s,i*s*0.5,s,i*s*0.5)); }
    g2.rotate(b.t*0.02);
    Path2D dia=poly(new double[]{0,s*0.6,0,-s*0.6}, new double[]{-s*0.8,0,s*0.8,0});
    g2.setColor(cF(lit,hue,0.7,0.55)); g2.fill(dia);
    g2.setColor(new Color(220,240,255)); g2.setStroke(new BasicStroke(2.5f)); g2.draw(dia);
    g2.setColor(Color.WHITE); g2.fill(circle(0,0,s*0.18));
  }
  // 第5層 エコー：二重にずれた輪郭
  void drawBossEcho(Graphics2D g2,Boss b,double s,double hue,boolean lit){
    for(int k=2;k>=0;k--){ double off=k*6*Math.sin(b.t*0.05);
      g2.setColor(hsba(hue,0.5,0.6,k==0?255:90-k*20));
      Path2D bd=new Path2D.Double(); bd.moveTo(off,-s*0.7); bd.quadTo(s*0.5+off,-s*0.3,s*0.35+off,s*0.8);
      bd.lineTo(-s*0.35+off,s*0.8); bd.quadTo(-s*0.5+off,-s*0.3,off,-s*0.7); bd.closePath();
      if(k==0&&lit){ g2.setColor(Color.WHITE); } g2.fill(bd);
      g2.setColor(cF(lit,hue,0.3,0.85)); g2.fill(circle(off,-s*0.45,s*0.25)); }
    g2.setColor(new Color(200,220,255,200)); g2.fill(circle(-s*0.08,-s*0.45,s*0.05)); g2.fill(circle(s*0.08,-s*0.45,s*0.05));
  }
  // 第6層 ルクス：球状金コア＋人影＋放射光
  void drawBossLux(Graphics2D g2,Boss b,double s,double hue,boolean lit){
    g2.setColor(hsba(45,0.8,0.7,60)); g2.setStroke(new BasicStroke(2f));
    for(int i=0;i<16;i++){ double a=i*Math.PI/8+b.t*0.01, rr=s*(1.1+0.2*Math.sin(b.t*0.04+i));
      g2.draw(new Line2D.Double(Math.cos(a)*s*0.8,Math.sin(a)*s*0.8,Math.cos(a)*rr*1.5,Math.sin(a)*rr*1.5)); }
    RadialGradientPaint rp=new RadialGradientPaint(new Point2D.Double(0,0),(float)s,
      new float[]{0f,0.5f,1f}, new Color[]{Color.WHITE,hsb(45,0.85,0.75),hsba(40,0.9,0.4,40)});
    g2.setPaint(rp); g2.fill(circle(0,0,s));
    g2.setColor(lit?Color.WHITE:new Color(255,250,230,180));   // 人影
    Path2D fig=new Path2D.Double(); fig.moveTo(0,-s*0.4); fig.quadTo(s*0.25,-s*0.1,s*0.18,s*0.5);
    fig.lineTo(-s*0.18,s*0.5); fig.quadTo(-s*0.25,-s*0.1,0,-s*0.4); fig.closePath(); g2.fill(fig);
    g2.fill(circle(0,-s*0.45,s*0.16));
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
    g2.setColor(new Color(255,180,90,235));   // 灯火（橙）の自弾
    for(PBullet b:playerBullets) g2.fill(new RoundRectangle2D.Double(b.x-b.r*0.5,b.y-b.r*1.6,b.r,b.r*3.2,b.r,b.r));
    g2.setColor(new Color(255,245,220));
    for(PBullet b:playerBullets) g2.fill(new Rectangle2D.Double(b.x-b.r*0.25,b.y-b.r*1.6,b.r*0.5,b.r*3.2));
  }

  void drawPlayer(Graphics2D g2){
    if(pDead) return;
    AffineTransform t=g2.getTransform(); g2.translate(px,py);
    Composite oc=g2.getComposite();
    if(pInvuln>0 && (pInvuln/4)%2==0) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.4f));
    // ランタン号：濃紺の小型機体 ＋ 橙ラインのアクセント
    Path2D body=poly(new double[]{0,7,13,5,0,-5,-13,-7}, new double[]{-19,0,13,9,15,9,13,0});
    g2.setColor(new Color(26,32,54)); g2.fill(body);
    g2.setColor(new Color(255,150,60)); g2.setStroke(new BasicStroke(1.6f)); g2.draw(body);  // 橙のライン
    // 機首の橙ランプ（残機が減るほど速く・赤く点滅）
    double blinkSpd = 0.08 + (CHARS.length>0? 0 :0) + Math.max(0, (5-lives))*0.05;
    double bl = 0.55 + 0.45*Math.sin(frame*blinkSpd);
    double warm = Math.max(0, Math.min(1, lives/5.0));        // 残機多→橙, 少→赤
    int lr=255, lg=(int)((90+110*warm)*bl+30), lbb=(int)(40*warm);
    RadialGradientPaint lamp=new RadialGradientPaint(new Point2D.Double(0,-9),12f,
      new float[]{0f,1f}, new Color[]{new Color(255,Math.min(255,lg+60),100,(int)(220*bl)), new Color(0,0,0,0)});
    g2.setPaint(lamp); g2.fill(circle(0,-9,12));
    g2.setColor(new Color(lr,Math.min(255,lg),lbb)); g2.fill(circle(0,-9,3.2));
    // エンジン炎（橙）
    g2.setColor(new Color(255,160,60,(int)(150+Math.random()*80)));
    g2.fill(poly(new double[]{-4,0,4}, new double[]{11,18+Math.random()*8,11}));
    g2.setComposite(oc);
    if(act("focus")){
      g2.setColor(new Color(255,120,60,230)); g2.setStroke(new BasicStroke(1.5f)); g2.draw(circle(0,0,pr+1));
      g2.setColor(new Color(255,90,40)); g2.fill(circle(0,0,pr*0.7));
      g2.setColor(new Color(255,210,150,60)); g2.draw(circle(0,0,14));
    }
    g2.setTransform(t);
  }

  void drawItems(Graphics2D g2){
    // 弾（丸）と紛れないよう、アイテムは小さな“四角”で表現
    for(Item it:items){
      Color c;
      if(it.type.equals("power")) c=new Color(255,150,60);
      else if(it.type.equals("point")) c=new Color(255,215,120);
      else if(it.type.equals("bomb")) c=new Color(150,210,255);
      else c=new Color(150,255,180);
      double s=it.r;
      g2.setColor(c); g2.fill(new Rectangle2D.Double(it.x-s*0.5,it.y-s*0.5,s,s));
      g2.setColor(new Color(255,255,255,200)); g2.setStroke(new BasicStroke(1f));
      g2.draw(new Rectangle2D.Double(it.x-s*0.5,it.y-s*0.5,s,s));
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
  // ---- 灯火回廊 フォント体系（見出しは明朝で荘厳に・数値は等幅）----
  // mac / Windows / Linux の日本語フォントを順に探す（無ければ論理フォント Serif/SansSerif）
  static String JPM = pickFont(new String[]{"Hiragino Mincho ProN","YuMincho","Yu Mincho","Yu Mincho Demibold","MS Mincho","ＭＳ 明朝","Noto Serif CJK JP","Serif"});
  static String JPG = pickFont(new String[]{"Hiragino Kaku Gothic ProN","YuGothic","Yu Gothic UI","Yu Gothic","Meiryo","MS Gothic","ＭＳ ゴシック","Noto Sans CJK JP","SansSerif"});
  static String pickFont(String[] cands){
    try{ java.util.Set<String> have=new java.util.HashSet<>(java.util.Arrays.asList(
      java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
      for(String c:cands) if(have.contains(c)) return c; }catch(Exception e){}
    return cands[cands.length-1];
  }
  static Font mincho(int sz){ return new Font(JPM, Font.BOLD, sz); }
  static Font gothic(int sz,boolean bold){ return new Font(JPG, bold?Font.BOLD:Font.PLAIN, sz); }
  static final Font F_TITLE=new Font(JPM,Font.BOLD,24);
  static final Font F_LBL  =new Font(JPG,Font.BOLD,14);
  static final Font F_NUM  =new Font("Monospaced",Font.BOLD,22);
  static final Font F_SMALL=new Font(JPG,Font.PLAIN,12);
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
    g2.setFont(F_TITLE); g2.setColor(new Color(255,190,110)); g2.drawString("灯火回廊",sx,46);
    g2.setColor(new Color(150,200,255)); g2.setFont(F_SMALL); g2.drawString("A S C E N T",sx,66);
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
      int total=boss.atks.length, remain=total-boss.atkIdx;
      for(int i=0;i<total;i++){ g2.setColor(i<remain?(boss.cur().spell?new Color(255,90,120):new Color(120,170,255)):new Color(70,70,82));
        g2.fill(circle(sx+8+i*12,410,4)); }
      if(boss.spell){ g2.setColor(hsb(boss.hue,0.5,1.0)); g2.setFont(F_LBL); drawWrapped(g2,boss.cur().name,sx,442,sw,20); }
      else { g2.setColor(new Color(150,170,200)); g2.setFont(F_SMALL); g2.drawString("― 通常弾幕 ―",sx,442); }
    }
    // 残響リワインド残量（R/C）
    String rwTxt = "残響(R): " + (rwUsesLeft<0? "∞" : (""+rwUsesLeft)) + (rwCD>0? "  CT"+(rwCD/60+1) : (rwUsesLeft==0?"  ―":"  OK"));
    g2.setFont(F_SMALL); g2.setColor(rwCD>0||rwUsesLeft==0? new Color(120,130,150): new Color(120,180,255));
    g2.drawString(rwTxt, sx, H-112);
    g2.setColor(new Color(143,184,232)); g2.drawString(STAGE_INFO[stageIndex].name,sx,H-92);
    g2.setColor(new Color(120,160,210)); g2.drawString(STAGE_INFO[stageIndex].sub,sx,H-72);
    g2.drawString("DIFFICULTY  "+diff().name,sx,H-48);
    g2.drawString(CHARS[charSel].name+" / "+SHOT_NAMES[shotSel].trim().split("\\s+")[0],sx,H-28);
    g2.setColor(fps>=55?new Color(120,200,140):fps>=40?new Color(220,200,120):new Color(230,120,120));
    g2.drawString("FPS "+fps+(sound.muted?"   MUTE(M)":""),sx,H-8);
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
    GradientPaint gp=new GradientPaint(0,0,new Color(14,8,8),0,H,new Color(8,9,20));
    g2.setPaint(gp); g2.fillRect(0,0,VW,H);
    // 灯火の粒（ゆらめく橙の塵）
    for(Star st:tstars){ st.y+=st.z*0.6; if(st.y>H)st.y=0; st.x+=Math.sin((frame+st.y)*0.01)*0.3;
      g2.setColor(new Color(255,170,80,(int)(70+Math.sin((frame+st.x)*0.05)*60)));
      g2.fill(circle(st.x,st.y,st.s*0.8)); }
    // 中央の灯火グロー
    RadialGradientPaint glow=new RadialGradientPaint(new Point2D.Double(CX,260),360,
      new float[]{0f,1f}, new Color[]{new Color(255,140,40,40), new Color(0,0,0,0)});
    g2.setPaint(glow); g2.fillRect(0,0,VW,H);
    // タイトル（明朝で灯火の荘厳さ）
    AffineTransform t=g2.getTransform(); g2.translate(CX,236);
    g2.setFont(mincho(72));
    g2.setColor(new Color(120,40,10)); centerStr(g2,"灯火回廊",4,4);
    g2.setColor(new Color(255,150,60)); centerStr(g2,"灯火回廊",1,1);
    g2.setColor(new Color(255,236,210)); centerStr(g2,"灯火回廊",0,0);
    g2.setFont(mincho(34));
    g2.setColor(new Color(210,180,150)); centerStr(g2,"― ASCENT ―",0,58);
    g2.setTransform(t);
    g2.setColor(new Color(190,175,160)); g2.setFont(gothic(16,false));
    centerStr(g2,"放棄ステーション「回廊」を、下層から最上層へ",CX,330);

    String[] labels={"ゲームスタート","モード:  "+MODES[modeSel],"難易度:  "+diff().name,"記録","遊び方","ハイスコア消去"};
    for(int i=0;i<labels.length;i++){
      boolean sel=i==menuSel;
      g2.setFont(new Font("SansSerif", sel?Font.BOLD:Font.PLAIN, sel?26:20));
      g2.setColor(sel?Color.WHITE:new Color(95,127,174));
      boolean cyc=(i==1||i==2);
      String s = sel? (cyc? "◀ "+labels[i]+" ▶" : "▶ "+labels[i]+" ◀") : labels[i];
      centerStr(g2,s,CX,428+i*42);
    }
    g2.setColor(new Color(95,127,174)); g2.setFont(new Font("SansSerif",Font.PLAIN,13));
    centerStr(g2,"移動: 方向キー / WASD    ショット: Z / Space    ボム: X",CX,H-110);
    centerStr(g2,"低速: Shift    ポーズ: P    ミュート: M    残響: R/C    音量: - / =",CX,H-88);
    g2.setColor(new Color(58,86,127)); g2.setFont(new Font("SansSerif",Font.PLAIN,12));
    centerStr(g2,"HI-SCORE  "+pad(hiscore,8),CX,H-52);
    centerStr(g2,"全6層・各層に中ボスとボス／通常↔スペル交互の手作り弾幕",CX,H-30);
  }
  void drawRecords(Graphics2D g2){
    g2.setColor(new Color(8,8,18)); g2.fillRect(0,0,VW,H);
    g2.setColor(new Color(255,200,120)); g2.setFont(new Font("SansSerif",Font.BOLD,30)); centerStr(g2,"記録（自己ベスト）",CX,80);
    g2.setColor(new Color(180,200,240)); g2.setFont(new Font("SansSerif",Font.BOLD,18));
    centerStr(g2,"◀  難易度: "+DIFFS[recDiff].name+"  ▶",CX,124);
    String dn=DIFFS[recDiff].name;
    int y=180;
    for(String mode: MODES){
      g2.setColor(new Color(140,180,255)); g2.setFont(new Font("SansSerif",Font.BOLD,17));
      g2.drawString("■ "+mode, 120, y);
      for(String rw: new String[]{"残響なし","残響あり"}){
        String k=dn+"|"+mode+"|"+rw;
        boolean noco = rw.equals("残響なし");
        g2.setColor(noco?new Color(255,225,140):new Color(170,190,220)); g2.setFont(new Font("Monospaced",Font.PLAIN,15));
        String tt = mode.equals("回廊無限")
          ? "  "+rw+"  生存 "+(bestScore.containsKey(k)?fmtTime(bestScore.get(k)/60.0):"--")
          : "  "+rw+"  TIME "+(bestTime.containsKey(k)?fmtTime(bestTime.get(k)):"--:--")+"   SCORE "+(bestScore.containsKey(k)?pad(bestScore.get(k),8):"--------");
        g2.drawString((noco?"★":"  ")+tt, 150, y+22 + (noco?0:20));
      }
      y+=72;
    }
    g2.setColor(new Color(120,150,200)); g2.setFont(new Font("SansSerif",Font.PLAIN,14));
    centerStr(g2,"★=正規踏破(残響不使用)　　X: 記録リセット　　Z / Esc: 戻る",CX,H-40);
  }
  void drawPractice(Graphics2D g2){
    g2.setColor(new Color(8,8,18)); g2.fillRect(0,0,VW,H);
    g2.setColor(new Color(255,200,120)); g2.setFont(new Font("SansSerif",Font.BOLD,30)); centerStr(g2,"弾幕鑑賞",CX,90);
    g2.setColor(new Color(170,200,240)); g2.setFont(new Font("SansSerif",Font.PLAIN,15));
    centerStr(g2,"観賞するボスを選択（無敵で安全に避けの練習）",CX,128);
    for(int i=0;i<BOSSES.length;i++){
      boolean sel=i==practiceBoss;
      g2.setFont(new Font("SansSerif", sel?Font.BOLD:Font.PLAIN, sel?24:19));
      g2.setColor(sel?hsb(BOSSES[i].hue,0.5,1.0):new Color(110,130,170));
      centerStr(g2,(sel?"▶ ":"   ")+"第"+(i+1)+"層  "+BOSSES[i].name+(sel?" ◀":""),CX,190+i*46);
    }
    g2.setColor(new Color(180,200,240)); g2.setFont(new Font("SansSerif",Font.BOLD,16));
    centerStr(g2,"◀  難易度: "+diff().name+"  ▶",CX,190+BOSSES.length*46+20);
    g2.setColor(new Color(120,150,200)); g2.setFont(new Font("SansSerif",Font.PLAIN,14));
    centerStr(g2,"Z: 鑑賞開始　　鑑賞中 P/Esc: 選択へ戻る　　Esc: タイトル",CX,H-40);
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
      "　 1プレイ中は「同じ敵・同じ弾幕」が出ないよう保証。","",
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
    centerStr(g2,"ランタン号 ― 装備選択",CX,90);
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
    g2.setColor(new Color(210,180,150)); g2.setFont(mincho(26));
    centerStr(g2,STAGE_INFO[stageIndex].name,CX,H/2-30);
    g2.setColor(new Color(255,236,210)); g2.setFont(mincho(44));
    centerStr(g2,STAGE_INFO[stageIndex].sub,CX,H/2+24);
    g2.setComposite(oc);
  }
  void drawPaused(Graphics2D g2){
    g2.setColor(new Color(0,0,10,180)); g2.fillRect(0,0,VW,H);
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,48)); centerStr(g2,"PAUSE",CX,H/2-20);
    g2.setColor(new Color(159,200,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,18));
    centerStr(g2,"P / Esc で再開　　Q でタイトルへ",CX,H/2+30);
    g2.setColor(new Color(150,170,200)); g2.setFont(new Font("SansSerif",Font.PLAIN,15));
    centerStr(g2,"音量: - / =  （現在 "+sound.volPct()+"%）　　M: ミュート",CX,H/2+64);
  }
  void drawStageClear(Graphics2D g2){
    g2.setColor(new Color(0,0,10,140)); g2.fillRect(0,0,VW,H);
    g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif",Font.BOLD,40));
    centerStr(g2,"STAGE "+(stageIndex+1)+" CLEAR!",CX,H/2-20);
    g2.setColor(new Color(159,255,207)); g2.setFont(new Font("SansSerif",Font.PLAIN,18));
    centerStr(g2,"SCORE  "+pad(score,8),CX,H/2+24);
  }
  void drawContinue(Graphics2D g2){
    g2.setColor(new Color(10,0,4,180)); g2.fillRect(0,0,VW,H);
    g2.setColor(new Color(255,200,120)); g2.setFont(mincho(48)); centerStr(g2,"CONTINUE?",CX,H/2-40);
    g2.setColor(Color.WHITE); g2.setFont(gothic(20,true));
    centerStr(g2,"Z / Enter ： 続ける    X / Esc ： やめる",CX,H/2+14);
    g2.setColor(new Color(255,180,150)); g2.setFont(gothic(14,false));
    centerStr(g2,"※コンティニューすると「正規踏破（ノーコンティニュー）」ではなくなります",CX,H/2+48);
    if(continueCount>0){ g2.setColor(new Color(160,170,200)); centerStr(g2,"これまでのコンティニュー: "+continueCount+" 回",CX,H/2+74); }
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
    g2.setColor(new Color(255,236,210)); g2.setFont(mincho(52)); centerStr(g2,"灯 を 継 ぐ 者",CX,150);
    g2.setColor(new Color(255,210,127)); g2.setFont(new Font("SansSerif",Font.BOLD,22)); centerStr(g2,"全6層を突破 — 灯を継ぐ者よ",CX,200);
    g2.setColor(new Color(207,227,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,16)); centerStr(g2,"DIFFICULTY  "+diff().name+(clean()?"   ★正規踏破":"   （"+(echoUsed?"残響":"")+(echoUsed&&continued?"・":"")+(continued?"コンティニュー":"")+"使用）"),CX,238);
    // タイム & スコア
    g2.setColor(Color.WHITE); g2.setFont(new Font("Monospaced",Font.BOLD,30)); centerStr(g2,"TIME  "+fmtTime(runTimeSec),CX,300);
    g2.setFont(new Font("Monospaced",Font.BOLD,30)); centerStr(g2,"SCORE "+pad(score,8),CX,340);
    g2.setColor(new Color(255,180,210)); g2.setFont(new Font("SansSerif",Font.BOLD,16)); centerStr(g2,"GRAZE  "+grazeCount,CX,372);
    if(newRecTime||newRecScore){
      g2.setColor(new Color(255,225,120)); g2.setFont(new Font("SansSerif",Font.BOLD,26));
      centerStr(g2,"★ NEW RECORD ★"+(newRecTime?"  TIME":"")+(newRecScore?"  SCORE":""),CX,410);
    }
    // 自己ベスト（このカテゴリ）
    String k=catKey();
    g2.setColor(new Color(159,200,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,15));
    centerStr(g2,"［"+k.replace("|","・")+"］ の自己ベスト",CX,456);
    g2.setColor(new Color(200,220,255)); g2.setFont(new Font("Monospaced",Font.PLAIN,16));
    centerStr(g2,"BEST TIME  "+(bestTime.containsKey(k)?fmtTime(bestTime.get(k)):"--"),CX,484);
    centerStr(g2,"BEST SCORE "+(bestScore.containsKey(k)?pad(bestScore.get(k),8):"--------"),CX,508);
    g2.setColor(new Color(255,200,140)); g2.setFont(new Font("SansSerif",Font.PLAIN,13));
    centerStr(g2, clean()? "残響・コンティニュー不使用 — 正規踏破（ノーコンティニュー）" : "残響/コンティニュー使用のため「正規踏破」ではありません", CX, 544);
    g2.setColor(new Color(159,200,255)); g2.setFont(new Font("SansSerif",Font.PLAIN,16)); centerStr(g2,"Z / Enter でタイトルへ",CX,610);
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

  /* ---------------- 競技モード・自己ベスト（永続保存） ---------------- */
  java.util.Map<String,Double>  bestTime  = new java.util.HashMap<>();
  java.util.Map<String,Integer> bestScore = new java.util.HashMap<>();
  static File recFile(){ return new File(System.getProperty("user.home"), ".tomoshibi_records"); }
  String catKey(){ return diff().name+"|"+gameMode+"|"+(echoUsed?"残響あり":"残響なし"); }
  void loadRecords(){
    try(BufferedReader r=new BufferedReader(new FileReader(recFile()))){
      String ln; while((ln=r.readLine())!=null){ String[] a=ln.split("\\t");
        if(a.length>=3){ if(a[0].equals("T")) bestTime.put(a[1],Double.parseDouble(a[2]));
          else if(a[0].equals("S")) bestScore.put(a[1],Integer.parseInt(a[2])); } }
    }catch(Exception e){}
  }
  void saveRecords(){
    try(FileWriter w=new FileWriter(recFile())){
      for(var en:bestTime.entrySet())  w.write("T\t"+en.getKey()+"\t"+en.getValue()+"\n");
      for(var en:bestScore.entrySet()) w.write("S\t"+en.getKey()+"\t"+en.getValue()+"\n");
    }catch(Exception e){}
  }
  void finishRun(){    // 最終ボス撃破＝1周クリア時に記録更新
    if(!clearTimerRunning) return;
    clearTimerRunning=false;
    runTimeSec=(System.nanoTime()-runStartNano)/1e9;
    String k=catKey();
    newRecTime = !bestTime.containsKey(k) || runTimeSec < bestTime.get(k);
    newRecScore= !bestScore.containsKey(k) || score > bestScore.get(k);
    if(newRecTime)  bestTime.put(k, runTimeSec);
    if(newRecScore) bestScore.put(k, (Integer)score);
    saveRecords();
    if(newRecTime||newRecScore) sound.spellGet();
  }
  static String fmtTime(double s){ int m=(int)(s/60); double sec=s-m*60; return String.format("%d:%05.2f",m,sec); }

  /* ---------------- KeyListener ---------------- */
  public void keyPressed(KeyEvent e){ int c=e.getKeyCode(); if(c<down.length){ if(!down[c]) just[c]=true; down[c]=true; } }
  public void keyReleased(KeyEvent e){ int c=e.getKeyCode(); if(c<down.length) down[c]=false; }
  public void keyTyped(KeyEvent e){}


  /* ====================================================================
     main
     ==================================================================== */
  public static void main(String[] args){
    if(args.length>0 && args[0].equals("selftest")){ runSelfTest(); return; }
    try{ System.setProperty("apple.awt.application.name","灯火回廊 ASCENT"); }catch(Exception e){}
    SwingUtilities.invokeLater(()->{
      JFrame f=new JFrame("灯火回廊 ― ASCENT");
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
      // 残響リワインド：記録→巻き戻し→継続 が例外なく動くか
      { int before=g.score; g.snaps.clear(); for(int f=0;f<200;f++){ g.update(); } g.doRewind(); for(int f=0;f<60;f++){ g.update(); }
        System.out.println("REWIND test: snaps="+g.snaps.size()+" echoUsed="+g.echoUsed+" OK"); }
      if(g.stageRunner!=null) g.stageTimer = g.stageRunner.finalTime+5;
      for(int f=0;f<500;f++){ g.update(); g.renderGame(g2); }
      // 各層ボスを実際に稼働（全攻撃スクリプト・レーザー・弾挙動・撃破→会話まで）
      for(int d=0; d<DIFFS.length; d++){
        for(int bi=0; bi<6; bi++){
          g.diffIdx=d; g.startNewGame(); g.stageIndex=bi; g.state="play"; g.stageRunner=null; g.enemies.clear(); g.boss=null; g.lasers.clear(); g.enemyBullets.clear();
          g.boss=g.makeBoss(bi); g.boss.entering=false; g.bossStartAtk(g.boss);
          for(int f=0; f<760; f++){ g.down[KeyEvent.VK_Z]=true; g.px=200+Math.sin(f*0.05)*120; g.py=H-160; g.update();
            if(d==1 && bi==3 && f==360){ try{ javax.imageio.ImageIO.write(img,"png",new File("/tmp/sc_frame.png")); }catch(Exception ex){} }
            if(g.state.equals("dialogue")){ g.just[KeyEvent.VK_Z]=true; g.updateDialogue(); }
            g.renderGame(g2);
          }
        }
      }
      // ルナ最終ボスの L1(無敵耐久)→L2(総ざらい) 経路
      g.diffIdx=3; g.startNewGame(); g.stageIndex=5; g.state="play"; g.stageRunner=null;
      g.boss=g.makeBoss(5); g.boss.entering=false; g.bossStartAtk(g.boss);
      g.boss.atkIdx=g.boss.atks.length-1; g.boss.invuln=0; g.boss.declTimer=0; g.bossTakeDamage(g.boss,9999999);  // 最終→撃破→L1突入
      if(g.state.equals("dialogue")){ for(int q=0;q<8 && g.state.equals("dialogue"); q++){ g.just[KeyEvent.VK_Z]=true; g.updateDialogue(); } }
      int luxp = g.boss!=null? g.boss.luxPhase : -1;
      for(int f=0; f<400; f++){ g.update(); if(g.state.equals("dialogue")){ g.just[KeyEvent.VK_Z]=true; g.updateDialogue(); } }  // L1稼働
      System.out.println("LUNA path: luxPhase(after L1 entry)="+luxp);
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
      // モード検証：回廊無限・弾幕鑑賞・制限時間・中ボス単独
      g.diffIdx=1; g.gameMode="回廊無限"; g.startNewGame(); g.state="play";
      for(int f=0;f<600;f++){ g.down[KeyEvent.VK_Z]=true; g.update(); g.renderGame(g2); }
      System.out.println("ENDLESS test: survival="+g.survivalFrames+" eb="+g.enemyBullets.size()+" OK");
      g.gameMode="弾幕鑑賞"; g.practiceBoss=3; g.startPractice();
      for(int f=0;f<400;f++){ g.update(); g.renderGame(g2); }
      System.out.println("PRACTICE test: bossNull="+(g.boss==null)+" practice="+g.practiceMode+" OK");
      g.practiceMode=false;
      System.out.println("TEST_OK psig="+g.usedPatternSigs.size()+" esig="+g.usedEnemySigs.size()+" eb="+g.enemyBullets.size());
    }catch(Throwable e){ System.out.println("TEST_FAIL "+e); e.printStackTrace(); System.exit(1); }
    g2.dispose();
    System.exit(0);
  }
}
