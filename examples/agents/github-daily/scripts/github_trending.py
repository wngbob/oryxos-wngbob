"""GitHub 热门项目抓取：今日新星 / 本月新星 / 本月 AI 相关热门。

只用标准库，免 token（未认证限速 10 次/分钟，日跑一次绰绰有余）。
用「按创建日期区间 + stars 排序」近似 trending，避开 GitHub 没有官方 trending API 的坑。
产物是 stdout 上的一段 JSON：脚本源码不进模型上下文，只有这段 JSON 进。
"""

import json
import sys
from datetime import date, timedelta
from urllib.parse import quote
from urllib.request import Request, urlopen

API = "https://api.github.com/search/repositories"
HDR = {"User-Agent": "oryxos-github-digest", "Accept": "application/vnd.github+json"}


def search(q, n=5):
    url = f"{API}?q={quote(q)}&sort=stars&order=desc&per_page={n}"
    with urlopen(Request(url, headers=HDR), timeout=20) as r:
        items = json.load(r).get("items", [])
    return [
        {
            "name": i["full_name"],
            "stars": i["stargazers_count"],
            "desc": (i.get("description") or "")[:120],
            "url": i["html_url"],
        }
        for i in items
    ]


today = date.today()
yday = (today - timedelta(days=1)).isoformat()
month0 = today.replace(day=1).isoformat()
out = {
    "today": search(f"created:>{yday}"),
    "month": search(f"created:>{month0}"),
    "ai": search(f"created:>{month0} topic:ai topic:machine-learning topic:llm"),
}
json.dump(out, sys.stdout, ensure_ascii=False)
sys.stdout.write("\n")
