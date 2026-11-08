#!/bin/bash
# Restores a TCL Google TV that was set up for Gallery: re-enables the stock launcher and the
# TCL and Google apps that were disabled, and resets the animation speeds.
# usage: ./tv-undo.sh <ip:port>   (the TV's adb address, with network debugging on)
if [ -z "$1" ]; then echo "usage: $0 <ip:port>" >&2; exit 1; fi
export ANDROID_SERIAL="$1"
adb connect "$ANDROID_SERIAL" >/dev/null
for p in com.google.android.tungsten.setupwraith com.google.android.apps.tv.launcherx com.tcl.tv.plus com.tcl.channelplus com.tcl.messagebox com.tcl.exhibit com.tcl.esticker com.tcl.logkit com.google.android.feedback com.android.tv.feedbackconsent com.tcl.ocean.instructions com.tcl.repairguide com.tcl.t_solo com.tcl.browser com.tcl.smartalexa com.tcl.usercenter com.google.android.play.games; do adb shell pm enable --user 0 "$p"; done
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings delete global animator_duration_scale
adb shell input keyevent KEYCODE_HOME
