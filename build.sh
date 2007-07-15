#!/bin/sh
# $Id$
LIBS=`echo ./lib/*.jar | tr ' ' ':'`
$ANT_HOME/bin/ant -buildfile installer.xml
$JAVA_HOME/bin/java -cp "$LIBS:$JAVA_HOME/lib/tools.jar" -Djava.library.path=lib -Dlog4j.configuration=file:log4j-build.properties -Dincludes="src/*/plugin.xml,src/plugins/*/plugin.xml" org.drftpd.tools.installer.Wrapper