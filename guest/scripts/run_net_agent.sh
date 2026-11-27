#!/system/bin/sh
# Android 12 changed the NetworkAgent API; older releases use the legacy agent.
CLS=DefaultNetworkAgent
if [ "$(getprop ro.build.version.sdk)" -lt 31 ] 2>/dev/null; then
  CLS=DefaultNetworkAgentLegacy
  # Registering before ConnectivityService has its metrics service stops system_server (Android 9).
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
fi
while true; do
  CLASSPATH=/data/local/tmp/default_net_agent.jar app_process /system/bin $CLS
  sleep 1
done
