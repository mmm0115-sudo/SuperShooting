#!/bin/bash
# Stellar Cascade 起動スクリプト（.app が開けない場合の予備）
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA=""
for c in /usr/bin/java "$JAVA_HOME/bin/java" "$(/usr/libexec/java_home 2>/dev/null)/bin/java"; do
  if [ -x "$c" ]; then JAVA="$c"; break; fi
done
[ -z "$JAVA" ] && JAVA="java"
exec "$JAVA" -Xdock:name="Stellar Cascade" -Dsun.java2d.metal=true -jar "$DIR/StellarCascade.jar"
