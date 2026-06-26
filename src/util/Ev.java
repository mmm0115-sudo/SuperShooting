package util;

/** タイムラインイベント（指定時刻に fn を実行） */
public class Ev { public int t; public Runnable fn; public Ev(int t,Runnable f){this.t=t;fn=f;} }
