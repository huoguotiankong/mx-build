#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from urllib.request import Request, urlopen


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def patch_source(root: Path) -> None:
    gradle = root / "src/zh/dongmanmanhuaplus/build.gradle.kts"
    g = gradle.read_text("utf-8")
    if "versionCode = 7" not in g:
        if "versionCode = 6" not in g:
            raise SystemExit("unexpected Dongman versionCode")
        g = g.replace("versionCode = 6", "versionCode = 7", 1)
        gradle.write_text(g, "utf-8")

    kt = root / "src/zh/dongmanmanhuaplus/src/eu/kanade/tachiyomi/extension/zh/dongmanmanhuaplus/DongmanManhuaPlus.kt"
    s = kt.read_text("utf-8")
    s = replace_once(
        s,
        '''        val comments = root.toChapterComments()
        val regularCount = root.dataArray("commentList")?.size ?: 0
        return CommentPage(
            comments = comments,
            hasNextPage = regularCount >= COMMENT_PAGE_SIZE,
            totalCount = root.firstLong("commentCount", "totalCount", "total", "count"),
        )''',
        '''        val comments = root.toChapterComments()
        val returnedCount = (root.dataArray("bestList")?.size ?: 0) +
            (root.dataArray("commentList")?.size ?: 0)
        val topLevelCount = root.firstLong("count")
        val currentPage = page.coerceAtLeast(1)
        return CommentPage(
            comments = comments,
            hasNextPage = topLevelCount?.let {
                currentPage.toLong() * COMMENT_PAGE_SIZE < it
            } ?: (returnedCount >= COMMENT_PAGE_SIZE),
            totalCount = root.firstLong("showTotalCount", "commentCount", "totalCount", "total", "count"),
        )''',
        "main comment pagination",
    )
    s = replace_once(
        s,
        '''        val replies = root.toReplyComments(comment.id)
        return CommentPage(
            comments = replies,
            hasNextPage = replies.size >= COMMENT_PAGE_SIZE,
            totalCount = root.firstLong("count") ?: comment.replyCount,
        )''',
        '''        val replies = root.toReplyComments(comment.id)
        val replyTotal = root.firstLong("count") ?: comment.replyCount
        val currentPage = page.coerceAtLeast(1)
        return CommentPage(
            comments = replies,
            hasNextPage = currentPage.toLong() * COMMENT_PAGE_SIZE < replyTotal,
            totalCount = replyTotal,
        )''',
        "reply pagination",
    )
    s = replace_once(
        s,
        'avatarUrl = stringAny("profileImage", "profileImageUrl", "avatarUrl"),',
        'avatarUrl = stringAny("userProfileImage", "userProfileImageUrl", "profileImage", "profileImageUrl", "avatarUrl"),',
        "avatar aliases",
    )
    if "private const val COMMENT_PAGE_SIZE = 10" not in s:
        if "private const val COMMENT_PAGE_SIZE = 20" not in s:
            raise SystemExit("missing COMMENT_PAGE_SIZE")
        s = s.replace("private const val COMMENT_PAGE_SIZE = 20", "private const val COMMENT_PAGE_SIZE = 10", 1)
    kt.write_text(s, "utf-8")

    doc = root / "docs/sources/dongmanmanhuaplus.md"
    d = doc.read_text("utf-8")
    d = re.sub(
        r"- 当前源码 `versionCode = \d+`；[^\n]*",
        "- 当前源码 `versionCode = 7`；v1.6.6 图标已由用户实机确认恢复，v1.6.7 修复章评分页。",
        d,
        count=1,
    )
    if "## v1.6.7 章评分页与头像核验" not in d:
        d += r'''

## v1.6.7 章评分页与头像核验

### 用户实机反馈

2026-09-06，用户确认 v1.6.6 图标已经恢复，同时反馈章评只能看到第一页的一部分评论，且所有评论均显示宿主默认头像。

### 分页根因

实时请求 `/v2/comment` 确认，服务端实际固定按 **10 个展示槽位/页** 返回数据，即使请求 `pageSize=20` 或 `pageSize=50` 也不会增加单页返回量。样本 `titleNo=2795&episodeNo=1`：第 1 页 `bestList=3`、`commentList=7`；第 2~5 页各 10 条；第 6 页 4 条；第 7 页空；`count=54`，`showTotalCount=64`。

旧代码用 `commentList.size >= 20` 判断下一页，因此第一页普通评论只有 7 条时立即停止。v1.6.7 将有效分页大小改为 10，并优先按服务端 `count` 与当前页判断 `hasNextPage`；回复列表同步使用 10 条分页。评论页总数优先使用 `showTotalCount`，该字段包含一级评论及其回复，与页面“共 N 条”的语义一致。

### 头像核验

实时跨 24 个当前首页作品抽样 231 条章评，所有评论均为 `imageNo = "*"`。当前匿名 `/v1/comment`、`/v2/comment` 响应均没有 `profileImage`、`avatarUrl`、`userProfileImage` 或任何可直接加载的头像 URL。官方 APK 模型虽然存在 `userProfileImage` 字段，但当前公开章评响应没有下发，因此宿主显示默认头像是当前接口没有提供可用头像数据，不是扩展漏掉现有头像 URL。

解析器继续兼容 `userProfileImage` / `userProfileImageUrl` / `profileImage` / `profileImageUrl` / `avatarUrl`，后续接口重新下发头像即可直接显示；不根据 `imageNo="*"` 伪造头像地址。
'''
    doc.write_text(d, "utf-8")


def get_json(url: str) -> dict:
    req = Request(url, headers={"Accept": "application/json", "User-Agent": "Dongman/Android"})
    with urlopen(req, timeout=20) as r:
        return json.load(r)


def verify_live() -> None:
    base = "https://apis.dongmanmanhua.cn/v2/comment?titleNo=2795&episodeNo=1&pageSize=10&pageNo="
    pages = {p: get_json(base + str(p)) for p in (1, 2, 6, 7)}
    for p, payload in pages.items():
        assert payload.get("code") == 200, (p, payload)
    d1, d2, d6, d7 = (pages[p]["data"] for p in (1, 2, 6, 7))
    page1 = (d1.get("bestList") or []) + (d1.get("commentList") or [])
    assert len(page1) == 10, len(page1)
    assert len(d1.get("commentList") or []) < 10
    assert len(d2.get("commentList") or []) == 10
    assert len(d6.get("commentList") or []) > 0
    assert len(d7.get("commentList") or []) == 0
    count = int(d1["count"])
    shown = int(d1["showTotalCount"])
    assert 10 < count <= 60
    assert shown >= count
    assert all(x.get("imageNo") == "*" for x in page1)
    print(f"live pagination verified: count={count}, showTotalCount={shown}, page1=10, page2=10, page6={len(d6.get('commentList') or [])}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path)
    parser.add_argument("--verify-live", action="store_true")
    args = parser.parse_args()
    if args.source_root:
        patch_source(args.source_root.resolve())
    if args.verify_live:
        verify_live()
    if not args.source_root and not args.verify_live:
        parser.error("provide --source-root and/or --verify-live")


if __name__ == "__main__":
    main()
