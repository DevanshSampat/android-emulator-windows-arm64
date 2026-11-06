#!/system/bin/sh
while true; do
  CLASSPATH=/data/local/tmp/default_net_agent.jar app_process /system/bin DefaultNetworkAgent
  sleep 1
done
