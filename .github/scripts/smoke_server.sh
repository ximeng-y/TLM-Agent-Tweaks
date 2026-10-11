#!/usr/bin/env bash
# 开发服务端冒烟：跑 <loader>:runServer，等到日志出现 Done ( 即判定通过。
#
# 判定必须看日志而不是退出码：实测 Forge 缺 eula.txt 时只打印一句 EULA 提示就结束，
# 而 Gradle 的退出码是 0。
#
# 用法：bash .github/scripts/smoke_server.sh <neoforge|forge> [超时秒数]
set -uo pipefail

loader="${1:?用法: smoke_server.sh <neoforge|forge> [超时秒数]}"
timeout_s="${2:-420}"

case "$loader" in
  neoforge) game_dir="neoforge/runs/server" ;;
  forge)    game_dir="forge/run/server" ;;
  *) echo "未知加载器: $loader" >&2; exit 2 ;;
esac

log="$(mktemp)"
echo "== ${loader} 冒烟：超时 ${timeout_s}s，日志 ${log} =="

# Forge 的开发服务端强制要求 eula.txt；NeoForge 侧实测不需要，写上也无害。
mkdir -p "$game_dir"
printf 'eula=true\n' > "$game_dir/eula.txt"

# 服务端进程的启动目标在两侧不同，用它做进程匹配的锚点
case "$loader" in
  neoforge) launch_target='forgeserverdev' ;;
  forge)    launch_target='forgeserveruserdev' ;;
esac

# pkill/pgrep 在 ubuntu runner 上有（procps），Windows 的 Git Bash 上没有；
# 缺了只影响本地调试的收尾，不影响 CI 判定，故降级而不是报错。
have_pkill=0
type -P pkill >/dev/null 2>&1 && have_pkill=1

kill_server() {
  (( have_pkill )) || return 0
  pkill -f "$launch_target" 2>/dev/null
}

wait_server_gone() {
  (( have_pkill )) || return 0
  for _ in 1 2 3 4 5; do
    pgrep -f "$launch_target" >/dev/null 2>&1 || return 0
    sleep 1
  done
  pkill -9 -f "$launch_target" 2>/dev/null
}

gradle ":${loader}:runServer" --no-daemon --console=plain > "$log" 2>&1 &
launcher_pid=$!

cleanup() {
  # 实测：杀掉 Gradle 不会连带杀掉它 fork 出的 Minecraft 服务端，必须按启动目标补一刀，
  # 否则 25565 一直被占，同机后续步骤会因端口冲突失败。
  kill "$launcher_pid" 2>/dev/null
  kill_server
  wait "$launcher_pid" 2>/dev/null
  wait_server_gone
}
trap cleanup EXIT

status=""
mixin_re='Mixin apply for mod .* failed|InvalidInjectionException|MixinTransformerError|MixinApplyError'
deadline=$(( SECONDS + timeout_s ))

while (( SECONDS < deadline )); do
  if grep -q 'Done (' "$log"; then status=ok; break; fi
  if grep -q 'You need to agree to the EULA' "$log"; then status=eula; break; fi
  if grep -qE "$mixin_re" "$log"; then status=mixin; break; fi
  kill -0 "$launcher_pid" 2>/dev/null || { status=exited; break; }
  sleep 2
done

[[ -n "$status" ]] || status=timeout

# 即使已经出现 Done (，也要确认启动全程没有混入失败日志
mixin_hit=""
grep -qE "$mixin_re" "$log" && mixin_hit=1

case "$status" in
  ok)
    if [[ -n "$mixin_hit" ]]; then
      echo "FAIL: 服务端启动了，但日志中存在 mixin 应用失败" >&2
      tail -n 60 "$log" >&2
      exit 1
    fi
    echo "OK: $(grep -m1 'Done (' "$log" | sed 's/^.*]: //')"
    ;;
  eula)
    echo "FAIL: 服务端因未同意 EULA 而退出（eula.txt 未生效或路径不对）" >&2
    tail -n 20 "$log" >&2
    exit 1
    ;;
  mixin)
    echo "FAIL: mixin 应用失败，服务端未能完成启动" >&2
    grep -m1 -E "$mixin_re" "$log" >&2
    tail -n 60 "$log" >&2
    exit 1
    ;;
  exited)
    echo "FAIL: Gradle 进程提前退出，服务端未启动到 Done (" >&2
    tail -n 60 "$log" >&2
    exit 1
    ;;
  timeout)
    echo "FAIL: 等待 ${timeout_s}s 仍未出现 Done (" >&2
    tail -n 60 "$log" >&2
    exit 1
    ;;
esac
