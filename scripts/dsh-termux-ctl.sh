#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Standalone DeepSeek Harness backend controller for Termux RUN_COMMAND Intent.
# Does not modify the existing DSH installation or configuration.

export PREFIX="/data/data/com.termux/files/usr"
export HOME="${HOME:-/data/data/com.termux/files/home}"
export PATH="$HOME/.local/bin:$PREFIX/bin:/system/bin:/system/xbin"

DSH_BIN="${DSH_BIN:-$HOME/.local/bin/dsh}"
STATE_DIR="${DSH_CTL_STATE_DIR:-$HOME/.cache/dsh-android-client}"
PID_FILE="$STATE_DIR/dsh-web.pid"
LOG_FILE="$STATE_DIR/dsh-web.log"
LEGACY_LOG_FILE="$HOME/.dsh/dsh-web.log"

DEFAULT_HOST="127.0.0.1"
DEFAULT_PORT="3080"
DEFAULT_WORKDIR="/storage/emulated/0/dsh_project"

ACTION="${1:-status}"
if [ "$#" -gt 0 ]; then
  shift
fi

HOST="$DEFAULT_HOST"
PORT="$DEFAULT_PORT"
WORKDIR="$DEFAULT_WORKDIR"
LOG_LINES="120"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --host)
      HOST="${2:-$DEFAULT_HOST}"
      shift 2
      ;;
    --port)
      PORT="${2:-$DEFAULT_PORT}"
      shift 2
      ;;
    --workdir)
      WORKDIR="${2:-$DEFAULT_WORKDIR}"
      shift 2
      ;;
    --lines)
      LOG_LINES="${2:-120}"
      shift 2
      ;;
    *)
      if [[ "$ACTION" == "logs" && "$1" =~ ^[0-9]+$ ]]; then
        LOG_LINES="$1"
      fi
      shift
      ;;
  esac
done

mkdir -p "$STATE_DIR"

is_pid_dsh_web() {
  local pid="$1"
  [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -Fq "@deepseek-ai/dsh/lib/bin.js" || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -Eq '(^| )web( |$)' || return 1
  return 0
}

# Port a DSH web process listens on; dsh web defaults to DEFAULT_PORT without --port.
pid_web_port() {
  local pid="$1"
  [ -r "/proc/$pid/cmdline" ] || return 1
  local args port
  args="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
  port="$(printf '%s' "$args" | grep -oE -- '--port [0-9]+' | head -n 1 | awk '{print $2}')"
  printf '%s' "${port:-$DEFAULT_PORT}"
}

# DSH web processes listening on the configured port only.
find_dsh_pids() {
  local pids=()
  local pid

  if [ -f "$PID_FILE" ]; then
    pid="$(tr -dc '0-9' < "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$pid" ] && is_pid_dsh_web "$pid" && [ "$(pid_web_port "$pid")" = "$PORT" ]; then
      pids+=("$pid")
    fi
  fi

  for pdir in /proc/[0-9]*; do
    pid="${pdir#/proc/}"
    [ "$pid" = "$$" ] && continue
    if is_pid_dsh_web "$pid" && [ "$(pid_web_port "$pid")" = "$PORT" ]; then
      local dup=0
      for existing in "${pids[@]:-}"; do
        if [ "$existing" = "$pid" ]; then
          dup=1
          break
        fi
      done
      if [ "$dup" -eq 0 ]; then
        pids+=("$pid")
      fi
    fi
  done

  if [ "${#pids[@]}" -gt 0 ]; then
    printf '%s\n' "${pids[@]}"
  fi
}

# Ports of DSH web instances running on a port other than the configured one.
find_other_web_ports() {
  local ports=""
  local pid port
  for pdir in /proc/[0-9]*; do
    pid="${pdir#/proc/}"
    [ "$pid" = "$$" ] && continue
    is_pid_dsh_web "$pid" || continue
    port="$(pid_web_port "$pid")"
    [ "$port" = "$PORT" ] && continue
    case ",$ports," in
      *",$port,"*) ;;
      *) ports="${ports:+$ports,}$port" ;;
    esac
  done
  printf '%s' "$ports"
}

