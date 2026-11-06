#!/system/bin/sh
while true; do
  CLASSPATH=/data/local/tmp/whpx_input.jar app_process /system/bin WhpxInputDaemon
  sleep 1
done
