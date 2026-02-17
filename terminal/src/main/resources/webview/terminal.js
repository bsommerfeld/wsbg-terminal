var maxSourceWidth = 40;

function checkWidth(text) {
    var tester = document.getElementById('width-tester');
    if (!tester) return;
    tester.textContent = text;
    var w = tester.getBoundingClientRect().width;
    var req = Math.ceil(w);
    if (req > maxSourceWidth) {
        maxSourceWidth = req;
        document.documentElement.style.setProperty('--source-width', maxSourceWidth + 'px');
    }
}

var spinnerFrames = ['|', '/', '-', '\\'];
var frameIndex = 0;
var spinnerInterval = null;
var isAtBottom = true;
var scrollTimeout = null;

// Sentinel-based auto-scroll detection
var observer = new IntersectionObserver(function (entries) {
    isAtBottom = entries[0].isIntersecting;
});

window.addEventListener('load', function () {
    var sentinel = document.getElementById('sentinel');
    if (sentinel) observer.observe(sentinel);
});

// Scrollbar visibility class
window.addEventListener('scroll', function () {
    document.body.classList.add('scrolling');
    if (scrollTimeout) clearTimeout(scrollTimeout);
    scrollTimeout = setTimeout(function () {
        document.body.classList.remove('scrolling');
    }, 1000);
}, { passive: true });

function startSpinner() {
    if (spinnerInterval) return;
    spinnerInterval = setInterval(function () {
        var el = document.getElementById('spinner');
        if (el) {
            el.innerText = spinnerFrames[frameIndex];
            frameIndex = (frameIndex + 1) % spinnerFrames.length;
        }
    }, 100);
}

function stopSpinner() {
    if (spinnerInterval) {
        clearInterval(spinnerInterval);
        spinnerInterval = null;
    }
}

var activeSearchTerm = '';

function highlightTextInNode(rootNode, term) {
    if (!term || term.trim() === '') return;
    var safeTerm = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    var walker = document.createTreeWalker(rootNode, NodeFilter.SHOW_TEXT, null, false);
    var textNodes = [];
    while (walker.nextNode()) textNodes.push(walker.currentNode);
    var regex = new RegExp('(' + safeTerm + ')', 'gi');
    textNodes.forEach(function (node) {
        if (node.parentNode.classList.contains('timestamp') || node.parentNode.classList.contains('source')) return;
        var text = node.nodeValue;
        if (text.match(regex)) {
            var span = document.createElement('span');
            span.innerHTML = text.replace(regex, '<span class="search-highlight">$1</span>');
            node.parentNode.replaceChild(span, node);
        }
    });
}

function appendLog(html, extraClass) {
    var content = document.getElementById('content');
    var div = document.createElement('div');
    div.className = 'log-entry ' + (extraClass || '');
    div.innerHTML = html;
    var src = div.querySelector('.log-source');
    if (src) checkWidth(src.textContent);
    if (activeSearchTerm) highlightTextInNode(div, activeSearchTerm);
    content.appendChild(div);
    if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);
}

var currentStreamDiv = null;

function startStreamLog(headerHtml, extraClass) {
    var content = document.getElementById('content');
    currentStreamDiv = document.createElement('div');
    currentStreamDiv.className = 'log-entry stream-active ' + (extraClass || '');
    currentStreamDiv.innerHTML = headerHtml + '<span class="content"></span>';
    var src = currentStreamDiv.querySelector('.log-source');
    if (src) checkWidth(src.textContent);
    content.appendChild(currentStreamDiv);
    if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);
}

function appendStreamToken(tokenHtml) {
    if (currentStreamDiv) {
        var contentSpan = currentStreamDiv.querySelector('.content');
        if (contentSpan) contentSpan.innerHTML += tokenHtml;
        if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);
    }
}

function endStreamLog() {
    currentStreamDiv = null;
}

function removeCurrentStream() {
    if (currentStreamDiv) {
        currentStreamDiv.remove();
        currentStreamDiv = null;
    }
    var zombies = document.querySelectorAll('.stream-active');
    zombies.forEach(function (el) { el.remove(); });
}

function updateLastRedditLog(htmlContent) {
    var content = document.getElementById('content');
    var last = content.lastElementChild;
    if (last && last.classList.contains('log-type-source-REDDIT')) {
        var contentSpan = last.querySelector('.content');
        if (contentSpan) {
            var oldVals = [];
            contentSpan.querySelectorAll('.animated-val').forEach(function (s) {
                oldVals.push(parseInt(s.innerText));
            });
            contentSpan.innerHTML = htmlContent;
            var newSpans = contentSpan.querySelectorAll('.animated-val');
            newSpans.forEach(function (span, i) {
                if (i < oldVals.length) {
                    var start = oldVals[i];
                    var end = parseInt(span.innerText);
                    if (!isNaN(start) && !isNaN(end) && start !== end) {
                        animateValue(span, start, end);
                    }
                }
            });
        }
    }
}

function animateValue(obj, start, end) {
    var delta = end - start;
    if (delta === 0) return;
    var duration = Math.min(Math.abs(delta) * 100, 2500);
    if (duration < 600) duration = 600;
    var startTime = null;
    function step(timestamp) {
        if (!startTime) startTime = timestamp;
        var progress = timestamp - startTime;
        var pct = Math.min(progress / duration, 1.0);
        obj.innerText = Math.floor(start + (delta * pct));
        if (progress < duration) {
            window.requestAnimationFrame(step);
        } else {
            obj.innerText = end;
        }
    }
    window.requestAnimationFrame(step);
}

function setStatus(text) {
    var st = document.getElementById('status');
    if (!text) {
        st.innerHTML = '';
        stopSpinner();
    } else {
        st.innerHTML = text + '<span id="spinner">|</span>';
        startSpinner();
    }
    if (isAtBottom) window.scrollTo(0, document.body.scrollHeight);
}

function clearLogs() {
    document.getElementById('content').innerHTML = '';
    setStatus('');
}

var currentMatchIndex = -1;

function findNextSearchTerm() {
    var content = document.getElementById('content');
    if (!content) return;
    var highlights = content.querySelectorAll('.search-highlight');
    if (highlights.length === 0) return;
    if (currentMatchIndex >= 0 && currentMatchIndex < highlights.length) {
        highlights[currentMatchIndex].style.outline = 'none';
        highlights[currentMatchIndex].style.backgroundColor = '#ffd700';
    }
    currentMatchIndex = (currentMatchIndex + 1) % highlights.length;
    var el = highlights[currentMatchIndex];
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    el.style.backgroundColor = '#ffd700';
    el.style.outline = 'none';
}

function highlightSearchTerms(term) {
    var content = document.getElementById('content');
    if (!content) return;
    var highlights = content.querySelectorAll('.search-highlight');
    highlights.forEach(function (el) {
        var parent = el.parentNode;
        if (parent) {
            parent.replaceChild(document.createTextNode(el.textContent), el);
            parent.normalize();
        }
    });
    currentMatchIndex = -1;
    activeSearchTerm = term;
    if (!term || term.trim() === '') return;
    highlightTextInNode(content, term);
    findNextSearchTerm();
}

function analyzeRef(ref) {
    alert('CMD:ANALYZE_REF:' + ref);
}
