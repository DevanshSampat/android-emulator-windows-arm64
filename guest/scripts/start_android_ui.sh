#!/system/bin/sh


# ----------------------------------------------------
# 1. Bring up network FIRST before any graphics attempt
# ----------------------------------------------------
ifconfig lo 127.0.0.1 up 2>/dev/null
ip link set lo up 2>/dev/null
ifconfig eth0 10.0.2.15 netmask 255.255.255.0 up 2>/dev/null
setprop service.adb.tcp.port 5555 2>/dev/null
ip route add 10.0.2.0/24 dev eth0 table local 2>/dev/null
ip route add default via 10.0.2.2 dev eth0 table local 2>/dev/null
ip route add default via 10.0.2.2 dev eth0 table main 2>/dev/null
ip rule del pref 32000 2>/dev/null
ip rule add from all lookup main pref 5000 2>/dev/null
setprop net.dns1 10.0.2.3 2>/dev/null
setprop net.eth0.dns1 10.0.2.3 2>/dev/null
setprop net.dns2 "" 2>/dev/null
setprop net.eth0.dns2 "" 2>/dev/null
if [ -f /data/local/tmp/run_net_agent.sh ] && ! pgrep -f DefaultNetworkAgent >/dev/null; then
  nohup sh /data/local/tmp/run_net_agent.sh </dev/null >/data/local/tmp/net_agent.log 2>&1 &
fi

# ----------------------------------------------------
# 2. Start native gfx_forwarder immediately
# ----------------------------------------------------

if [ -f /data/local/tmp/gfx_forwarder ] && ! pgrep -f gfx_forwarder >/dev/null; then
  /data/local/tmp/gfx_forwarder 5000 10.0.2.2 "$(getprop ro.boot.whpx.gfx_port)" &
  sleep 0.5
fi

