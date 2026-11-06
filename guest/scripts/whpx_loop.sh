#!/system/bin/sh

# Infinite watchdog loop for WHPX Android UI
while true; do
  # Every tick stays cheap: only checks that something crashed. Commands that re-apply
  # settings fork processes and wake the framework (each "cmd battery set" broadcasts to
  # every app), which at one pass per 2 s was 48 forks/s of constant load. They run through
  # boot and the minute after it, after a framework restart, then every 30 s.
  tick=$((${tick:-0} + 1))
  [ ! -f /dev/whpx_boot_done ] && settle=30
  if [ "${settle:-30}" -gt 0 ]; then
    settle=$((${settle:-30} - 1))
    apply=1
  elif [ $((tick % 15)) = 0 ]; then
    apply=1
  else
    apply=0
  fi

  # 1. Ensure loopback and eth0 are UP with correct routes
  ifconfig lo 127.0.0.1 up 2>/dev/null
  if ! ifconfig eth0 2>/dev/null | grep -q "10.0.2.15"; then
    ifconfig eth0 10.0.2.15 netmask 255.255.255.0 up 2>/dev/null
    ip route add 10.0.2.0/24 dev eth0 table local 2>/dev/null
    ip route add default via 10.0.2.2 dev eth0 table local 2>/dev/null
    ip route add default via 10.0.2.2 dev eth0 table main 2>/dev/null
    ip rule del pref 32000 2>/dev/null
    ip rule add from all lookup main pref 5000 2>/dev/null
    setprop net.dns1 10.0.2.3 2>/dev/null
    setprop net.eth0.dns1 10.0.2.3 2>/dev/null
  fi

  # Keep IP rule lookup main active permanently
  if ! ip rule show | grep -q "lookup main"; then
    ip rule del pref 32000 2>/dev/null
    ip rule add from all lookup main pref 5000 2>/dev/null
  fi

  # 2. Ensure gfx_forwarder is running

  # 3. Ensure HALs and SurfaceFlinger are running
  if ! pidof surfaceflinger >/dev/null; then
    if ! pgrep -f gfx_forwarder >/dev/null && [ -x /data/local/tmp/gfx_forwarder ]; then
      /data/local/tmp/gfx_forwarder 5000 10.0.2.2 "$(getprop ro.boot.whpx.gfx_port)" &
      sleep 0.5
    fi
    SDK_VER=$(getprop ro.build.version.sdk)
    if [ -z "$SDK_VER" ] || [ "$SDK_VER" -ge 34 ]; then
      pkill -9 -f composer3 2>/dev/null
      pkill -9 surfaceflinger 2>/dev/null
      sleep 0.5
      setprop ctl.start vendor.graphics.allocator.ranchu 2>/dev/null
      sleep 0.5
      setprop ctl.start vendor.hwcomposer-3 2>/dev/null
    else
      if ! pidof android.hardware.graphics.allocator@3.0-service >/dev/null; then
        setprop ctl.start vendor.gralloc-3-0 2>/dev/null
      fi
      if ! pidof android.hardware.graphics.composer@2.4-service >/dev/null; then
        setprop ctl.start vendor.hwcomposer-2-4 2>/dev/null
      fi
    fi
    sleep 0.5
    setprop ctl.start surfaceflinger 2>/dev/null
    sleep 1
  fi

  # Continuous client composition enforcement:
  # Disables hardware overlays so SurfaceFlinger composites all layers
  # (Wallpaper, Launcher, SystemUI, Shade, BootAnim) into a single framebuffer target.
  if [ "$apply" = 1 ]; then
    if pidof surfaceflinger >/dev/null; then
      service call SurfaceFlinger 1008 i32 1 2>/dev/null
    fi
    chmod 755 /data/local/tmp/run_input_daemon.sh /data/local/tmp/run_net_agent.sh 2>/dev/null
  fi

  # 4. Ensure WhpxInputDaemon is running once zygote is up
  if ! pgrep -f WhpxInputDaemon >/dev/null && ! pgrep -f run_input_daemon >/dev/null; then
    if [ -f /data/local/tmp/run_input_daemon.sh ]; then
      nohup sh /data/local/tmp/run_input_daemon.sh </dev/null >/data/local/tmp/input.log 2>&1 &
    fi
  fi

  if [ "$apply" = 1 ]; then
  settings put global private_dns_mode off 2>/dev/null

  # Timeouts while the network comes up would bench healthy DNS servers for 30 minutes,
  # which is when the launcher loads its content ("content failed to load").
  if [ "$(settings get global dns_resolver_success_threshold_percent 2>/dev/null)" != "0" ]; then
    settings put global dns_resolver_success_threshold_percent 0 2>/dev/null
  fi

  # The kernel has no Bluetooth, so the HAL can never get a controller and the stack
  # aborts about every 17 s while it is on. Keep it off, as on hardware with the radio off.
  if [ "$(settings get global bluetooth_on 2>/dev/null)" != "0" ]; then
    settings put global ble_scan_always_enabled 0 2>/dev/null
    settings put global bluetooth_on 0 2>/dev/null
    cmd bluetooth_manager disable >/dev/null 2>&1 || svc bluetooth disable >/dev/null 2>&1
  fi
  cmd netpolicy set restrict-background false 2>/dev/null
  ndc bandwidth setglobalalert 10737418240 2>/dev/null
  fi

  # 5. Ensure DefaultNetworkAgent is running
  if ! pgrep -f DefaultNetworkAgent >/dev/null && ! pgrep -f run_net_agent >/dev/null; then
    if [ -f /data/local/tmp/run_net_agent.sh ]; then
      nohup sh /data/local/tmp/run_net_agent.sh </dev/null >/data/local/tmp/net_agent.log 2>&1 &
    fi
  fi

  # The radio HAL is hidden from VINTF and cannot run; init would restart it forever.
  if [ "$(getprop init.svc.vendor.radio-ranchu)" = "restarting" ]; then
    setprop ctl.stop vendor.radio-ranchu
  fi

  # 6. Keep battery full and devicestoragemonitor normal
  if [ "$apply" = 1 ]; then
    # A watch on a charger shows Wear OS's full-screen charging experience instead of the UI.
    case "$(getprop ro.build.characteristics)" in
      *watch*) batt_default_ac=0; batt_default_status=3 ;;
      *) batt_default_ac=1; batt_default_status=2 ;;
    esac
    # Extended Controls sets these; otherwise stay on the defaults above.
    batt_level=$(getprop whpx.batt.level)
    batt_level=${batt_level:-100}
    batt_ac=$(getprop whpx.batt.ac)
    batt_ac=${batt_ac:-$batt_default_ac}
    batt_status=$(getprop whpx.batt.status)
    batt_status=${batt_status:-$batt_default_status}
    # Applied once per value. BatteryService keeps it until system_server restarts.
    batt_want="$batt_level $batt_ac $batt_status"
    if [ "$batt_want" != "$batt_applied" ]; then
      cmd battery set present 1 2>/dev/null
      cmd battery set ac $batt_ac 2>/dev/null
      cmd battery set status $batt_status 2>/dev/null
      cmd battery set level $batt_level 2>/dev/null
      batt_applied=$batt_want
    fi
    stop console 2>/dev/null
    cmd devicestoragemonitor force-not-low -f 2>/dev/null
    settings put global sys_storage_threshold_percentage 1 2>/dev/null
    settings put global sys_storage_threshold_max_bytes 10485760 2>/dev/null
  fi

  # 7. Ensure Wallpaper is populated on every boot once NexusLauncher is running
  if [ ! -f /dev/whpx_wallpaper_done ] && [ -f /data/local/tmp/set_wallpaper.jar ] && [ -f /data/local/tmp/default_wallpaper.png ]; then
    LPID=$(pidof com.google.android.apps.nexuslauncher)
    if [ -n "$LPID" ]; then
      CLASSPATH=/data/local/tmp/set_wallpaper.jar app_process /system/bin SetWallpaper /data/local/tmp/default_wallpaper.png >/dev/null 2>&1
      if [ $? -eq 0 ]; then
        touch /dev/whpx_wallpaper_done 2>/dev/null
      fi
    fi
  fi

  # 8. Ensure composition and animation scales once NexusLauncher is running
  if [ ! -f /dev/whpx_scales_done ]; then
    LPID=$(pidof com.google.android.apps.nexuslauncher)
    if [ -n "$LPID" ]; then
      settings put global window_animation_scale 0.5 2>/dev/null
      settings put global transition_animation_scale 0.5 2>/dev/null
      settings put global animator_duration_scale 0.5 2>/dev/null
      settings put global disable_hw_overlays 1 2>/dev/null
      settings put global disable_window_blurs 1 2>/dev/null
      touch /dev/whpx_scales_done 2>/dev/null
    fi
  fi

  # 9. Ensure media Codec2 service is running
  if ! pgrep -f "media.c2" >/dev/null; then
    start android-hardware-media-c2-goldfish 2>/dev/null || \
    start android-hardware-media-c2@1.0-service-goldfish 2>/dev/null || \
    /vendor/bin/hw/android.hardware.media.c2-service-goldfish 2>/dev/null || \
    /vendor/bin/hw/android.hardware.media.c2@1.0-service-goldfish 2>/dev/null &
  fi

  # 10. Complete boot props, unlock user 0, and dismiss fallback once
  USER_STATE=
  [ "$apply" = 1 ] && USER_STATE=$(am get-started-user-state 0 2>/dev/null)
  # Android starts and unlocks user 0 itself. (This script used to run
  # "am start-user 0" here; during early boot that races system_server's
  # startup, crashes it with an NPE in UserController.onUserStarting, and
  # every crash restarts zygote, so boot never finishes.)
  case "$USER_STATE" in
    *RUNNING_LOCKED*)
      # Repair AVDs where older versions of this script ran
      # "pm disable .../FallbackHome": while user 0 is still locked that is the
      # only direct-boot HOME, and Android 15+ defers unlocking until boot
      # completes, so without it boot deadlocks on the splash screen forever.
      # No-op on stock data (already enabled).
      if [ ! -f /dev/whpx_fallback_repaired ]; then
        pm enable com.android.settings/.FallbackHome >/dev/null 2>&1
        pm enable com.android.fallback >/dev/null 2>&1
        am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
        touch /dev/whpx_fallback_repaired
      fi
      ;;
  esac

  case "$USER_STATE" in
    *RUNNING_UNLOCKED*)
      # Boot completion is left to Android itself (no faked sys.boot_completed
      # or BOOT_COMPLETED broadcasts) and FallbackHome is never disabled: both
      # hid real failures and the latter deadlocked later boots.
      if [ ! -f /dev/whpx_boot_done ] && [ "$(getprop sys.boot_completed)" = 1 ]; then
        wm dismiss-keyguard 2>/dev/null


        touch /dev/whpx_boot_done 2>/dev/null
      fi

      ip route add default via 10.0.2.2 dev eth0 2>/dev/null
      if ! pgrep -f "DefaultNetworkAgent" >/dev/null && ! pgrep -f run_net_agent >/dev/null && [ -f /data/local/tmp/default_net_agent.jar ]; then
        /data/local/tmp/run_net_agent.sh >/data/local/tmp/net_agent.log 2>&1 &
      fi
      ;;
  esac

  # These hold binders into system_server. After a framework restart those are dead
  # and they never recover alone: no default network, no input. run_*.sh restarts them.
  ss_pid=$(pidof system_server)
  if [ -n "$ss_pid" ] && [ -n "$last_ss_pid" ] && [ "$ss_pid" != "$last_ss_pid" ]; then
    pkill -f DefaultNetworkAgent 2>/dev/null
    pkill -f WhpxInputDaemon 2>/dev/null
    settle=30
    batt_applied=
  fi
  [ -n "$ss_pid" ] && last_ss_pid=$ss_pid

  # Network location needs Wi-Fi or cell scans, which this VM does not have, and apps
  # asking for it wait forever. Answer with the same position GPS reports. A failed
  # update means the provider is gone (first run, or Android restarted): re-create it.
  if [ "$(getprop sys.boot_completed)" = "1" ]; then
    # The bridge sets this once after boot; if that lands early it is simply lost and
    # a fresh AVD stays at the default density, with everything tiny.
    # TV caps its UI below the panel (1920x1080 on 4K) and scales density to match; the
    # panel's own value there is a combination Google TV refuses to run on.
    boot_density=
    [ "$apply" = 1 ] && boot_density=$(getprop ro.boot.lcd_density)
    if [ -n "$boot_density" ]; then
      wm_size=$(wm size 2>/dev/null)
      panel_w=$(echo "$wm_size" | grep "^Physical size" | sed "s/.*: //;s/x.*//")
      ui_w=$(echo "$wm_size" | grep "^Override size" | sed "s/.*: //;s/x.*//")
      if [ -n "$panel_w" ] && [ -n "$ui_w" ] && [ "$ui_w" -lt "$panel_w" ]; then
        boot_density=$((boot_density * ui_w / panel_w))
      fi
      if ! wm density 2>/dev/null | tail -1 | grep -q ": $boot_density"; then
        wm density "$boot_density" >/dev/null 2>&1
      fi
    fi

    # Images ship a framework and a SystemUI overlay per Pixel skin with its real cutout and
    # corners, which the stock emulator enables. Without them a skin with a cutout falls back to
    # emu01, an inset that draws nothing: "hole" would add a black circle at the top left.
    if [ -z "$cutout_done" ]; then
      overlays=$(cmd overlay list 2>/dev/null)
      if [ -n "$overlays" ]; then
        cutout_base=com.android.internal.display.cutout.emulation
        skin=$(getprop ro.boot.whpx.skin)
        want=
        if [ -n "$skin" ] && echo "$overlays" | grep -q "com.android.internal.emulation.$skin\$"; then
          want=com.android.internal.emulation.$skin
          echo "$overlays" | grep -q "com.android.systemui.emulation.$skin\$" && want="$want com.android.systemui.emulation.$skin"
        elif [ -n "$(getprop ro.boot.whpx.cutout)" ] && echo "$overlays" | grep -q "$cutout_base.emu01\$"; then
          want=$cutout_base.emu01
        fi
        pending=
        for o in $cutout_base.hole $cutout_base.emu01; do
          case " $want " in *" $o "*) continue ;; esac
          echo "$overlays" | grep -q "\[x\] $o\$" && { cmd overlay disable "$o" >/dev/null 2>&1; pending=1; }
        done
        for o in $want; do
          echo "$overlays" | grep -q "\[x\] $o\$" || { cmd overlay enable "$o" >/dev/null 2>&1; pending=1; }
        done
        if [ -n "$pending" ]; then
          shape_changed=1
          cutout_tries=$((${cutout_tries:-0} + 1))
          [ "$cutout_tries" -ge 20 ] && cutout_done=1
        else
          cutout_done=1
          # The status bar only lays out for the new shape when SystemUI starts again.
          [ -n "$shape_changed" ] && pkill -f com.android.systemui 2>/dev/null
        fi
      fi
    fi

    gps_tick=$((${gps_tick:-0} + 1))
    if [ $((gps_tick % 5)) = 1 ]; then
      gps_pos=$(getprop whpx.gps.pos)
      gps_pos=${gps_pos:-37.4220,-122.0841}
      if ! cmd location providers set-test-provider-location network --location "$gps_pos" --accuracy 20 >/dev/null 2>&1; then
        appops set --uid 0 MOCK_LOCATION allow >/dev/null 2>&1  # API 33-34 refuse root without this
        cmd location providers add-test-provider network --supportsAltitude --supportsSpeed --supportsBearing >/dev/null 2>&1
        cmd location providers set-test-provider-enabled network true >/dev/null 2>&1
        cmd location providers set-test-provider-location network --location "$gps_pos" --accuracy 20 >/dev/null 2>&1
      fi
    fi
  fi

  sleep 2
done
