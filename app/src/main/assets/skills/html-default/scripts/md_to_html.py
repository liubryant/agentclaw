#!/usr/bin/env python3
"""
Markdown to HTML converter
Converts Markdown files to HTML format with basic styling.
"""

import os
import sys
import argparse
import markdown
from pathlib import Path


# Basic HTML template with styling
HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title}</title>
    <style>
        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
        }}
        h1, h2, h3, h4, h5, h6 {{
            margin-top: 1.5em;
            margin-bottom: 0.5em;
            line-height: 1.2;
        }}
        h1 {{
            color: #2c3e50;
            border-bottom: 2px solid #3498db;
            padding-bottom: 10px;
        }}
        h2 {{
            color: #34495e;
            border-bottom: 1px solid #bdc3c7;
            padding-bottom: 5px;
        }}
        h3 {{
            color: #7f8c8d;
        }}
        code {{
            background-color: #f8f8f8;
            border: 1px solid #ddd;
            border-radius: 3px;
            padding: 2px 5px;
            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
            font-size: 0.9em;
        }}
        pre {{
            background-color: #f8f8f8;
            border: 1px solid #ddd;
            border-radius: 5px;
            padding: 15px;
            overflow-x: auto;
        }}
        pre code {{
            background-color: transparent;
            border: none;
            padding: 0;
        }}
        blockquote {{
            border-left: 4px solid #3498db;
            padding-left: 15px;
            margin-left: 0;
            color: #7f8c8d;
        }}
        ul, ol {{
            padding-left: 20px;
        }}
        li {{
            margin: 5px 0;
        }}
        a {{
            color: #3498db;
            text-decoration: none;
        }}
        a:hover {{
            text-decoration: underline;
        }}
        table {{
            border-collapse: collapse;
            width: 100%;
            margin: 15px 0;
        }}
        th, td {{
            border: 1px solid #ddd;
            padding: 8px 12px;
            text-align: left;
        }}
        th {{
            background-color: #f8f8f8;
            font-weight: bold;
        }}
        img {{
            max-width: 100%;
            height: auto;
        }}
        hr {{
            border: none;
            border-top: 1px solid #ddd;
            margin: 20px 0;
        }}
    </style>
</head>
<body>
    {content}
</body>
</html>
"""


def convert_md_to_html(md_path, html_path=None):
    """
    Convert a Markdown file to HTML.

    Args:
        md_path: Path to the Markdown file
        html_path: Path to save the HTML file (optional, defaults to same name with .html extension)

    Returns:
        Path to the generated HTML file
    """
    md_path = Path(md_path)

    if not md_path.exists():
        raise FileNotFoundError(f"Markdown file not found: {md_path}")

    if html_path is None:
        html_path = md_path.with_suffix('.html')
    else:
        html_path = Path(html_path)

    # Read Markdown content
    with open(md_path, 'r', encoding='utf-8') as f:
        md_content = f.read()

    # Convert to HTML
    md = markdown.Markdown(extensions=['tables', 'fenced_code', 'toc'])
    html_content = md.convert(md_content)

    # Extract title from first heading or use filename
    title = md_path.stem
    lines = md_content.split('\n')
    for line in lines:
        if line.strip().startswith('#'):
            title = line.strip().lstrip('#').strip()
            break

    # Apply template
    full_html = HTML_TEMPLATE.format(title=title, content=html_content)

    # Write HTML file
    with open(html_path, 'w', encoding='utf-8') as f:
        f.write(full_html)

    return html_path


def main():
    parser = argparse.ArgumentParser(description='Convert Markdown to HTML')
    parser.add_argument('input', help='Input Markdown file')
    parser.add_argument('-o', '--output', help='Output HTML file (optional)')
    parser.add_argument('-v', '--verbose', action='store_true', help='Verbose output')

    args = parser.parse_args()

    try:
        output_path = convert_md_to_html(args.input, args.output)
        if args.verbose:
            print(f"✓ Converted {args.input} -> {output_path}")
        else:
            print(str(output_path))
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
