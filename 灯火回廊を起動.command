#!/bin/bash
# 灯火回廊 起動スクリプト（.app が開けない場合の予備）
# jar は .app バンドル内の1個だけを使う（重複を持たない）
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/StellarCascade.app/Contents/Resources/灯火回廊.jar"
[ -f "$JAR" ] || JAR="$DIR/灯火回廊.jar"   # 無ければルートの jar
JAVA=""
for c in /usr/bin/java "$JAVA_HOME/bin/java" "$(/usr/libexec/java_home 2>/dev/null)/bin/java"; do
  if [ -x "$c" ]; then JAVA="$c"; break; fi
done
[ -z "$JAVA" ] && JAVA="java"
exec "$JAVA" -Xdock:name="灯火回廊" -Dsun.java2d.metal=true -jar "$JAR"