check_port_open() {
  local h="$1"
  local p="$2"
  # Fast pure-bash TCP probe with timeout via curl or /dev/tcp.
  if command -v curl >/dev/null 2>&1; then
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 1 --max-time 2 "http://${h}:${p}/" 2>/dev/null || true)"
    if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then
      return 0
    fi
  fi
  if (exec 3<>"/dev/tcp/${h}/${p}") 2>/dev/null; then
    exec 3>&- 3<&-
    return 0
  fi
  return 1
}

get_active_log_file() {
  if [ -f "$LOG_FILE" ] && [ -f "$LEGACY_LOG_FILE" ]; then
    if [ "$LEGACY_LOG_FILE" -nt "$LOG_FILE" ]; then
      printf '%s' "$LEGACY_LOG_FILE"
    else
      printf '%s' "$LOG_FILE"
    fi
  elif [ -f "$LOG_FILE" ]; then
    printf '%s' "$LOG_FILE"
  elif [ -f "$LEGACY_LOG_FILE" ]; then
    printf '%s' "$LEGACY_LOG_FILE"
  else
    printf '%s' "$LOG_FILE"
  fi
}

# Discover the launch-token URL that `dsh web` prints on startup.
# The token is random per process, so it must be re-read (and re-validated)
# after every backend restart. Validation keeps stale tokens in the logs from
# being handed out: only a token that the live listener still accepts is returned.
discover_auth_url() {
  local -a tokens=()
  local f line code i
  for f in "$LOG_FILE" "$LEGACY_LOG_FILE" "$HOME/.dsh/restart-web.log"; do
    [ -f "$f" ] || continue
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      tokens+=("$line")
    done < <(grep -oE "http://(127\.0\.0\.1|localhost):${PORT}/?\?token=[A-Za-z0-9_-]+" "$f" 2>/dev/null | tail -n 5 || true)
  done

  [ "${#tokens[@]}" -gt 0 ] || return 1

  # Prefer the most recently logged token that the listener still accepts.
  for ((i = ${#tokens[@]} - 1; i >= 0; i--)); do
    line="${tokens[$i]}"
    if command -v curl >/dev/null 2>&1; then
      code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 1 --max-time 2 "$line" 2>/dev/null || true)"
      case "$code" in
        200|302|303) printf '%s' "$line"; return 0 ;;
      esac
    else
      printf '%s' "$line"
      return 0
    fi
  done
  return 1
}

# True when an unauthenticated GET on the base URL is rejected with 401.
requires_auth() {
  command -v curl >/dev/null 2>&1 || return 1
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 1 --max-time 2 \
    "http://${HOST}:${PORT}/" 2>/dev/null || true)"
  [ "$code" = "401" ]
}

rotate_log_if_large() {
  if [ -f "$LOG_FILE" ]; then
    local size
    size="$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d '[:space:]' || echo 0)"
    if [ "${size:-0}" -gt 5242880 ]; then
      mv -f "$LOG_FILE" "${LOG_FILE}.1" 2>/dev/null || true
    fi
  fi
}

