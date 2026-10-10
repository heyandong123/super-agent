#!/usr/bin/env bash
#
# 密钥守卫：扫描「即将进入 git 的文件」里是否含明文凭据。
#
# 扫描范围 = 已跟踪文件 + 未跟踪且未被忽略的文件。
# 因此被 .gitignore 忽略的 application-local.yml 天然不在范围内 —— 真值只许放那里。
# 局限：扫的是工作区内容，能拦「将来的」泄露，拦不住已经在历史里的明文（那靠提交轮清单）。
#
# 用法：
#   bash scripts/check-secrets.sh      # 命中即退出码 1
#
# 输出只给「文件:行号」+ 打码后的片段，任何疑似凭据的值都不会以明文打到终端/CI 日志。

set -uo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "check-secrets: 当前目录不在 git 仓库内" >&2
  exit 2
}

# ---------------------------------------------------------------- 待扫描清单
list=$(mktemp) || exit 2
trap 'rm -f "$list"' EXIT
{ git ls-files; git ls-files --others --exclude-standard; } | sort -u > "$list"

# 第三方依赖与构建产物不参与扫描（密钥只会由我们自己写进源码/配置）。
# 注意 node_modules 目前仍被误跟踪，等提交轮清出索引后才真正消失 —— 这里先按路径排除。
EXCLUDE_RE='(^|/)(node_modules|target|dist|build|\.git|\.idea|\.vscode)/'

files=()
while IFS= read -r f; do
  [ -f "$f" ] || continue
  [[ "$f" =~ $EXCLUDE_RE ]] && continue
  files+=("$f")
done < "$list"

# ---------------------------------------------------------------- 规则
# 规则一：各家 API Key 的固定形态（sk- 系）
RE_KEY='sk-[A-Za-z0-9_-]{20,}'
# 规则二：键值对形态。值要求 ≥12 位，避免把 token = getToken() 这类代码误判成凭据
RE_KV='(api[-_]?key|apikey|password|passwd|secret|access[-_]?key|accesskey|private[-_]?key|token)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9@#$%^&*_+./-]{12,}'

# 放行：占位符/文档说明（${VAR}、<REDACTED>、your-api-key、示例、CHANGE_ME、xxxx）
# 放行：URL 查询串里的 ?token= / &key=（推广链接、跳转地址，不是我们的凭据）
ALLOW='\$\{|<[^>]*>|REDACTED|your[-_]?api|your[-_]?key|示例|placeholder|CHANGE[_-]?ME|xxxx|TODO|[?&](token|key|apikey|access_key|password)='

report=""

scan() {
  local label="$1" pattern="$2" raw
  raw=$(grep -HnIE -- "$pattern" "${files[@]}" 2>/dev/null || true)
  raw=$(printf '%s\n' "$raw" | grep -vE -- "$ALLOW" || true)
  [ -z "$(printf '%s' "$raw" | tr -d '[:space:]')" ] && return 0

  printf '%s\n' "$raw" | while IFS= read -r line; do
    # 关键：必须先把 grep 的「文件:行号:」前缀剥掉再打码。
    # 否则行号会被正则当成键名、真实值被当成值域，**** 会打在错误的位置上，真值反而漏出来。
    loc=$(printf '%s' "$line" | sed -E 's/^(.+:[0-9]+):.*/\1/')
    body=$(printf '%s' "$line" | sed -E 's/^.+:[0-9]+://')
    masked=$(printf '%s' "$body" \
      | sed -E \
          -e 's/(sk-[A-Za-z0-9_-]{4})[A-Za-z0-9_-]+/\1****/g' \
          -e 's/[:=][[:space:]]*[^[:space:]]{6,}/: ****/g' \
      | cut -c1-100)
    printf '  %s  [%s]  %s\n' "$loc" "$label" "$masked"
  done
}

# 注意 $(...) 会吃掉末尾换行，所以换行要写在命令替换外面
report+=$(scan "API-Key" "$RE_KEY")$'\n'
report+=$(scan "键值对"  "$RE_KV")$'\n'
# 同一行被两条规则同时命中时只报一次（保留先出现的 API-Key 形态，信号更强）
report=$(printf '%s\n' "$report" | grep -v '^$' | awk '!seen[$1]++' || true)

fail=0

count=$(printf '%s\n' "$report" | grep -c . || true)
if [ "$count" -gt 0 ]; then
  fail=1
  echo "❌ 检测到 $count 处疑似明文凭据（值已打码，按「文件:行号」定位）："
  echo
  printf '%s\n' "$report" | head -40
  [ "$count" -gt 40 ] && echo "  ...（还有 $((count - 40)) 处未列出）"
  echo
fi

# ---------------------------------------------------------------- 附加检查
# 真值文件必须仍处于忽略状态（防止有人把它从 .gitignore 里删掉）
if [ -f application-local.yml ] && ! git check-ignore -q application-local.yml; then
  fail=1
  echo "❌ application-local.yml 已不再被 .gitignore 忽略，随时可能被提交"
fi

if [ "$fail" -ne 0 ]; then
  echo "真值只写 application-local.yml（未跟踪 + 已忽略），被跟踪文件一律用 \${ENV:default} 占位符。"
  exit 1
fi

echo "✅ 未在待提交文件中发现明文凭据"