# ----------------------------------------------------
# 3. Mount shims and mock hardware interfaces (run once)
# ----------------------------------------------------
if [ ! -f /dev/whpx_shims_mounted ]; then
  # Mock power supply (whpx_init already does this before the health HAL
  # starts; doing it only here, after boot, let BatteryService shut down).
  if [ ! -f /dev/whpx_power_mocked ]; then
    # Mount mock power supply in sysfs so healthd immediately detects 100% battery & AC
    mount -t tmpfs tmpfs /sys/class/power_supply 2>/dev/null
    mkdir -p /sys/class/power_supply/ac /sys/class/power_supply/battery 2>/dev/null
    echo Mains > /sys/class/power_supply/ac/type 2>/dev/null
    echo 1 > /sys/class/power_supply/ac/online 2>/dev/null
    echo Battery > /sys/class/power_supply/battery/type 2>/dev/null
    echo 1 > /sys/class/power_supply/battery/present 2>/dev/null
    echo 100 > /sys/class/power_supply/battery/capacity 2>/dev/null
    echo Normal > /sys/class/power_supply/battery/capacity_level 2>/dev/null
    echo Charging > /sys/class/power_supply/battery/status 2>/dev/null
    echo Good > /sys/class/power_supply/battery/health 2>/dev/null
    echo 100 > /sys/class/power_supply/battery/health_index 2>/dev/null
    echo Good > /sys/class/power_supply/battery/health_status 2>/dev/null
    echo 4200000 > /sys/class/power_supply/battery/voltage_now 2>/dev/null
    echo 4200000 > /sys/class/power_supply/battery/voltage_max 2>/dev/null
    echo 4200000 > /sys/class/power_supply/battery/voltage_max_design 2>/dev/null
    echo 3000000 > /sys/class/power_supply/battery/voltage_min_design 2>/dev/null
    echo 500000 > /sys/class/power_supply/battery/current_now 2>/dev/null
    echo 500000 > /sys/class/power_supply/battery/current_avg 2>/dev/null
    echo 1000000 > /sys/class/power_supply/battery/current_max 2>/dev/null
    echo 250 > /sys/class/power_supply/battery/temp 2>/dev/null
    echo Li-ion > /sys/class/power_supply/battery/technology 2>/dev/null
    echo 5000000 > /sys/class/power_supply/battery/charge_full 2>/dev/null
    echo 5000000 > /sys/class/power_supply/battery/charge_full_design 2>/dev/null
    echo 5000000 > /sys/class/power_supply/battery/charge_counter 2>/dev/null
    echo 1 > /sys/class/power_supply/battery/cycle_count 2>/dev/null
    echo Google > /sys/class/power_supply/battery/manufacturer 2>/dev/null
    echo Pixel > /sys/class/power_supply/battery/model_name 2>/dev/null
    chmod -R 755 /sys/class/power_supply 2>/dev/null
    chmod 644 /sys/class/power_supply/*/* 2>/dev/null
    chcon -R u:object_r:sysfs_batteryinfo:s0 /sys/class/power_supply 2>/dev/null
  fi

  # Timezone property
  setprop persist.sys.timezone Asia/Kolkata 2>/dev/null

  # Ensure WebView GPU compositing is enabled (clean up any legacy debug flags)
  rm -f /data/local/tmp/webview-command-line /data/local/tmp/chrome-command-line 2>/dev/null

  # SurfaceFlinger rc fix
  if [ -f /system/etc/init/surfaceflinger.rc ]; then
    grep -v "reboot_on_failure" /system/etc/init/surfaceflinger.rc > /dev/whpx_sf.rc
    chmod 644 /dev/whpx_sf.rc
    chcon u:object_r:system_file:s0 /dev/whpx_sf.rc 2>/dev/null
    mount -o bind /dev/whpx_sf.rc /system/etc/init/surfaceflinger.rc 2>/dev/null
  fi

  # Legacy fixed-offset graphics shims. Skipped when whpx_init already
  # overlaid this image's own auto-patched vendor binaries at boot (before any
  # HAL started); re-mounting or restarting the stack then only breaks it.
  if [ ! -f /dev/whpx_gfxshim_mounted ]; then
    # Mount patched graphics and sensor shims
    if [ -d /data/local/tmp/patched/patched ]; then
      cp -f /data/local/tmp/patched/patched/* /data/local/tmp/patched/ 2>/dev/null
      rm -rf /data/local/tmp/patched/patched 2>/dev/null
    fi

    mkdir -p /dev/whpx_libs
    chmod 755 /dev/whpx_libs 2>/dev/null
    cp -f /data/local/tmp/patched/* /dev/whpx_libs/ 2>/dev/null
    chmod 644 /dev/whpx_libs/*.so /data/local/tmp/aapm.xml 2>/dev/null
    chmod 755 /dev/whpx_libs/allocator.ranchu 2>/dev/null
    chcon u:object_r:same_process_hal_file:s0 /dev/whpx_libs/libqemupipe.ranchu.so /dev/whpx_libs/libOpenglSystemCommon.so /dev/whpx_libs/libEGL_emulation.so /dev/whpx_libs/vulkan.ranchu.so /dev/whpx_libs/mapper.ranchu.so /dev/whpx_libs/lib_renderControl_enc.so 2>/dev/null
    chcon u:object_r:vendor_file:s0 /dev/whpx_libs/sensors.ranchu.so 2>/dev/null
    chcon u:object_r:hal_graphics_allocator_default_exec:s0 /dev/whpx_libs/allocator.ranchu 2>/dev/null
    cp -f /data/local/tmp/aapm.xml /dev/whpx_aapm.xml 2>/dev/null
    chmod 644 /dev/whpx_aapm.xml 2>/dev/null
    chcon u:object_r:system_file:s0 /dev/whpx_aapm.xml 2>/dev/null

    chmod 666 /dev/vsock* /dev/vhost-vsock* 2>/dev/null
    SDK_VER=$(getprop ro.build.version.sdk)
    if [ "$SDK_VER" = "33" ] && [ -d /data/local/tmp/patched_p33 ]; then
      mount -o bind /data/local/tmp/patched_p33/libqemupipe.ranchu.so /vendor/lib64/libqemupipe.ranchu.so 2>/dev/null
      mount -o bind /data/local/tmp/patched_p33/libOpenglSystemCommon.so /vendor/lib64/libOpenglSystemCommon.so 2>/dev/null
      mount -o bind /data/local/tmp/patched_p33/libEGL_emulation.so /vendor/lib64/egl/libEGL_emulation.so 2>/dev/null
      mount -o bind /data/local/tmp/patched_p33/libOpenglCodecCommon.so /vendor/lib64/libOpenglCodecCommon.so 2>/dev/null
      if [ -f /data/local/tmp/mapper3.so ]; then
        chcon u:object_r:same_process_hal_file:s0 /data/local/tmp/mapper3.so 2>/dev/null
        mount -o bind /data/local/tmp/mapper3.so /vendor/lib64/hw/android.hardware.graphics.mapper@3.0-impl-ranchu.so 2>/dev/null
      fi
      if [ -f /data/local/tmp/framework_patched.jar ]; then
        chcon u:object_r:system_file:s0 /data/local/tmp/framework_patched.jar 2>/dev/null
        mount -o bind /data/local/tmp/framework_patched.jar /system/framework/framework.jar 2>/dev/null
      fi
    else
      mount -o bind /dev/whpx_libs/libqemupipe.ranchu.so /vendor/lib64/libqemupipe.ranchu.so 2>/dev/null
      mount -o bind /dev/whpx_libs/libOpenglSystemCommon.so /vendor/lib64/libOpenglSystemCommon.so 2>/dev/null
      mount -o bind /dev/whpx_libs/libEGL_emulation.so /vendor/lib64/egl/libEGL_emulation.so 2>/dev/null
      mount -o bind /dev/whpx_libs/lib_renderControl_enc.so /vendor/lib64/lib_renderControl_enc.so 2>/dev/null
      if [ -z "$SDK_VER" ] || [ "$SDK_VER" -ge 34 ]; then
        mount -o bind /dev/whpx_libs/mapper.ranchu.so /vendor/lib64/hw/mapper.ranchu.so 2>/dev/null
        mount -o bind /dev/whpx_libs/mapper.ranchu.so /vendor/lib64/hw/android.hardware.graphics.mapper@3.0-impl-ranchu.so 2>/dev/null
        mount -o bind /dev/whpx_libs/allocator.ranchu /vendor/bin/hw/android.hardware.graphics.allocator-service.ranchu 2>/dev/null
        mount -o bind /dev/whpx_libs/allocator.ranchu /vendor/bin/hw/android.hardware.graphics.allocator@3.0-service.ranchu 2>/dev/null
        mount -o bind /dev/whpx_libs/sensors.ranchu.so /vendor/lib64/hw/android.hardware.sensors@2.1-impl.ranchu.so 2>/dev/null
        mount -o bind /dev/whpx_aapm.xml /system/etc/advanced-protection-config/advanced-protection-config.xml 2>/dev/null
      fi
    fi

    # Disable vulkan so Android cleanly uses hardware OpenGL ES
    touch /dev/whpx_no_vulkan
    chmod 644 /dev/whpx_no_vulkan
    chcon u:object_r:same_process_hal_file:s0 /dev/whpx_no_vulkan 2>/dev/null
    mount -o bind /dev/whpx_no_vulkan /vendor/lib64/hw/vulkan.ranchu.so 2>/dev/null
  fi

  # Ensure media codecs and Codec2 default service are running cleanly
  if [ -f /data/local/tmp/system_heap.ko ]; then
    insmod /data/local/tmp/system_heap.ko 2>/dev/null
    chmod 666 /dev/dma_heap/system 2>/dev/null
  fi
  echo '<Included></Included>' > /dev/whpx_empty_codecs.xml
  chmod 644 /dev/whpx_empty_codecs.xml 2>/dev/null
  chcon u:object_r:vendor_configs_file:s0 /dev/whpx_empty_codecs.xml 2>/dev/null
  # mount -o bind /dev/whpx_empty_codecs.xml /vendor/etc/media_codecs_google_video.xml (disabled for video playback)
  if [ -f /data/local/tmp/media_codecs_nogoldfish.xml ]; then
    mount -o bind /data/local/tmp/media_codecs_nogoldfish.xml /vendor/etc/media_codecs.xml 2>/dev/null
  fi
  start android-hardware-media-c2-goldfish 2>/dev/null

  # Handle Bluetooth and UWB permissions
  umount /vendor/etc/permissions/android.hardware.bluetooth.xml 2>/dev/null
  umount /vendor/etc/permissions/android.hardware.bluetooth_le.xml 2>/dev/null
  umount /vendor/etc/permissions/android.hardware.uwb.xml 2>/dev/null
  umount /vendor/etc/permissions/handheld_core_hardware.xml 2>/dev/null
  if [ -f /data/local/tmp/empty_perm.xml ]; then
    mount -o bind /data/local/tmp/empty_perm.xml /vendor/etc/permissions/android.hardware.bluetooth.xml 2>/dev/null
    mount -o bind /data/local/tmp/empty_perm.xml /vendor/etc/permissions/android.hardware.bluetooth_le.xml 2>/dev/null
    mount -o bind /data/local/tmp/empty_perm.xml /vendor/etc/permissions/android.hardware.uwb.xml 2>/dev/null
  fi
  if [ -f /data/local/tmp/handheld_core_hardware.xml ]; then
    mount -o bind /data/local/tmp/handheld_core_hardware.xml /vendor/etc/permissions/handheld_core_hardware.xml 2>/dev/null
  fi
  setprop ro.radio.noril yes
  # Sensor HAL multihal configuration: empty hals.conf ensures multihal runs cleanly with 0 sensors
  touch /dev/whpx_empty_hals.conf
  chmod 644 /dev/whpx_empty_hals.conf 2>/dev/null
  chcon u:object_r:vendor_configs_file:s0 /dev/whpx_empty_hals.conf 2>/dev/null
  mount -o bind /dev/whpx_empty_hals.conf /vendor/etc/sensors/hals.conf 2>/dev/null
  setprop ctl.start vendor.sensors-hal-2-1-multihal 2>/dev/null
  setprop pm.dexopt.first-boot skip
  setprop pm.dexopt.boot-after-ota skip
  setprop pm.dexopt.boot-after-mainline-update skip
  setprop pm.dexopt.install skip

  cmd window set-fix-to-user-rotation enabled 2>/dev/null
  cmd window user-rotation lock 0 2>/dev/null
  settings put system accelerometer_rotation 0 2>/dev/null
  settings put system user_rotation 0 2>/dev/null
  setprop ctl.stop vendor.biometrics.fingerprint-service.ranchu 2>/dev/null
  stop vendor.biometrics.fingerprint-service.ranchu 2>/dev/null
  pkill -9 -f fingerprint-service.ranchu 2>/dev/null
  sync

  # Restart allocator, composer3, and health HALs in proper dependency order
  if [ ! -f /dev/whpx_gfxshim_mounted ]; then
    chmod 755 /dev/whpx_libs /dev/whpx_libs/allocator.ranchu /vendor/bin/hw/android.hardware.graphics.allocator-service.ranchu 2>/dev/null
    chmod 644 /dev/whpx_libs/*.so 2>/dev/null
    pkill -9 -f composer3 2>/dev/null
    pkill -9 -f composer@2.4 2>/dev/null
    pkill -9 surfaceflinger 2>/dev/null
    sleep 0.5
    setprop ctl.start vendor.graphics.allocator.ranchu 2>/dev/null
    setprop ctl.start vendor.gralloc-3-0 2>/dev/null
    sleep 0.5
    setprop ctl.start vendor.hwcomposer-3 2>/dev/null
    setprop ctl.start vendor.hwcomposer-2-4 2>/dev/null
  fi
  killall -9 android.hardware.health-service.default android.hardware.health-service android.hardware.health-service.example android.hardware.health@2.1-service 2>/dev/null
  setprop ctl.restart vendor.health-default 2>/dev/null
  setprop ctl.restart health-hal-2-1 2>/dev/null
  setprop ctl.start health-hal-2-1 2>/dev/null

  # Surfaceflinger & display config
  setprop debug.sf.nobootanimation 0
  DENSITY=${1:-$(getprop ro.boot.qemu.lcd_density)}
  DENSITY=${DENSITY:-480}
  setprop qemu.sf.lcd_density $DENSITY
  setprop ro.sf.lcd_density $DENSITY
  resize2fs /dev/block/dm-5 2>/dev/null

  if ! pidof surfaceflinger >/dev/null; then
    setprop ctl.start surfaceflinger 2>/dev/null
  fi

  touch /dev/whpx_shims_mounted
fi

# Never kill the allocator here: a running composer keeps its binder proxy, so every later
# buffer fails with NO_RESOURCES and SurfaceFlinger aborts in a loop. init restarts it if it dies.
# ----------------------------------------------------
# 4. Guarantee graphics stack is active
# ----------------------------------------------------
if ! pidof surfaceflinger >/dev/null; then
  pkill -9 -f composer3 2>/dev/null
  pkill -9 -f composer@2.4 2>/dev/null
  pkill -9 surfaceflinger 2>/dev/null
  sleep 0.5
  setprop ctl.start vendor.graphics.allocator.ranchu 2>/dev/null
  setprop ctl.start vendor.gralloc-3-0 2>/dev/null
  sleep 0.5
  setprop ctl.start vendor.hwcomposer-3 2>/dev/null
  setprop ctl.start vendor.hwcomposer-2-4 2>/dev/null
  sleep 0.5
  setprop ctl.start surfaceflinger 2>/dev/null
fi

# Immediately enforce client composition
sleep 1
if pidof surfaceflinger >/dev/null; then
  service call SurfaceFlinger 1008 i32 1 2>/dev/null
fi

# ----------------------------------------------------
# 5. Start persistent watchdog daemon
# ----------------------------------------------------
if [ -f /data/local/tmp/whpx_loop.sh ] && ! pgrep -f "whpx_loop.sh" >/dev/null; then
  if [ -x /data/local/tmp/whpx_daemon ]; then
    /data/local/tmp/whpx_daemon /data/local/tmp/whpx_loop.sh
  else
    nohup sh /data/local/tmp/whpx_loop.sh </dev/null >/data/local/tmp/loop.log 2>&1 &
  fi
fi