do_status() {
  local pids_text primary_pid port_open status_val active_log uptime_str
  pids_text="$(find_dsh_pids || true)"
  primary_pid="$(printf '%s\n' "$pids_text" | head -n 1)"
  active_log="$(get_active_log_file)"

  if check_port_open "$HOST" "$PORT"; then
    port_open="yes"
  else
    port_open="no"
  fi

  if [ -n "$primary_pid" ] && [ "$port_open" = "yes" ]; then
    status_val="RUNNING"
  elif [ -n "$primary_pid" ]; then
    status_val="STARTING"
  elif [ "$port_open" = "yes" ]; then
    status_val="RUNNING"
  else
    status_val="STOPPED"
  fi

  uptime_str=""
  if [ -n "$primary_pid" ] && command -v ps >/dev/null 2>&1; then
    uptime_str="$(ps -p "$primary_pid" -o etime= 2>/dev/null | tr -d '[:space:]' || true)"
  fi

  printf 'STATUS=%s\n' "$status_val"
  printf 'PID=%s\n' "${primary_pid:-none}"
  printf 'HOST=%s\n' "$HOST"
  printf 'PORT=%s\n' "$PORT"
  printf 'URL=http://%s:%s\n' "$HOST" "$PORT"
  printf 'PORT_OPEN=%s\n' "$port_open"
  [ -n "$uptime_str" ] && printf 'UPTIME=%s\n' "$uptime_str"
  printf 'LOG=%s\n' "$active_log"

  local other_ports
  other_ports="$(find_other_web_ports)"
  if [ -n "$other_ports" ]; then
    printf 'OTHER_PORTS=%s\n' "$other_ports"
  fi

  if requires_auth; then
    printf 'AUTH=required\n'
    local auth_url
    if auth_url="$(discover_auth_url)"; then
      printf 'AUTH_URL=%s\n' "$auth_url"
    else
      printf 'AUTH_URL=\n'
    fi
  else
    printf 'AUTH=none\n'
  fi

  if [ "$status_val" = "RUNNING" ]; then
    return 0
  fi
  return 3
}

do_start() {
  local pids_text primary_pid
  pids_text="$(find_dsh_pids || true)"
  primary_pid="$(printf '%s\n' "$pids_text" | head -n 1)"

  if [ -n "$primary_pid" ] && check_port_open "$HOST" "$PORT"; then
    printf 'STATUS=RUNNING\n'
    printf 'PID=%s\n' "$primary_pid"
    printf 'URL=http://%s:%s\n' "$HOST" "$PORT"
    printf 'MESSAGE=DSH backend is already running.\n'
    local existing_auth_url
    if existing_auth_url="$(discover_auth_url)"; then
      printf 'AUTH_URL=%s\n' "$existing_auth_url"
    fi
    return 0
  fi

  if [ ! -x "$DSH_BIN" ]; then
    printf 'STATUS=ERROR\n'
    printf 'MESSAGE=DSH executable not found: %s\n' "$DSH_BIN"
    return 127
  fi

  rotate_log_if_large

  local run_dir="$WORKDIR"
  if [ ! -d "$run_dir" ]; then
    run_dir="$HOME"
  fi

  {
    printf '\n===== [%s] Starting DSH web (host=%s port=%s cwd=%s) =====\n' \
      "$(date '+%Y-%m-%d %H:%M:%S')" "$HOST" "$PORT" "$run_dir"
  } >> "$LOG_FILE"

  local cmd_args=("web" "--no-open")
  if [ -n "$HOST" ]; then
    cmd_args+=("--host" "$HOST")
  fi
  if [ -n "$PORT" ]; then
    cmd_args+=("--port" "$PORT")
  fi

  (
    cd "$run_dir"
    if command -v setsid >/dev/null 2>&1; then
      setsid "$DSH_BIN" "${cmd_args[@]}" </dev/null >>"$LOG_FILE" 2>&1 &
    elif command -v nohup >/dev/null 2>&1; then
      nohup "$DSH_BIN" "${cmd_args[@]}" </dev/null >>"$LOG_FILE" 2>&1 &
    else
      "$DSH_BIN" "${cmd_args[@]}" </dev/null >>"$LOG_FILE" 2>&1 &
    fi
    echo $! > "$PID_FILE"
  )

  local new_pid
  new_pid="$(tr -dc '0-9' < "$PID_FILE" 2>/dev/null || true)"

  # Wait briefly (up to 15s) for the HTTP port to come online.
  local i=0
  while [ "$i" -lt 30 ]; do
    if check_port_open "$HOST" "$PORT"; then
      local live_pid
      live_pid="$(find_dsh_pids | head -n 1 || true)"
      [ -n "$live_pid" ] && echo "$live_pid" > "$PID_FILE"
      printf 'STATUS=RUNNING\n'
      printf 'PID=%s\n' "${live_pid:-$new_pid}"
      printf 'URL=http://%s:%s\n' "$HOST" "$PORT"
      printf 'LOG=%s\n' "$LOG_FILE"
      printf 'MESSAGE=DSH backend started successfully.\n'
      local started_auth_url
      if started_auth_url="$(discover_auth_url)"; then
        printf 'AUTH_URL=%s\n' "$started_auth_url"
      fi
      return 0
    fi
    if [ -n "$new_pid" ] && ! kill -0 "$new_pid" 2>/dev/null; then
      break
    fi
    sleep 0.5
    i=$((i + 1))
  done

  if [ -n "$new_pid" ] && kill -0 "$new_pid" 2>/dev/null; then
    printf 'STATUS=STARTING\n'
    printf 'PID=%s\n' "$new_pid"
    printf 'URL=http://%s:%s\n' "$HOST" "$PORT"
    printf 'LOG=%s\n' "$LOG_FILE"
    printf 'MESSAGE=DSH process started; still initializing port %s.\n' "$PORT"
    return 0
  fi

  printf 'STATUS=FAILED\n'
  printf 'LOG=%s\n' "$LOG_FILE"
  printf 'MESSAGE=DSH failed to start. Recent log:\n'
  tail -n 30 "$LOG_FILE" 2>/dev/null || true
  return 1
}

