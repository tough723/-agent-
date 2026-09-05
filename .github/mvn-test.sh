#!/usr/bin/env bash
# 跑单个模块的测试，失败时把关键错误行作为 GitHub check-run annotation 发出。
#
# 为什么需要这个脚本：
#   CI 的 job 日志在开发沙箱里取不到（actions/jobs/<id>/logs 下载接口 SSL 失败，
#   gh run view --log 也是空）。唯一能从 API 侧读到的输出通道是 check-run 的
#   annotation 与 ::notice:: / ::error:: 指令。没有它，构建失败时只能靠猜。
#
# 用法：./.github/mvn-test.sh <module>
set -uo pipefail

module="${1:?用法: mvn-test.sh <module>}"
log="/tmp/build-${module}.log"

# 这里刻意不用 -e：要拿到 Maven 的真实退出码，而不是被 grep 的退出码顶掉。
set +e
mvn -B -pl "$module" -am test > "$log" 2>&1
rc=$?
set -e

if [ "$rc" -ne 0 ]; then
  echo "===== ${module} 构建失败，退出码 ${rc} ====="
  # annotation 的消息里 % 和回车必须转义，否则 GitHub 会解析错乱
  # 末尾的 || true 是必需的：脚本此处处于 set -e 且 pipefail 生效，
  # grep 没匹配到任何行会返回 1，直接让脚本以 1 退出并跳过下面的 tail。
  grep -E "^\[ERROR\]|error:|BUILD FAILURE|cannot find symbol|符号|Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)" "$log" \
    | head -80 \
    | sed -e 's/%/%25/g' -e 's/\r//g' -e 's/^/::error::/' || true
fi

echo "----- ${module} 日志末尾 -----"
tail -60 "$log"
exit "$rc"
