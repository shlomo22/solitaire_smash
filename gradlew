#!/bin/sh
APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -z "$JAVA_HOME" ] && [ -x "$APP_HOME/.jdk/jdk-17.0.20+8/bin/java" ]; then
  JAVA_HOME="$APP_HOME/.jdk/jdk-17.0.20+8"
fi

if [ -n "$JAVA_HOME" ] ; then
  JAVACMD=$JAVA_HOME/bin/java
else
  JAVACMD=java
fi
exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