do_stop() {
  local pids_text pid
  pids_text="$(find_dsh_pids || true)"

  if [ -z "$pids_text" ]; then
    rm -f "$PID_FILE"
    printf 'STATUS=STOPPED\n'
    printf 'MESSAGE=DSH backend is not running.\n'
    return 0
  fi

  for pid in $pids_text; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  local i=0
  while [ "$i" -lt 12 ]; do
    if [ -z "$(find_dsh_pids || true)" ]; then
      rm -f "$PID_FILE"
      printf 'STATUS=STOPPED\n'
      printf 'MESSAGE=DSH backend stopped cleanly.\n'
      return 0
    fi
    sleep 0.5
    i=$((i + 1))
  done

  # Force terminate any remaining DSH web processes.
  pids_text="$(find_dsh_pids || true)"
  for pid in $pids_text; do
    kill -KILL "$pid" 2>/dev/null || true
  done
  sleep 0.3
  rm -f "$PID_FILE"

  printf 'STATUS=STOPPED\n'
  printf 'MESSAGE=DSH backend stopped (forced).\n'
  return 0
}

do_url() {
  local auth_url
  if ! check_port_open "$HOST" "$PORT"; then
    printf 'STATUS=STOPPED\n'
    printf 'URL=http://%s:%s\n' "$HOST" "$PORT"
    printf 'MESSAGE=DSH backend is not running.\n'
    return 3
  fi

  if ! requires_auth; then
    printf 'STATUS=RUNNING\n'
    printf 'AUTH=none\n'
    printf 'URL=http://%s:%s/\n' "$HOST" "$PORT"
    return 0
  fi

  if auth_url="$(discover_auth_url)"; then
    printf 'STATUS=RUNNING\n'
    printf 'AUTH=required\n'
    printf 'URL=%s\n' "$auth_url"
    return 0
  fi

  printf 'STATUS=RUNNING\n'
  printf 'AUTH=required\n'
  printf 'URL=http://%s:%s/\n' "$HOST" "$PORT"
  printf 'MESSAGE=认证令牌未找到；请在 Termux 中运行 dsh web 并复制其输出的链接。\n'
  return 4
}

do_logs() {
  local active_log
  active_log="$(get_active_log_file)"
  printf 'LOG=%s\n' "$active_log"
  if [ -f "$active_log" ]; then
    printf -- '--- Last %s lines ---\n' "$LOG_LINES"
    tail -n "$LOG_LINES" "$active_log" 2>/dev/null || true
  else
    printf 'MESSAGE=No log file found yet at %s.\n' "$active_log"
  fi
  return 0
}

case "$ACTION" in
  status)
    do_status
    ;;
  start)
    do_start
    ;;
  stop)
    do_stop
    ;;
  restart)
    do_stop
    do_start
    ;;
  logs)
    do_logs
    ;;
  url|auth-url)
    do_url
    ;;
  *)
    printf 'Usage: %s {status|start|stop|restart|logs|url} [--host H] [--port P] [--workdir DIR] [--lines N]\n' "$0" >&2
    exit 2
    ;;
esac
