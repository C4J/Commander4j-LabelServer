#!/bin/sh

# The label server resolves everything it needs relative to the working directory
# (./xml/config, ./logs, ./web, ./labeller_cmd), so run from the install folder.
BASEDIR=$(dirname "$0")
cd "$BASEDIR" || exit 1

# exec, so the JVM replaces this shell and keeps its process id: a SIGTERM (a service stop,
# or Ctrl-C) then reaches Java directly and its shutdown hook runs. Without exec the signal
# only kills the shell and leaves the JVM orphaned, still holding the labeller connections
# and the web viewer's port.
#
# -Dlog4j2.shutdownHookEnabled=false  lets our own shutdown hook stop log4j2 LAST, so the
#                                     teardown lines still reach the log file.
exec java -Dlog4j2.shutdownHookEnabled=false \
          -cp ./LabelServer.jar:./lib/devonly/i4jruntime.jar \
          com.commander4j.labeller.Service
