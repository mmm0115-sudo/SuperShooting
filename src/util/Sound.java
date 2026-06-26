package util;

import javax.sound.sampled.*;
import java.util.*;

/** 自前シンセのSFX/BGMミキサ（外部ファイル不要） */
public class Sound {
  static final int RATE=44100;
  SourceDataLine line; volatile boolean ok=false; public volatile boolean muted=false;
  final List<Voice> voices = Collections.synchronizedList(new ArrayList<>());
  Thread th; int shotCd=0;
  static final int[][] SCALES = {
    {0,2,3,5,7,8,10},{0,2,4,7,9},{0,3,5,6,7,10},{0,1,4,5,7,8,11},{0,2,3,7,8},{0,2,4,5,7,9,11}
  };
  public double volume=0.6;   // マスター音量(0〜1)
  int[] scale = SCALES[0]; double bassRoot=82; boolean bgmOn=false; int stepFrames=8; int bgmStep=0;
  java.util.Random rnd=new java.util.Random();

  public void init(){
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
        s*=volume; if(s>1)s=1; if(s<-1)s=-1;
        short val=(short)(s*32767);
        buf[i*2]=(byte)(val&0xff); buf[i*2+1]=(byte)((val>>8)&0xff);
      }
      try{ line.write(buf,0,buf.length); }catch(Throwable e){ break; }
    }
  }
  void add(Voice v){ if(ok && !muted){ if(voices.size()<48) voices.add(v); } }
  void tone(double freq,double dur,int type,double vol,double slide){ add(new Voice(freq,dur,type,vol,slide,false)); }
  void noise(double dur,double vol){ add(new Voice(0,dur,0,vol,0,true)); }
  public void tick(){ if(shotCd>0) shotCd--; if(grazeCd>0) grazeCd--; }
  // SFX
  public void shot(){ if(shotCd>0) return; shotCd=4; tone(880,0.05,0,0.05,0.6); }
  public void enemyHit(){ tone(220,0.04,0,0.05,0.8); }
  public void explode(){ noise(0.22,0.28); tone(120,0.3,1,0.16,0.4); }
  public void bossHit(){ noise(0.05,0.05); }
  public void bomb(){ noise(0.55,0.32); tone(80,0.6,1,0.22,0.5); }
  public void death(){ noise(0.45,0.38); tone(300,0.55,1,0.28,0.3); }
  public void menu(){ tone(660,0.06,0,0.12,1.2); }
  public void select(){ tone(990,0.1,0,0.15,1.3); }
  public void bossDown(){ tone(330,1.0,1,0.28,0.4); noise(0.9,0.28); }
  public void power(){ tone(1200,0.08,2,0.16,1.4); }
  public void extend(){ tone(523,0.12,2,0.18,1.0); tone(784,0.18,2,0.18,1.0); }
  int grazeCd=0;
  public void graze(){ if(grazeCd>0) return; grazeCd=3; tone(2400,0.03,2,0.04,1.1); }
  public void spellDeclare(){ tone(220,0.6,1,0.16,2.0); tone(330,0.6,2,0.12,2.0); noise(0.2,0.12); }
  public void spellGet(){ tone(523,0.5,2,0.18,1.0); tone(659,0.5,2,0.16,1.0); tone(784,0.5,2,0.16,1.0); tone(1046,0.6,2,0.18,1.0); }
  public void rewind(){ tone(1400,0.18,2,0.18,0.5); tone(900,0.22,2,0.12,0.6); }   // ピンッ
  public boolean toggleMute(){ muted=!muted; return muted; }
  public void setSilent(boolean s){ muted=s; }
  public int addVolume(double d){ volume=Math.max(0,Math.min(1.0,volume+d)); if(volume>0) muted=false; return (int)Math.round(volume*100); }
  public int volPct(){ return (int)Math.round(volume*100); }
  // BGM
  public void startBGM(int idx){ scale=SCALES[idx%SCALES.length]; bassRoot=82*Math.pow(2,(idx%3)/12.0); bgmOn=true; bgmStep=0; stepFrames=Math.max(5,9-idx/2); }
  public void stopBGM(){ bgmOn=false; }
  public void bgmTick(int frame,int idx){
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
