#!/usr/bin/env python3
"""Build drive mode: paste Otter into src.html and write index.html.

    python3 build.py

Edit src.html, never index.html. Otter's stylesheet is copied in whole from
~/.claude/skills/otter-design/otter.css (the design language's rule: inline it,
keep its markers). Refuses to build if the page would contain a key.
"""
import pathlib, re, sys
HERE = pathlib.Path(__file__).resolve().parent
otter = (pathlib.Path.home() / ".claude/skills/otter-design/otter.css").read_text()
src = (HERE / "src.html").read_text()
page = src.replace("/*OTTER*/", otter.strip())
if re.search(r"(sk-ant-|key=[0-9a-f]{20,}|[0-9a-f]{48})", page):
    sys.exit("A key-like string is in the page. Not building.")
(HERE / "index.html").write_text(page)
print("index.html written,", len(page), "bytes")
