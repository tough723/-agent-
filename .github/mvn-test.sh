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
  # 只有首行不够：ERROR（抛异常）和 FAILURE（断言不符）的处置完全不同，
  # 而区分它们要看栈。
  #
  # 注意 GitHub 的上限是「每个 run 每个级别 10 条 annotation」，
  # 所以不能逐行发——上一轮发了 10 条 [ERROR] 首行，把栈全挤掉了。
  # 这里把首个失败报告的关键段落压成【一条】多行 annotation（换行用 %0A 转义）。
  grep -E "^\[ERROR\]|cannot find symbol|BUILD FAILURE" "$log" \
    | head -6 \
    | sed -e 's/%/%25/g' -e 's/\r//g' -e 's/^/::error::/' || true

  rpt=$(grep -lE "Failures: [1-9]|Errors: [1-9]" "$module"/target/surefire-reports/*.txt 2>/dev/null | head -1 || true)
  if [ -n "$rpt" ]; then
    echo "===== 失败测试报告: $rpt ====="
    # 跳过表头，取前 22 行栈；多行消息在 annotation 里必须用 %0A 表示换行
    detail=$(sed -n '4,25p' "$rpt" | sed -e 's/%/%25/g' -e 's/\r//g' | paste -sd'|' - | sed 's/|/%0A/g')
    echo "::error::[失败栈] $(basename "$rpt")%0A${detail}"
  fi
fi

echo "----- ${module} 日志末尾 -----"
tail -60 "$log"
exit "$rc"
