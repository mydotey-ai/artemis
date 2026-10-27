#!/usr/bin/env bash
# Artemis all-in-one 运维脚本（VM 标准安装包，v1.0 部署形态 I）。
# 布局与语义见 docs/deploy/vm.md；服务端配置出口为工作目录 artemis.properties（SCF），
# 故启动前必须 cd 到 APP_HOME（脚本自动完成）。
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$APP_HOME/lib/artemis-dist.jar"
PID_FILE="$APP_HOME/app.pid"
CONSOLE_LOG="$APP_HOME/console.log" # 仅 start（后台手跑）模式落地；systemd 路径日志走 journal
SERVICE_NAME="artemis"
UNIT_DIR="${SYSTEMD_UNIT_DIR:-/etc/systemd/system}"

JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:-}"

die() { echo "error: $*" >&2; exit 1; }

pid_alive() {
    local pid="$1"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

running_pid() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if pid_alive "$pid"; then echo "$pid"; else rm -f "$PID_FILE"; return 1; fi
}

cmd_run() {
    # 前台运行（systemd ExecStart / 容器）；exec 替换 shell，信号直达 JVM（优雅停机 hook）
    cd "$APP_HOME"
    exec "$JAVA_BIN" $JAVA_OPTS -jar "$JAR"
}

cmd_start() {
    local pid
    if pid="$(running_pid)"; then die "already running (pid $pid)"; fi
    echo "starting artemis (background; log: $CONSOLE_LOG)"
    cd "$APP_HOME"
    nohup "$JAVA_BIN" $JAVA_OPTS -jar "$JAR" >>"$CONSOLE_LOG" 2>&1 &
    echo $! >"$PID_FILE"
    sleep 1
    pid="$(running_pid)" || die "start failed, see $CONSOLE_LOG"
    echo "started (pid $pid)"
}

cmd_stop() {
    local pid
    pid="$(running_pid)" || { echo "not running"; return 0; }
    echo "stopping (pid $pid, SIGTERM -> graceful shutdown: discovery -> management -> registry)"
    kill -TERM "$pid"
    # 优雅停机含排水窗口，等待上限与 systemd TimeoutStopSec 对齐（90s）
    for _ in $(seq 1 90); do
        pid_alive "$pid" || { rm -f "$PID_FILE"; echo "stopped"; return 0; }
        sleep 1
    done
    echo "graceful stop timed out after 90s, killing" >&2
    kill -KILL "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "killed"
}

cmd_status() {
    local pid
    if pid="$(running_pid)"; then echo "running (pid $pid)"; else echo "not running"; fi
}

cmd_logs() {
    # systemd 承载优先（journal），手跑模式兜底 console.log
    if command -v journalctl >/dev/null 2>&1 && journalctl -u "$SERVICE_NAME" -n 1 >/dev/null 2>&1; then
        journalctl -u "$SERVICE_NAME" -f
    elif [ -f "$CONSOLE_LOG" ]; then
        tail -f "$CONSOLE_LOG"
    else
        die "no log source (neither journalctl unit '$SERVICE_NAME' nor $CONSOLE_LOG)"
    fi
}

cmd_version() {
    cat "$APP_HOME/lib/version"
}

install_unit() {
    local unit="$UNIT_DIR/$SERVICE_NAME.service"
    [ -d "$UNIT_DIR" ] || die "unit dir $UNIT_DIR does not exist (set SYSTEMD_UNIT_DIR to override)"
    sed "s#__APP_HOME__#$APP_HOME#g" "$APP_HOME/artemis.service" >"$unit"
    echo "unit written: $unit"
}

cmd_install() {
    install_unit
    systemctl daemon-reload
    systemctl enable "$SERVICE_NAME"
    echo "installed; start with: systemctl start $SERVICE_NAME"
}

cmd_uninstall() {
    systemctl disable "$SERVICE_NAME" 2>/dev/null || true
    rm -f "$UNIT_DIR/$SERVICE_NAME.service"
    systemctl daemon-reload
    echo "uninstalled"
}

case "${1:-help}" in
    run) cmd_run ;;
    start) cmd_start ;;
    stop) cmd_stop ;;
    restart) cmd_stop; cmd_start ;;
    status) cmd_status ;;
    logs) cmd_logs ;;
    version) cmd_version ;;
    install) cmd_install ;;
    uninstall) cmd_uninstall ;;
    *) cat <<EOF
usage: app.sh {run|start|stop|restart|status|logs|version|install|uninstall}

  run        前台运行（systemd / 容器）
  start      后台启动（手跑模式，日志 -> console.log）
  stop       优雅停止（SIGTERM，等排水，上限 90s）
  restart    stop + start
  status     进程状态
  logs       跟随日志（journalctl 优先，console.log 兜底）
  version    版本信息
  install    安装 systemd 服务（SYSTEMD_UNIT_DIR 默认 /etc/systemd/system）
  uninstall  卸载 systemd 服务
EOF
       ;;
esac
