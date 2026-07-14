// Minimal GitHub-flavoured Markdown renderer for the release-notes overlay.
//
// Covers what actually appears in the repo's release bodies: headings, bold /
// italic / strikethrough, inline + fenced code, links, images, blockquotes,
// lists, rules — plus the small raw-HTML subset the GitHub release editor
// emits around screenshots (<img>, <div align>, <br>). Everything else is
// escaped, so an unexpected tag renders as text instead of executing.

// Raw-HTML whitelist: matched BEFORE escaping, re-emitted sanitised (only the
// listed attributes survive; img src must be http(s)).
const RAW_TAG = /<img\b[^>]*\/?>|<div\b[^>]*>|<\/div>|<br\s*\/?>|<p\b[^>]*>|<\/p>|<table\b[^>]*>|<\/table>|<tr\b[^>]*>|<\/tr>|<t[dh]\b[^>]*>|<\/t[dh]>/gi;

// \u0000 (NUL) sentinels cannot occur in real text and survive HTML escaping, so
// protected fragments (raw tags, code spans) pass through the pipeline inert.
const RAW_PH = /\u0000R(\d+)\u0000/g;
const CODE_PH = /\u0000C(\d+)\u0000/g;

function sanitizeRawTag(tag) {
  const lower = tag.toLowerCase();
  if (lower.startsWith('<br')) return '<br>';
  if (lower === '</div>') return '</div>';
  if (lower === '</p>') return '</p>';
  if (lower.startsWith('<p')) return '<p>';
  if (lower === '</table>' || lower === '</tr>' || lower === '</td>' || lower === '</th>') return lower;
  if (lower.startsWith('<table')) return '<table>';
  if (lower.startsWith('<tr')) return '<tr>';
  if (lower.startsWith('<div') || lower.startsWith('<td') || lower.startsWith('<th')) {
    const name = lower.startsWith('<div') ? 'div' : (lower.startsWith('<td') ? 'td' : 'th');
    const align = /align\s*=\s*["']?(center|right|left)/i.exec(tag);
    return align ? `<${name} class="md-align-${align[1].toLowerCase()}">` : `<${name}>`;
  }
  // <img>: keep src/alt/width/height only.
  const src = /src\s*=\s*["']([^"']+)["']/i.exec(tag);
  if (!src || !/^https?:\/\//i.test(src[1])) return '';
  const alt = /alt\s*=\s*["']([^"']*)["']/i.exec(tag);
  const w = /width\s*=\s*["']?(\d+)/i.exec(tag);
  const h = /height\s*=\s*["']?(\d+)/i.exec(tag);
  return `<img src="${escapeAttr(src[1])}" alt="${alt ? escapeAttr(alt[1]) : ''}"` +
      (w ? ` width="${w[1]}"` : '') + (h ? ` height="${h[1]}"` : '') + ' loading="lazy">';
}

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttr(s) {
  return escapeHtml(s).replace(/"/g, '&quot;');
}

// ---- inline spans (input is already HTML-escaped) ----
function inline(text, codes) {
  return text
      // code spans first, protected from the other inline rules
      .replace(/`([^`\n]+)`/g, (_, c) => {
        codes.push(`<code>${c}</code>`);
        return `\u0000C${codes.length - 1}\u0000`;
      })
      .replace(/!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)/g,
          (_, alt, url) => `<img src="${escapeAttr(url)}" alt="${escapeAttr(alt)}" loading="lazy">`)
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
          (_, txt, url) => `<a href="${escapeAttr(url)}" target="_blank" rel="noopener">${txt}</a>`)
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/__([^_]+)__/g, '<strong>$1</strong>')
      .replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<em>$2</em>')
      .replace(/(^|[\s(])_([^_\n]+)_/g, '$1<em>$2</em>')
      .replace(/~~([^~]+)~~/g, '<del>$1</del>');
}

/** Renders a Markdown string to safe HTML. */
export function renderMarkdown(md) {
  if (!md) return '';

  // Pull the whitelisted raw tags out before escaping, re-insert sanitised after.
  const raw = [];
  const marked = md.replace(/\r\n?/g, '\n').replace(RAW_TAG, tag => {
    raw.push(sanitizeRawTag(tag));
    return `\u0000R${raw.length - 1}\u0000`;
  });

  const lines = escapeHtml(marked).split('\n');
  const codes = [];
  const out = [];
  let list = null;      // 'ul' | 'ol' while inside a list
  let quote = [];       // collected blockquote lines
  let para = [];        // collected paragraph lines
  let code = null;      // collected fenced-code lines, or null
  let table = [];       // collected pipe-table lines

  const span = s => inline(s, codes);
  const closeList = () => { if (list) { out.push(`</${list}>`); list = null; } };
  // ---- pipe tables (GFM subset: header row, separator row, data rows) ----
  const isTableRow = s => {
    const t = s.trim();
    return t.length > 1 && t.startsWith('|') && t.indexOf('|', 1) > 0;
  };
  const splitRow = s => {
    let r = s.trim();
    if (r.startsWith('|')) r = r.slice(1);
    if (r.endsWith('|')) r = r.slice(0, -1);
    return r.split('|').map(c => c.trim());
  };
  const isSepRow = s => {
    const cells = splitRow(s);
    return cells.length > 0 && cells.every(c => /^:?-+:?$/.test(c));
  };
  const flushTable = () => {
    if (!table.length) return;
    const rows = table;
    table = [];
    if (rows.length >= 2 && isSepRow(rows[1])) {
      const aligns = splitRow(rows[1]).map(c => c.startsWith(':') && c.endsWith(':')
          ? 'center' : c.endsWith(':') ? 'right' : c.startsWith(':') ? 'left' : '');
      const cell = (c, i, tag) =>
          `<${tag}${aligns[i] ? ` class="md-align-${aligns[i]}"` : ''}>${span(c)}</${tag}>`;
      const head = splitRow(rows[0]).map((c, i) => cell(c, i, 'th')).join('');
      const body = rows.slice(2)
          .map(r => `<tr>${splitRow(r).map((c, i) => cell(c, i, 'td')).join('')}</tr>`)
          .join('');
      // The wrapper div owns horizontal overflow so the page never scrolls sideways.
      out.push(`<div class="md-table"><table><thead><tr>${head}</tr></thead>` +
          `<tbody>${body}</tbody></table></div>`);
    } else {
      // Not a real pipe table (no separator row) — keep the lines as a paragraph.
      out.push(`<p>${rows.map(span).join('<br>')}</p>`);
    }
  };
  const flushQuote = () => {
    if (quote.length) {
      out.push(`<blockquote>${quote.map(span).join('<br>')}</blockquote>`);
      quote = [];
    }
  };
  const flushPara = () => {
    if (para.length) {
      // GitHub renders release bodies comment-style: single newline = line break.
      out.push(`<p>${para.map(span).join('<br>')}</p>`);
      para = [];
    }
  };
  const flushAll = () => { flushTable(); flushPara(); flushQuote(); closeList(); };

  for (const line of lines) {
    if (code !== null) {
      if (/^```/.test(line)) { out.push(`<pre><code>${code.join('\n')}</code></pre>`); code = null; }
      else code.push(line);
      continue;
    }
    if (table.length && !isTableRow(line)) flushTable();
    let m;
    if (/^```/.test(line)) { flushAll(); code = []; }
    else if ((m = /^(#{1,6})\s+(.*)$/.exec(line))) {
      flushAll();
      const level = m[1].length;
      out.push(`<h${level}>${span(m[2].trim())}</h${level}>`);
    } else if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      flushAll();
      out.push('<hr>');
    } else if ((m = /^&gt;\s?(.*)$/.exec(line))) {
      flushPara(); closeList();
      quote.push(m[1]);
    } else if ((m = /^\s*[-*+]\s+(.*)$/.exec(line))) {
      flushPara(); flushQuote();
      if (list !== 'ul') { closeList(); out.push('<ul>'); list = 'ul'; }
      out.push(`<li>${span(m[1])}</li>`);
    } else if ((m = /^\s*\d+\.\s+(.*)$/.exec(line))) {
      flushPara(); flushQuote();
      if (list !== 'ol') { closeList(); out.push('<ol>'); list = 'ol'; }
      out.push(`<li>${span(m[1])}</li>`);
    } else if (isTableRow(line)) {
      flushPara(); flushQuote(); closeList();
      table.push(line.trim());
    } else if (!line.trim()) {
      flushAll();
    } else if (/^(\u0000R\d+\u0000\s*)+$/.test(line.trim())) {
      // a raw-HTML-only line (e.g. <div align="center">) stands alone, not in a <p>
      flushAll();
      out.push(line.trim());
    } else {
      flushQuote(); closeList();
      para.push(line.trim());
    }
  }
  if (code !== null) out.push(`<pre><code>${code.join('\n')}</code></pre>`);
  flushAll();

  return out.join('\n')
      .replace(CODE_PH, (_, i) => codes[+i])
      .replace(RAW_PH, (_, i) => raw[+i]);
}
