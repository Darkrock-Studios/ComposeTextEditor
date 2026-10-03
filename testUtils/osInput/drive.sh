#!/usr/bin/env bash
# Opens the OsInputProbe window (0.8), presses real X key events into it with xdotool,
# and checks what the editor ends up holding. Needs an X11 display with a window
# manager, the US International layout already set (setxkbmap -layout us -variant intl),
# and xdotool. Run from the repository root:
#   testUtils/osInput/drive.sh <output dir>
# Writes PASS or FAIL per case to <dir>/results.txt and exits 1 if any case failed.
set -uo pipefail

out=${1:?usage: drive.sh <output dir>}
mkdir -p "$out"
rm -f "$out/ready" "$out/text"
results=$out/results.txt
: > "$results"
failed=0

./gradlew :ComposeTextEditor:runOsInputProbe --args="$out" > "$out/probe.log" 2>&1 &
probe=$!
window=

finish() {
	command -v import > /dev/null && import -window root "$out/screen.png" 2>/dev/null
	[ -n "$window" ] && xdotool windowclose "$window" 2>/dev/null
	sleep 2
	kill "$probe" 2>/dev/null
}
trap finish EXIT

# The document, with an x after it so a trailing newline survives the substitution.
text() { cat "$out/text" 2>/dev/null; printf x; }

# Waits up to five seconds for the document to read $1.
await_text() {
	for _ in $(seq 50); do
		[ "$(text)" = "${1}x" ] && return 0
		sleep 0.1
	done
	return 1
}

pass() { echo "PASS $1" | tee -a "$results"; }
fail() { echo "FAIL $1" | tee -a "$results"; failed=1; }

# Empties the document, runs xdotool with the remaining arguments, and compares.
check() {
	local name=$1 expected=$2
	shift 2
	xdotool key --clearmodifiers ctrl+a BackSpace
	if ! await_text ""; then
		# A dead key the editor never finished can swallow the first keys.
		xdotool key Escape ctrl+a BackSpace
		await_text "" || { fail "$name: could not empty the document, it holds $(printf %q "$(text)")"; return; }
	fi
	xdotool "$@"
	if await_text "$expected"; then
		pass "$name"
	else
		local got
		got=$(text)
		fail "$name: expected $(printf %q "$expected"), got $(printf %q "${got%x}")"
	fi
}

echo "layout: $(setxkbmap -query | tr '\n' ' ')"
for _ in $(seq 6000); do
	[ -e "$out/ready" ] && break
	kill -0 "$probe" 2>/dev/null || { echo "the probe exited; see $out/probe.log"; exit 1; }
	sleep 0.1
done
[ -e "$out/ready" ] || { echo "the probe never took focus"; exit 1; }
window=$(xdotool search --sync --onlyvisible --name '^OsInputProbe$' | head -1)
xdotool windowactivate --sync "$window" 2>/dev/null || xdotool windowfocus --sync "$window"
xdotool mousemove --window "$window" 200 100 click 1
sleep 1

# Keysyms, not characters: under us(intl) the quote keys are dead keys, and xdotool
# would remap a spare keycode for a character the layout does not have at level 1.
check "plain typing" "hello world" type --delay 40 "hello world"
check "Enter and Backspace" $'one\ntw' key --delay 60 o n e Return t w o BackSpace
check "dead acute" "é" key --delay 80 dead_acute e
check "dead grave" "à" key --delay 80 dead_grave a
check "dead circumflex" "ê" key --delay 80 dead_circumflex e
check "dead tilde" "ñ" key --delay 80 dead_tilde n
check "dead diaeresis" "ü" key --delay 80 dead_diaeresis u
check "dead acute, capital" "É" key --delay 80 dead_acute shift+e
check "dead acute then space" "'" key --delay 80 dead_acute space
check "dead keys inside words" "café naïve" key --delay 80 c a f dead_acute e space n a dead_diaeresis i v e
check "AltGr" "ä" key --delay 80 ISO_Level3_Shift+q

exit $failed
