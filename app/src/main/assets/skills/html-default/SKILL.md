---
name: html-default
description: "Automatically convert generated files to HTML format. Use when: (1) User requests file generation without specifying format (default to HTML), (2) User generates or references .md files (auto-convert to HTML), (3) Any file output should be in HTML for web viewing, (4) Converting Markdown documentation to web-ready HTML."
---

# HTML Default

## Overview

This skill ensures that all generated files default to HTML format and automatically converts Markdown files to HTML. It provides a simple Python script for converting Markdown to styled HTML that is ready for web viewing.

## Core Behavior

### Mandatory Workspace File Creation

When the user asks to generate a document, report, article, README, HTML page, or any file-like output, you MUST create a real file by calling the available file writing tool (for example `write`) instead of only replying with the document text in chat.

- Write generated files under `/root/.openclaw/workspace`.
- Use an absolute path such as `/root/.openclaw/workspace/shenzhen.html`.
- Do not rely on the Android client to create or export files from assistant text.
- After writing, briefly tell the user the file path that was created.
- If no format is specified, create an `.html` file by default.

**Required workflow:**
```
User: "生成一个深圳的文档"
Action: call write with path=/root/.openclaw/workspace/shenzhen.html and full HTML content
Reply: 已生成：/root/.openclaw/workspace/shenzhen.html
```

**Do not do this:**
```
User: "生成一个深圳的文档"
Action: only output the full document text in chat without a write/tool call
```

### Default Output Format

When a user requests file creation without specifying the format:

**Example 1 - Default to HTML:**
```
User: "Generate a report on sales data"
Action: call write to create /root/.openclaw/workspace/sales-data.html (not sales-data.md or sales-data.txt)
```

**Example 2 - Implicit HTML:**
```
User: "Create a document about the project"
Action: call write to create /root/.openclaw/workspace/project-document.html with proper HTML structure
```

### Markdown to HTML Conversion

When the user generates or references a .md file, automatically convert it to HTML:

**Example 1 - Convert existing MD:**
```
User: "I created report.md, can you format it?"
Action: Convert report.md → report.html using the conversion script
```

**Example 2 - Generate as HTML:**
```
User: "Generate a README for this project"
Action: Create README.md first, then immediately convert to README.html
```

**Example 3 - Update workflow:**
```
User: "Update the documentation file"
Action: Edit the .md file, then regenerate the .html version
```

## Conversion Process

### Using the Markdown to HTML Script

The script `scripts/md_to_html.py` converts Markdown files to styled HTML:

**Basic usage:**
```bash
python3 scripts/md_to_html.py input.md
# Output: input.html
```

**Specify output path:**
```bash
python3 scripts/md_to_html.py input.md -o output.html
```

**Verbose mode:**
```bash
python3 scripts/md_to_html.py input.md -v
# Output: ✓ Converted input.md -> input.html
```

### Script Features

The converter includes:
- **Automatic title extraction** from first heading
- **Basic HTML5 structure** with proper DOCTYPE and metadata
- **Built-in CSS styling** for readability
- **Markdown extensions** support: tables, fenced code blocks, TOC
- **UTF-8 encoding** for international characters
- **Responsive design** with mobile-friendly viewport

### Styling Included

The generated HTML includes CSS for:
- Typography (headings, paragraphs, lists)
- Code blocks and inline code
- Blockquotes with left border
- Tables with borders and headers
- Links with hover effects
- Images that scale responsively
- Horizontal rules

## Workflow Decision Tree

```
User requests file generation?
│
├─ Yes, format specified?
│   ├─ Yes → Use specified format (PDF, TXT, etc.)
│   └─ No  → Default to HTML format
│
└─ No, references existing file?
    ├─ Is .md file?
    │   └─ Yes → Convert to .html
    └─ No  → Process normally
```

## Best Practices

### When to Use HTML Default

✅ **Use when:**
- User says "generate a document" without format
- User says "create a file" without extension
- Working with Markdown documentation
- Preparing content for web viewing
- Creating reports or articles for display

❌ **Don't use when:**
- User explicitly requests .md, .txt, .pdf, etc.
- Working with code files (.py, .js, etc.)
- User needs raw Markdown for editing
- File is for CLI tools or scripts
- Format must remain unstyled

### File Management

- **Keep both versions**: When converting MD to HTML, keep the original .md file for editing
- **Update both**: If content changes, regenerate HTML from updated MD
- **Consistent naming**: Use same base name (e.g., document.md → document.html)

### HTML Generation Tips

When generating HTML from scratch (not from MD):

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Document Title</title>
</head>
<body>
    <!-- Content here -->
</body>
</html>
```

Include basic structure and consider adding CSS for styling.

## Resources

### scripts/md_to_html.py

Python script for converting Markdown to HTML with built-in styling.

**Usage:**
```bash
python3 scripts/md_to_html.py <input.md> [-o <output.html>] [-v]
```

**Features:**
- Converts Markdown to valid HTML5
- Extracts title from first heading
- Includes responsive CSS styling
- Supports tables, code blocks, and TOC
- UTF-8 encoding support

**Dependencies:**
- Python 3.6+
- markdown package: `pip install markdown`

**Testing the script:**
```bash
# Create test MD
echo "# Test\n\nHello **world**!" > test.md

# Convert
python3 scripts/md_to_html.py test.md

# Open in browser
open test.html  # macOS
xdg-open test.html  # Linux
start test.html  # Windows
```
