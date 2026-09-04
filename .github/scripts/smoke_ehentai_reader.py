from html.parser import HTMLParser
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen
import re

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
COOKIE = "nw=1"


def get(url: str, referer: str | None = None) -> tuple[bytes, str]:
    headers = {"User-Agent": UA, "Cookie": COOKIE}
    if referer:
        headers["Referer"] = referer
    req = Request(url, headers=headers)
    with urlopen(req, timeout=30) as r:
        return r.read(), r.headers.get("Content-Type", "")


home, _ = get("https://e-hentai.org/?f_search=language%3Achinese")
home_text = home.decode("utf-8", errors="ignore")
gallery_match = re.search(r"https://e-hentai\.org/g/\d+/[0-9a-z]+/?", home_text, re.I)
if not gallery_match:
    raise SystemExit("No public gallery URL found")
gallery = gallery_match.group(0)

gallery_body, _ = get(gallery, "https://e-hentai.org/")
gallery_text = gallery_body.decode("utf-8", errors="ignore")
viewer_match = re.search(r"https://e-hentai\.org/s/[0-9a-z]+/\d+-\d+", gallery_text, re.I)
if not viewer_match:
    raise SystemExit(f"No viewer URL found in {gallery}")
viewer = viewer_match.group(0)


class ImageParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.src: str | None = None

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        if tag.lower() == "img" and data.get("id") == "img":
            self.src = data.get("src")


viewer_body, _ = get(viewer, gallery)
parser = ImageParser()
parser.feed(viewer_body.decode("utf-8", errors="ignore"))
image = parser.src
if not image:
    raise SystemExit("No #img src found on viewer page")
if image in ("https://ehgt.org/g/509.gif", "https://exhentai.org/img/509.gif"):
    raise SystemExit("E-Hentai returned the 509 image quota placeholder")
parts = urlsplit(image)
if parts.hostname == "s.exhentai.org":
    parts = parts._replace(netloc="ehgt.org" + (f":{parts.port}" if parts.port else ""))
    image = urlunsplit(parts)

image_body, content_type = get(image, viewer)
if not content_type.lower().startswith("image/"):
    raise SystemExit(f"Resolved URL is not an image: {content_type} {image}")
if len(image_body) <= 1024:
    raise SystemExit(f"Resolved image is unexpectedly small: {len(image_body)} bytes")

print(f"gallery={gallery}")
print(f"viewer={viewer}")
print(f"image={image}")
print(f"content_type={content_type} bytes={len(image_body)}")
