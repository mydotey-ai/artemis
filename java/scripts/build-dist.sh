#!/usr/bin/env bash
# 构建 VM 标准安装包（v1.0 部署形态 I）：artemis-dist fat jar + deploy/vm 模板 -> build/artemis-<version>.tar.gz
# 结构与用法见 docs/deploy/vm.md；冒烟入口 java/scripts/smoke-deploy.sh vm。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_TARGET="$REPO_ROOT/java/server/artemis-dist/target"
TEMPLATE_DIR="$REPO_ROOT/deploy/vm"
BUILD_DIR="$REPO_ROOT/build"
PKG_DIR="$BUILD_DIR/artemis"

command -v mvn >/dev/null 2>&1 || { echo "error: mvn not found" >&2; exit 1; }

echo "==> building artemis-dist fat jar"
mvn -B -q -f "$REPO_ROOT/java/pom.xml" -pl :artemis-dist -am package -DskipTests

JAR="$(ls "$DIST_TARGET"/artemis-dist-*-exec.jar 2>/dev/null | head -n1)"
[ -n "$JAR" ] || { echo "error: fat jar not found in $DIST_TARGET" >&2; exit 1; }
# 版本取自 jar 文件名（artemis-dist-<version>-exec.jar），不依赖 maven evaluate
VERSION="$(basename "$JAR" | sed 's/^artemis-dist-//; s/-exec\.jar$//')"

echo "==> assembling package (version $VERSION)"
rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR/bin" "$PKG_DIR/lib" "$PKG_DIR/data"
chmod +x "$TEMPLATE_DIR/bin/app.sh"
cp "$TEMPLATE_DIR/bin/app.sh" "$PKG_DIR/bin/"
cp "$TEMPLATE_DIR/artemis.properties" "$TEMPLATE_DIR/artemis.service" "$PKG_DIR/"
cp "$JAR" "$PKG_DIR/lib/artemis-dist.jar"
{
    echo "VERSION=$VERSION"
    echo "BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$PKG_DIR/lib/version"

echo "==> packing tarball"
# -C BUILD_DIR 打 artemis/ 前缀；data/ 空目录入包（tar 本身保留空目录）
tar -czf "$BUILD_DIR/artemis-$VERSION.tar.gz" -C "$BUILD_DIR" artemis

SIZE="$(du -h "$BUILD_DIR/artemis-$VERSION.tar.gz" | cut -f1)"
MD5="$(md5sum "$BUILD_DIR/artemis-$VERSION.tar.gz" | cut -d' ' -f1)"
echo "==> done"
echo "artifact: $BUILD_DIR/artemis-$VERSION.tar.gz ($SIZE)"
echo "md5: $MD5"
