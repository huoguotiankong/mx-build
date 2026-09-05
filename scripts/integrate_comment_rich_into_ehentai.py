#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
source_rel = "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
test_rel = "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
source_path = root / source_rel
test_path = root / test_rel

def git_show(ref: str, path: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(root), "show", f"{ref}:{path}"],
        text=True,
        encoding="utf-8",
    )

def replace_slice(target: str, donor: str, start_marker: str, end_marker: str) -> str:
    target_start = target.index(start_marker)
    target_end = target.index(end_marker, target_start)
    donor_start = donor.index(start_marker)
    donor_end = donor.index(end_marker, donor_start)
    return target[:target_start] + donor[donor_start:donor_end] + target[target_end:]

target = source_path.read_text("utf-8")
donor = git_show("origin/feat/comment-rich-content-v2", source_rel)

# The E-Hentai branch owns the vote controls. The rich-comment branch owns media parsing/rendering.
# Keep the vote UI intact and transplant only the final rich-media implementation.
if "import java.net.URI\n" not in target:
    target = target.replace("import java.time.Instant\n", "import java.net.URI\nimport java.time.Instant\n", 1)

media_start = "                richContent.imageUrls.forEach { imageUrl ->\n"
media_end = "\n\n                Row(\n                    verticalAlignment = Alignment.CenterVertically,\n"
target = replace_slice(target, donor, media_start, media_end)

parser_start = "internal data class CommentRichContent(\n"
parser_end = "private fun formatCommentTime(timestamp: Long): String {\n"
target = replace_slice(target, donor, parser_start, parser_end)

required_vote_markers = [
    "CommentVoteState",
    "supportsVotes",
    "Icons.Outlined.ArrowUpward",
    "Icons.Outlined.ArrowDownward",
    "onVote(comment, CommentVoteState.UPVOTE)",
    "onVote(comment, CommentVoteState.DOWNVOTE)",
]
for marker in required_vote_markers:
    assert marker in target, f"E-Hentai vote UI lost during integration: {marker}"

required_rich_markers = [
    "decodeCommentEntities",
    "collectExplicitCommentMedia",
    "extractBareCommentMedia",
    "onError = { imageLoadFailed = true }",
    "COMMENT_KUAIKAN_IMAGE_HOST_REGEX",
    "COMMENT_MARKDOWN_IMAGE_REGEX",
    "COMMENT_BBCODE_IMAGE_REGEX",
]
for marker in required_rich_markers:
    assert marker in target, f"Rich-comment implementation missing after integration: {marker}"

source_path.write_text(target, "utf-8")

# The donor test file is a strict superset of the older parser tests on the E-Hentai branch.
test_path.write_text(git_show("origin/feat/comment-rich-content-v2", test_rel), "utf-8")
